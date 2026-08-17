package com.clawmate.app.profile.share.nfc

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Typed ViewModel boundary for the Android NFC profile-knock-share screen.
 *
 * It is the only place Compose/Activity talks to: it probes real NFC availability,
 * starts/arms explicit send & receive sessions, stops/retries them, routes a peer
 * [Tag] to either send-write or receive-import, and merges received profiles
 * through the shared importer pipeline. The UI reads [uiState] and only ever calls
 * the explicit user actions ([sendProfile], [startReceive], [stopSession],
 * [retry], [probe]).
 *
 * The machine, codec and dedupe logic are pure JVM ([ProfileKnockNfcStateMachine],
 * [ProfileKnockEnvelopeCodec]) and are never bypassed.
 */
class NfcProfileShareViewModel(
    private val stateMachine: ProfileKnockNfcStateMachine,
    private val gateway: NfcProfileShareGateway,
    private val profileRepository: ProfileAvatarRepository,
) : ViewModel() {

    /** UI-facing snapshot: machine state + a human-reader summary + the profile to share. */
    data class UiState(
        val status: KnockNfcStatus = KnockNfcStatus.NOT_SUPPORTED,
        val op: ProfileKnockNfcStateMachine.Op = ProfileKnockNfcStateMachine.Op.NONE,
        val error: ProfileKnockNfcError? = null,
        val errorDetail: String? = null,
        val receiveResult: ProfileKnockNfcStateMachine.State.ReceiveResult? = null,
        val sendResult: ProfileKnockNfcStateMachine.State.SendResult? = null,
        val currentProfile: LocalAvatarProfile = LocalAvatarProfile.empty,
    ) {
        /** Capability summary the Compose screen renders without fabricating anything. */
        val capabilitySummary: String = when (status) {
            KnockNfcStatus.NOT_SUPPORTED -> "设备不支持 NFC 或无法访问 NFC 硬件"
            KnockNfcStatus.DISABLED -> "NFC 已关闭，请在系统设置中开启"
            else -> "NFC 可用"
        }

        val okToAct: Boolean = status == KnockNfcStatus.READY
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val loadedProfile = MutableStateFlow(LocalAvatarProfile.empty)

    init {
        viewModelScope.launch {
            loadedProfile.value = loadLocalProfile()
            probeInternal()
        }
    }

    /** Re-probe NFC availability (e.g. resurce); surfaces NOT_SUPPORTED / DISABLED / READY. */
    fun probe() {
        viewModelScope.launch { probeInternal() }
    }

    /**
     * Explicit user action: build the bounded outgoing profile envelope and arm a
     * SEND session, ready for the user to bring a peer tag to the phone.
     */
    fun sendProfile() {
        viewModelScope.launch {
            val payload = encodePendingPayload() ?: return@launch
            withContext(Dispatchers.Default) {
                val updated = stateMachine.startSend(payload)
                _uiState.value = _uiState.value.copy(
                    status = updated.status,
                    op = updated.op,
                    error = updated.error,
                    errorDetail = updated.errorDetail,
                    sendResult = ProfileKnockNfcStateMachine.State.SendResult.ARMED,
                )
            }
        }
    }

    /**
     * Explicit user action: arm a RECEIVE session (reader mode / foreground
     * dispatch) and register the inbound callback that feeds received NDEF
     * profile bytes into the machine (validate → dedupe → import).
     */
    fun startReceive() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val updated = stateMachine.startReceive()
                if (updated.status == KnockNfcStatus.RECEIVING) {
                    val armed = gateway.startReceive { mime, bytes ->
                        onInbound(mime, bytes)
                    }
                    if (!armed) {
                        // The machine already moved to RECEIVING; reflect reality.
                        _uiState.value = _uiState.value.copy(
                            status = KnockNfcStatus.ERROR,
                            error = ProfileKnockNfcError.DEVICE,
                            errorDetail = "无法开启 NFC 接收监听",
                        )
                        return@withContext
                    }
                }
                _uiState.value = _uiState.value.copy(
                    status = updated.status,
                    op = updated.op,
                    error = updated.error,
                    errorDetail = updated.errorDetail,
                )
            }
        }
    }

    /**
     * A peer [Tag] was discovered while a SEND session is armed: write the pending
     * bounded payload to it by handoff to the machine (device write via the gateway).
     */
    fun sendToPeerTag(tag: Tag) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val ok = stateMachine.onPeerTagForSend(tag)
                _uiState.value = _uiState.value.copy(
                    status = stateMachine.current().status,
                    op = stateMachine.current().op,
                    error = stateMachine.current().error,
                    errorDetail = stateMachine.current().errorDetail,
                    sendResult = if (ok) {
                        ProfileKnockNfcStateMachine.State.SendResult.WRITTEN
                    } else {
                        ProfileKnockNfcStateMachine.State.SendResult.FAILED
                    },
                )
            }
        }
    }

    /**
     * Inbound NDEF profile bytes from the gateway. Delegates all validation and
     * merging to the machine (which never bypasses [ProfileShareImporter]).
     */
    private fun onInbound(mime: String?, bytes: ByteArray) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val updated = stateMachine.processInbound(mime, bytes)
                _uiState.value = _uiState.value.copy(
                    status = updated.status,
                    op = updated.op,
                    error = updated.error,
                    errorDetail = updated.errorDetail,
                    receiveResult = updated.lastReceive,
                )
            }
        }
    }

    /** Explicitly cancel / stop the current session and return to IDLE. */
    fun stopSession() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val updated = stateMachine.cancel()
                _uiState.value = _uiState.value.copy(
                    status = updated.status,
                    op = updated.op,
                    error = updated.error,
                    errorDetail = updated.errorDetail,
                )
            }
        }
    }

    /** Explicitly reset after an ERROR so a fresh session can be armed. */
    fun retry() {
        viewModelScope.launch {
            val updated = stateMachine.reset()
            _uiState.value = _uiState.value.copy(
                status = updated.status,
                op = updated.op,
                error = updated.error,
                errorDetail = updated.errorDetail,
            )
        }
    }

    private suspend fun probeInternal() {
        loadedProfile.value = loadLocalProfile()
        val updated = withContext(Dispatchers.Default) { stateMachine.probe() }
        _uiState.value = _uiState.value.copy(status = updated.status, op = updated.op)
    }

    private suspend fun encodePendingPayload(): ByteArray? {
        val profile = loadedProfile.value
        val envelope = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(profile)
        val payload = ProfileKnockEnvelopeCodec.encodeOutgoingPayload(envelope)
        if (payload == null) {
            _uiState.value = _uiState.value.copy(
                status = KnockNfcStatus.ERROR,
                error = ProfileKnockNfcError.OVERSIZED,
                errorDetail = "待发送画像超出大小上限，无法通过 NFC 发送",
            )
        }
        return payload
    }

    private suspend fun loadLocalProfile(): LocalAvatarProfile =
        withContext(Dispatchers.IO) { profileRepository.snapshot() }
}
