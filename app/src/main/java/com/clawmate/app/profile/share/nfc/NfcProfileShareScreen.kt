package com.clawmate.app.profile.share.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose screen for the Android NFC profile-knock-share boundary. It only ever
 * surfaces real device state through the ViewModel and exposes ONLY explicit
 * user actions: start send, start receive, stop, retry, re-probe. It never
 * auto-starts NFC on launch, on recomposition, or in the background.
 */
@Composable
fun NfcProfileShareScreen(
    viewModel: NfcProfileShareViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("一碰分享 · NFC", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "将你的 ClawMate 画像通过 NFC 发送给另一台解锁的 ClawMate，或从对方接收画像并合并。步骤均需你显式点击开始。",
                style = MaterialTheme.typography.bodyMedium,
            )

            CapabilityCard(state)

            when (state.status) {
                KnockNfcStatus.READY, KnockNfcStatus.SUCCESS, KnockNfcStatus.CANCELLED -> {
                    ActionRow(viewModel, state)
                }
                KnockNfcStatus.RECEIVING, KnockNfcStatus.SENDING -> {
                    SessionIndicator(state)
                    StopButton(viewModel)
                }
                KnockNfcStatus.ERROR -> {
                    ErrorMessage(state)
                    RetryRow(viewModel)
                }
                KnockNfcStatus.NOT_SUPPORTED, KnockNfcStatus.DISABLED -> {
                    ActionRow(viewModel, state)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(state: NfcProfileShareViewModel.UiState) {
    Column(Modifier.fillMaxWidth()) {
        Text("设备 NFC 状态", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            state.capabilitySummary,
            style = MaterialTheme.typography.bodyLarge,
            color = when (state.status) {
                KnockNfcStatus.READY -> MaterialTheme.colorScheme.primary
                KnockNfcStatus.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ActionRow(viewModel: NfcProfileShareViewModel, state: NfcProfileShareViewModel.UiState) {
    Column(Modifier.fillMaxWidth()) {
        Text("显式操作", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::sendProfile,
            enabled = state.okToAct,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始发送画像（靠近目标手机）")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::startReceive,
            enabled = state.okToAct,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始接收画像（靠近对方手机）")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = viewModel::probe,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("检查 NFC 可用状态")
        }
    }
}

@Composable
private fun SessionIndicator(state: NfcProfileShareViewModel.UiState) {
    Column(Modifier.fillMaxWidth()) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.status == KnockNfcStatus.RECEIVING) {
                "正在接收：请将对方解锁的 ClawMate 手机（含画像）贴近本机 NFC 感应区…"
            } else {
                "正在发送：请将本机贴近目标手机 NFC 感应区…"
            },
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ErrorMessage(state: NfcProfileShareViewModel.UiState) {
    Column(Modifier.fillMaxWidth()) {
        Text("操作失败", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            state.errorDetail ?: "发生未预期错误，请检查 NFC 状态后重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StopButton(viewModel: NfcProfileShareViewModel) {
    Button(
        onClick = viewModel::stopSession,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("停止当前会话")
    }
}

@Composable
private fun RetryRow(viewModel: NfcProfileShareViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = viewModel::retry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重试")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = viewModel::probe,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("检查 NFC 可用状态")
        }
    }
}
