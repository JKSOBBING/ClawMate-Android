package com.clawmate.app.profile.avatar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.profile.avatar.data.asset.AvatarImageContentReader
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.ui.ClawMateInput
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Profile / Avatar screen — the real Android-visible "数字分身" page.
 *
 * Two genuinely on-device surfaces:
 *  1. **Local profile editor** — display name, short summary, gender/age and
 *     each persona domain summary/claims, persisted in app-private DataStore.
 *  2. **Local avatar asset repository** (portable part of migration row A2) —
 *     the user picks a *real* image from the system photo picker; it is read
 *     bounded, validated to decode, and persisted as an app-private
 *     `local-import` version that is displayed (Coil) and kept in an ordered
 *     version history with delete + clear. This is a genuine avatar asset —
 *     never a fabricated/generated one.
 *
 * The avatar *image generation* stays honestly gated via
 * [com.clawmate.app.profile.avatar.domain.AvatarGenerationGate] and is never
 * faked — no cloud output appears unless a real provider is wired (not yet on
 * Android).
 *
 * HarmonyOS source reference (read-only):
 *   pages/home/HomePage.ets (avatar section)
 *   datamanager/avatar/AvatarTypes.ets / AvatarStorage.ets / AvatarService.ets
 */
@Composable
fun ProfileAvatarScreen(
    viewModel: ProfileAvatarViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current.applicationContext

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val bytes = AvatarImageContentReader.readBounded(context, uri)
            if (bytes == null) {
                viewModel.showAssetMessage("无法读取所选图片或文件过大", isError = true)
            } else {
                viewModel.importAvatarImage(
                    bytes = bytes,
                    displayLabel = "本机相册照片",
                    sourceMimeType = context.contentResolver.getType(uri) ?: "",
                )
            }
        }
    }
    val launchPicker = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(state.savedOk) {
        if (state.savedOk) snackbar.showSnackbar("本地画像已保存")
    }
    LaunchedEffect(state.assetMessage) {
        state.assetMessage?.let { message ->
            snackbar.showSnackbar(message.text)
            viewModel.consumeAssetMessage()
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
                runState = state.runState,
                runStateLabel = state.runStateLabel,
                generationError = state.generationError,
                isGenerating = state.runState == com.clawmate.app.profile.avatar.domain.AvatarRunState.RUNNING,
                canGenerate = state.generationState == AvatarGenerationState.READY &&
                    state.runState != com.clawmate.app.profile.avatar.domain.AvatarRunState.RUNNING,
                onGenerate = viewModel::generateAvatar,
            )

            // Local avatar asset card (display + import + history + clear)
            LocalAvatarAssetCard(
                imagePath = state.avatarImagePath,
                hasAvatar = state.currentAvatar != null,
                versionHistory = state.versionHistory,
                isImporting = state.isImporting,
                onPickImage = launchPicker,
                onDeleteVersion = viewModel::deleteVersion,
                onClearAvatar = viewModel::clearAvatar,
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
private fun LocalAvatarAssetCard(
    imagePath: String?,
    hasAvatar: Boolean,
    versionHistory: List<AvatarVersionRecord>,
    isImporting: Boolean,
    onPickImage: () -> Unit,
    onDeleteVersion: (String) -> Unit,
    onClearAvatar: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("本机分身照片（设备内保存）", style = MaterialTheme.typography.titleMedium)
            Text(
                "从相册选择一张真实照片，仅保存在本机并按\"数字分身\"展示；不会生成、也绝不凭空造出图片。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6F7684),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            if (imagePath != null && hasAvatar) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "当前本机分身照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color(0xFFE8F1FF), RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color(0xFFEDF1F8), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isImporting) "正在导入…" else "尚未选择本机照片", color = Color(0xFF6F7684))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onPickImage,
                    enabled = !isImporting,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7669FF)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("选择本机照片", fontWeight = FontWeight.Bold)
                }
                if (versionHistory.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearAvatar,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("清空")
                    }
                }
            }

            if (versionHistory.isNotEmpty()) {
                Text(
                    "分身版本历史（最新在前）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                versionHistory.forEach { version ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(version.promptSnapshot.ifBlank { version.versionId }, fontSize = 14.sp)
                            Text(
                                "导入于 ${version.createdAt} · ${version.provider}",
                                fontSize = 12.sp,
                                color = Color(0xFF6F7684),
                            )
                        }
                        TextButton(onClick = { onDeleteVersion(version.versionId) }) {
                            Text("删除", color = Color(0xFFC62828))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerationStatusCard(
    state: AvatarGenerationState,
    guidance: String,
    completeness: Int,
    runState: com.clawmate.app.profile.avatar.domain.AvatarRunState,
    runStateLabel: String,
    generationError: String?,
    isGenerating: Boolean,
    canGenerate: Boolean,
    onGenerate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF5FF)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    state == AvatarGenerationState.NOT_CONFIGURED -> Color(0xFFB1B7C6)
                    state == AvatarGenerationState.PARTIALLY_CONFIGURED -> Color(0xFFF59E0B)
                    isGenerating -> Color(0xFF7669FF)
                    else -> Color(0xFF3FA72F)
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, RoundedCornerShape(5.dp)),
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

            // Real run-state / error surface (never a faked success).
            if (isGenerating) {
                Text(
                    runStateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7669FF),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            generationError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (runState == com.clawmate.app.profile.avatar.domain.AvatarRunState.SUCCESS) {
                Text(
                    runStateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF3FA72F),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

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

            Button(
                onClick = onGenerate,
                enabled = canGenerate,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7669FF)),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(
                    if (isGenerating) "生成中…" else "生成数字分身",
                    fontWeight = FontWeight.Bold,
                )
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

            ClawMateInput(
                value = profile.displayName,
                onValueChange = onDisplayName,
                label = "展示名称",
                singleLine = true,
            )
            ClawMateInput(
                value = profile.shortSummary,
                onValueChange = onShortSummary,
                label = "一句话简介",
                singleLine = true,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClawMateInput(
                    value = profile.gender,
                    onValueChange = onGender,
                    label = "性别",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                ClawMateInput(
                    value = profile.age,
                    onValueChange = onAge,
                    label = "年龄",
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
                ClawMateInput(
                    value = domain.shortSummary,
                    onValueChange = { onDomainSummary(domain.domain, it) },
                    label = "${domain.displayName} · ${domain.domain}",
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            saveError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onSave,
                shape = RoundedCornerShape(ClawMateDesign.Shape.Control),
                colors = ButtonDefaults.buttonColors(containerColor = ClawMateDesign.Colors.Accent),
                modifier = Modifier.fillMaxWidth().height(ClawMateDesign.Size.ButtonHeight).padding(top = 8.dp),
            ) {
                Text("保存本地画像", fontWeight = FontWeight.Bold)
            }
        }
    }
}
