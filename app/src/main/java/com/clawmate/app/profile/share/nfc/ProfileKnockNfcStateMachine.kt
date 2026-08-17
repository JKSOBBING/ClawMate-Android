package com.clawmate.app.profile.share.nfc

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.share.ProfileShareErrorCode
import com.clawmate.app.profile.share.ProfileShareImporter
import com.clawmate.app.profile.share.ProfileShareParseResult

/**
 * Pure, deterministic state machine for the Android NFC profile-share sessions.
 *
 * It owns the *decision* logic of a send/receive session while the device-facing
 * [NfcProfileShareGateway] does the NFC I/O. Keeping every transition and every
 * rejection rule in one JVM-testable class lets the whole flow be verified
 * offline: NOT_SUPPORTED / DISABLED gating, READY → SENDING / RECEIVING, tag
 * receipt → validate → dedupe → import, CANCELLED, and typed ERROR states.
 *
 * Only the four-allowed-local-domain merge (via the existing [ProfileShareImporter])
 * is ever applied; the state machine never bypasses that shared pipeline. The
 * importer's "local-first, blank-only fill" precedence is preserved end to end.
 *
 * API: model → outside caller (the ViewModel) feeds observed events; the state
 * machine emits a [State]. The [processInbound] / [persistOutcome] steps call the
 * injected persist functions so a real save is applied exactly once per session.
 */
