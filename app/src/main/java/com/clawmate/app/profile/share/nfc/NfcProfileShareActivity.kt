package com.clawmate.app.profile.share.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.share.nfc.device.AndroidNfcProfileShareGateway
import com.clawmate.app.profile.share.nfc.device.PrefsProfileKnockDedupeStore
import kotlinx.coroutines.launch

/**
 * Lifecycle-safe host for the Android NFC profile-knock-share screen.
 *
 * It owns the [NfcAdapter] reader-mode session: NfcAdapter reader mode is enabled
 * in [onResume] and *always* disabled in [onPause] (so NFC resources are released
 * when this screen leaves the foreground), and never works in the background.
 * A discovered [Tag] is handed to the ViewModel, which decides whether the active
 * session is SEND (write the pending payload) or RECEIVE (read + import).
 *
 * The ViewModel is the only UI-reachable boundary; this Activity only wires the
 * device-facing gateway and the lifecycle.
 */
class NfcProfileShareActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private lateinit var gateway: AndroidNfcProfileShareGateway
    private lateinit var viewModel: NfcProfileShareViewModel
    private var nfcAdapter: NfcAdapter? = null
    private var readerModeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gateway = AndroidNfcProfileShareGateway(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repo = DataStoreProfileAvatarRepository(this@NfcProfileShareActivity.applicationContext)
                    val dedupe = PrefsProfileKnockDedupeStore(this@NfcProfileShareActivity.applicationContext)
                    val machine = ProfileKnockNfcStateMachine(
                        gateway = gateway,
                        dedupe = dedupe,
                        loadLocal = { repo.snapshot() },
                        saveLocal = { repo.save(it) },
                    )
                    return NfcProfileShareViewModel(machine, gateway, repo) as T
                }
            },
        )[NfcProfileShareViewModel::class.java]

        setContent {
            ProfileShareTheme {
                NfcProfileShareScreen(viewModel = viewModel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState.collect { state ->
                    if (state.op == ProfileKnockNfcStateMachine.Op.NONE) {
                        disableReaderMode()
                    } else {
                        enableReaderMode()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        disableReaderMode()
        super.onPause()
    }

    override fun onDestroy() {
        disableReaderMode()
        super.onDestroy()
    }

    override fun onTagDiscovered(tag: Tag) {
        // Only deliver when a session is actually armed; otherwise ignore.
        val current = viewModel.uiState.value
        when (current.op) {
            ProfileKnockNfcStateMachine.Op.SEND -> viewModel.sendToPeerTag(tag)
            ProfileKnockNfcStateMachine.Op.RECEIVE -> gateway.onTagDiscovered(tag)
            else -> Unit
        }
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        if (readerModeEnabled) return
        runCatching {
            adapter.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
            readerModeEnabled = true
        }
    }

    private fun disableReaderMode() {
        val adapter = nfcAdapter ?: return
        runCatching { adapter.disableReaderMode(this) }
        readerModeEnabled = false
    }
}

@Composable
private fun ProfileShareTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme {
        content()
    }
}
