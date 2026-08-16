package com.clawmate.app.profile.avatar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile

/**
 * Profile / Avatar screen — the real Android-visible "数字分身" page.
 *
 * Exposes genuine, on-device profile state: the user edits their display name,
 * short summary, gender/age and each persona domain summary/claims, all
 * persisted in app-private DataStore with **no cloud and no secrets**. The
 * avatar *image* generation is surfaced honestly via
 * [com.clawmate.app.profile.avatar.domain.AvatarGenerationGate] and is never
 * faked — no generated avatar appears unless a real provider is wired (not yet
 * on Android).
 *
 * HarmonyOS source reference (read-only):
 *   pages/home/HomePage.ets (avatar section)
 *   datamanager/avatar/AvatarTypes.ets
 */
@Composable
fun ProfileAvatarScreen(
    viewModel: ProfileAvatarViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedOk) {
        if (state.savedOk) {
            snackbar.showSnackbar("本地画像已保存")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF4F8FF))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text("数字分身", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("本地画像与分身生成状态", fontSize = 12.sp, color = Color(0xFF6F7684))
                }
            }

            // Honest generation status
            GenerationStatusCard(
                state = state.generationState,
                guidance = state.generationGuidance,
                completeness = state.completeness,
            )

            // Local profile editor
            ProfileEditorCard(
                profile = state.draft,
                onDisplayName = viewModel::setDisplayName,
                onShortSummary = viewModel::setShortSummary,
                onGender = viewModel::setGender,
                onAge = viewModel::setAge,
                onDomainSummary = viewModel::setDomainSummary,
                onSave = { viewModel.saveDraft() },
                saveError = state.saveError,
            )
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun GenerationStatusCard(
    state: AvatarGenerationState,
    guidance: String,
    completeness: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF5FF)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (state == AvatarGenerationState.NOT_CONFIGURED) Color(0xFFB1B7C6) else Color(0xFF7669FF), RoundedCornerShape(5.dp)),
                )
                Text(
                    "分身生成服务",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                com.clawmate.app.profile.avatar.domain.AvatarGenerationGate.label(state),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                guidance,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6F7684),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("本地画像完整度", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF272C38))
                LinearProgressIndicator(
                    progress = { completeness / 100f },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).height(6.dp),
                    color = Color(0xFF7669FF),
                    trackColor = Color(0xFFE5E7EF),
                )
                Text("$completeness%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7669FF))
            }
        }
    }
}

@Composable
private fun ProfileEditorCard(
    profile: LocalAvatarProfile,
    onDisplayName: (String) -> Unit,
    onShortSummary: (String) -> Unit,
    onGender: (String) -> Unit,
    onAge: (String) -> Unit,
    onDomainSummary: (String, String) -> Unit,
    onSave: () -> Unit,
    saveError: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本地画像（设备内保存）", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅保存在本机，不上传；用于描述你希望数字分身呈现的样子。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6F7684),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            OutlinedTextField(
                value = profile.displayName,
                onValueChange = onDisplayName,
                label = { Text("展示名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = profile.shortSummary,
                onValueChange = onShortSummary,
                label = { Text("一句话简介") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profile.gender,
                    onValueChange = onGender,
                    label = { Text("性别") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = profile.age,
                    onValueChange = onAge,
                    label = { Text("年龄") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                "画像维度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            profile.domains.forEach { domain ->
                OutlinedTextField(
                    value = domain.shortSummary,
                    onValueChange = { onDomainSummary(domain.domain, it) },
                    label = { Text("${domain.displayName} · ${domain.domain}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            saveError?.let {
                Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onSave,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7669FF)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("保存本地画像", fontWeight = FontWeight.Bold)
            }
        }
    }
}