class ProfileKnockNfcStateMachine(
    private val gateway: NfcProfileShareGateway,
    private val dedupe: ProfileKnockDedupeStore,
    private val loadLocal: suspend () -> LocalAvatarProfile,
    private val saveLocal: suspend (LocalAvatarProfile) -> LocalAvatarProfile,
) {

    /** The immutable snapshot of the machine that UI reads. */
    data class State(
        val status: KnockNfcStatus = KnockNfcStatus.READY,
        val op: Op = Op.NONE,
        val error: ProfileKnockNfcError? = null,
        val errorDetail: String? = null,
        val lastReceive: ReceiveResult? = null,
        val lastSend: SendResult? = null,
    ) {
        enum class SendResult { NOT_STARTED, ARMED, WRITTEN, FAILED }
        enum class ReceiveResult { NONE, RECEIVED, DUPLICATE, REJECTED }
    }

    /** Which explicit session (if any) is currently armed. */
    enum class Op { NONE, SEND, RECEIVE }

    private var state = State()
    private val consumedSignaturesInSession = mutableSetOf<String>()

    /** The incoming NDEF payload under construction for a send (set when armed). */
    private var pendingSendBytes: ByteArray? = null

    fun current(): State = state

    /**
     * Re-probe availability (e.g. when the screen resumes). Transitions to
     * NOT_SUPPORTED / DISABLED / READY; armed sessions are cancelled if the
     * hardware disappeared.
     */
    fun probe(): State {
        val availability = gateway.probeAvailability()
        state = when (availability) {
            KnockNfcStatus.NOT_SUPPORTED -> State(status = KnockNfcStatus.NOT_SUPPORTED, op = Op.NONE)
            KnockNfcStatus.DISABLED -> State(status = KnockNfcStatus.DISABLED, op = Op.NONE)
            else -> State(status = KnockNfcStatus.READY, op = Op.NONE)
        }
        pendingSendBytes = null
        return state
    }

    /**
     * Begin an explicit send session after the caller encoded [payload]. Only a
     * READY machine may arm a send; arming starts NDEF tech-disable readiness so
     * `gateway` is not touched here (the real push happens on the device adapter
     * when a peer tag is presented).
     */
    fun startSend(payload: ByteArray): State {
        if (state.status != KnockNfcStatus.READY || state.op != Op.NONE) {
            state = state.failed(ProfileKnockNfcError.INVALID_OPERATION, "仅在 READY 状态可开始发送（当前 ${state.status}）")
            return state
        }
        if (payload.isEmpty()) {
            state = state.failed(ProfileKnockNfcError.OVERSIZED, "待发送画像为空")
            return state
        }
        pendingSendBytes = payload
        state = State(status = KnockNfcStatus.SENDING, op = Op.SEND)
        return state
    }

    /**
     * The peer tag to which we must write the pending payload arrived. Returns
     * true and transitions to SUCCESS when [gateway.writeTag] succeeds, otherwise
     * transitions to ERROR([ProfileKnockNfcError.NDEF_WRITE_FAILED]).
     */
    fun onPeerTagForSend(tagToken: Any): Boolean {
        val payload = pendingSendBytes ?: run {
            state = state.failed(ProfileKnockNfcError.INVALID_OPERATION, "没有待发送的画像")
            return false
        }
        if (state.op != Op.SEND) {
            state = state.failed(ProfileKnockNfcError.INVALID_OPERATION, "当前未处于发送会话")
            return false
        }
        return try {
            val failure = gateway.writeTag(tagToken, payload)
            if (failure == null) {
                state = State(status = KnockNfcStatus.SUCCESS, op = Op.NONE)
                true
            } else {
                state = state.failed(ProfileKnockNfcError.NDEF_WRITE_FAILED, failure)
                false
            }
        } catch (t: Throwable) {
            state = state.failed(ProfileKnockNfcError.DEVICE, "写入 NFC 标签失败：${t.message}")
            false
        }
    }

    /**
     * Begin an explicit receive session (reader mode / foreground dispatch).
     * Only a READY machine may arm receiving. Arming records the new session in
     * the machine; the actual [NfcProfileShareGateway.startReceive] registration
     * is performed by the host when the inbound callback is observed.
     */
    fun startReceive(): State {
        if (state.status != KnockNfcStatus.READY || state.op != Op.NONE) {
            state = state.failed(ProfileKnockNfcError.INVALID_OPERATION, "仅在 READY 状态可开始接收（当前 ${state.status}）")
            return state
        }
        state = State(status = KnockNfcStatus.RECEIVING, op = Op.RECEIVE)
        return state
    }

    /**
     * Process an inbound NDEF payload received during a RECEIVE session.
     *
     * Validates MIME + size + UTF-8/JSON + schema/version + app/type identity via
     * [ProfileKnockEnvelopeCodec.parseAndValidateNfc] (which delegates to the
     * shared [ProfileShareImporter]), then either re-imports nothing (duplicate /
     * rejected) or runs the shared merge pipeline and persists the result.
     */
    suspend fun processInbound(mime: String?, bytes: ByteArray): State {
        if (state.op != Op.RECEIVE) {
            state = state.failed(ProfileKnockNfcError.INVALID_OPERATION, "未处于接收会话")
            return state
        }
        val signature = ProfileKnockEnvelopeCodec.messageSignature(bytes)
        if (dedupe.isConsumed(signature) || signature in consumedSignaturesInSession) {
            state = State(
                status = KnockNfcStatus.SUCCESS,
                op = Op.NONE,
                lastReceive = State.ReceiveResult.DUPLICATE,
            )
            return state
        }

        when (val parsed = ProfileKnockEnvelopeCodec.parseAndValidateNfc(mime, bytes)) {
            is ProfileShareParseResult.Error -> {
                state = state.failed(mapError(parsed.code), parsed.message)
                return state
            }
            is ProfileShareParseResult.Success -> {
                val local = loadLocal()
                val outcome = ProfileShareImporter.mergeIntoLocal(local, parsed.envelope)
                saveLocal(outcome.profile)
                dedupe.markConsumed(signature)
                consumedSignaturesInSession.add(signature)
                if (consumedSignaturesInSession.size > MAX_SESSION_SIGNATURES) {
                    val ordered = consumedSignaturesInSession.toList()
                    consumedSignaturesInSession.clear()
                    consumedSignaturesInSession.addAll(ordered.takeLast(MAX_SESSION_SIGNATURES))
                }
                state = State(
                    status = KnockNfcStatus.SUCCESS,
                    op = Op.NONE,
                    lastReceive = State.ReceiveResult.RECEIVED,
                )
                return state
            }
        }
    }

    /** Explicitly cancel the current session (stop listening / discard a pending send). */
    fun cancel(): State {
        runCatching { gateway.stopReceive() }
        pendingSendBytes = null
        state = if (state.op == Op.NONE) {
            state.copy(status = KnockNfcStatus.READY)
        } else {
            State(status = KnockNfcStatus.CANCELLED, op = Op.NONE)
        }
        return state
    }

    /** Arm a fresh session right after an ERROR so the user can retry. */
    fun reset(): State {
        runCatching { gateway.stopReceive() }
        pendingSendBytes = null
        state = State(status = if (gateway.probeAvailability() == KnockNfcStatus.READY) KnockNfcStatus.READY else KnockNfcStatus.NOT_SUPPORTED)
        return state
    }

    private fun State.failed(reason: ProfileKnockNfcError, detail: String): State =
        State(status = KnockNfcStatus.ERROR, op = Op.NONE, error = reason, errorDetail = detail)

    private fun mapError(code: ProfileShareErrorCode): ProfileKnockNfcError = when (code) {
        ProfileShareErrorCode.OVERSIZED -> ProfileKnockNfcError.OVERSIZED
        ProfileShareErrorCode.MALFORMED -> ProfileKnockNfcError.MALFORMED
        ProfileShareErrorCode.UNSUPPORTED_VERSION -> ProfileKnockNfcError.UNSUPPORTED_VERSION
        ProfileShareErrorCode.MISSING_FIELD -> ProfileKnockNfcError.MISSING_FIELD
        ProfileShareErrorCode.UNRECOGNIZED -> ProfileKnockNfcError.UNRECOGNIZED
    }

    private companion object {
        const val MAX_SESSION_SIGNATURES: Int = 30
    }
}
