package com.clawmate.app.profile.avatar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.profile.avatar.data.AvatarAssetRepository
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause
import com.clawmate.app.profile.avatar.data.generation.AvatarGenerationParams
import com.clawmate.app.profile.avatar.data.generation.AvatarProvider
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderConfig
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderRequest
import com.clawmate.app.profile.avatar.domain.AvatarGenerationGate
import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.AvatarProviderSettings
import com.clawmate.app.profile.avatar.domain.AvatarRunState
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.avatarRunStateLabel
import com.clawmate.app.profile.avatar.domain.buildAvatarGenerationPrompt
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import com.clawmate.app.settings.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Profile / Avatar screen. */
data class ProfileAvatarUiState(
    val profile: LocalAvatarProfile = LocalAvatarProfile.empty,
    val draft: LocalAvatarProfile = LocalAvatarProfile.empty,
    val completeness: Int = 0,
    val generationState: AvatarGenerationState = AvatarGenerationState.NOT_CONFIGURED,
    val generationGuidance: String = "",
    val saveError: String? = null,
    val savedOk: Boolean = false,
    // --- Local asset repository (portable part of row A2) ---
    val currentAvatar: AvatarVersionRecord? = null,
    val avatarImagePath: String? = null, // absolute app-private file path for the current local image
    val versionHistory: List<AvatarVersionRecord> = emptyList(),
    val isImporting: Boolean = false,
    val assetMessage: AssetMessage? = null,
    // --- Phase 24: generation run lifecycle (row A2) ---
    val runState: AvatarRunState = AvatarRunState.IDLE,
    val runStateLabel: String = "",
    val generationError: String? = null, // redacted; never contains a secret
)

/** One-shot honest snackbar message for the local asset repository. */
data class AssetMessage(val text: String, val isError: Boolean = false)

/**
 * ViewModel for the real Profile / Avatar screen: exposes the locally editable
 * avatar profile, derives the honest avatar-generation gate from the configured
 * settings, persists edits to an app-private DataStore, and drives the local
 * avatar **asset repository** (import/display/persist a real user-selected
 * local image, version history, clear). It never performs network I/O and never
 * fabricates a generated avatar.
 */
class ProfileAvatarViewModel(
    private val profileRepository: ProfileAvatarRepository,
    private val settingsRepository: SettingsRepository,
    private val assetRepository: AvatarAssetRepository,
    private val provider: AvatarProvider? = null,
) : ViewModel() {

    private val saveError = MutableStateFlow<String?>(null)
    private val savedOk = MutableStateFlow(false)
    private val currentAvatar = MutableStateFlow<AvatarVersionRecord?>(null)
    private val avatarImagePath = MutableStateFlow<String?>(null)
    private val versionHistory = MutableStateFlow<List<AvatarVersionRecord>>(emptyList())
    private val isImporting = MutableStateFlow(false)
    private val assetMessage = MutableStateFlow<AssetMessage?>(null)
    private val runState = MutableStateFlow(AvatarRunState.IDLE)
    private val generationError = MutableStateFlow<String?>(null)

    // Working draft (unsaved edits) separate from the persisted snapshot so
    // field edits never touch storage until the user taps save.
    private val draft = MutableStateFlow(LocalAvatarProfile.empty)

    private val settings = settingsRepository.settings

    val uiState: StateFlow<ProfileAvatarUiState> = combine(
        combine(profileRepository.profile, settings) { profile, settings -> profile to settings },
        combine(draft, saveError, savedOk) { draft, error, ok -> Triple(draft, error, ok) },
        combine(currentAvatar, avatarImagePath, versionHistory) { current, imagePath, history ->
            Triple(current, imagePath, history)
        },
        combine(isImporting, assetMessage) { importing, message -> importing to message },
        combine(runState, generationError) { run, error -> run to error },
    ) { (profile, settings), (draft, error, ok), (current, imagePath, history), (importing, message), (run, genError) ->
        ProfileAvatarUiState(
            profile = profile,
            draft = draft,
            completeness = localProfileCompleteness(profile),
            generationState = generationState(settings),
            generationGuidance = generationGuidance(settings, profile),
            saveError = error,
            savedOk = ok,
            currentAvatar = current,
            avatarImagePath = imagePath,
            versionHistory = history,
            isImporting = importing,
            assetMessage = message,
            runState = run,
            runStateLabel = avatarRunStateLabel(run),
            generationError = genError,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileAvatarUiState())

    init {
        viewModelScope.launch {
            val current = profileRepository.snapshot()
            draft.value = current
            refreshAssetState()
        }
    }

    /** Called from the UI as the user types; updates the working draft only. */
    fun onFieldUpdate(transform: (LocalAvatarProfile) -> LocalAvatarProfile) {
        val updated = transform(draft.value)
        draft.value = updated
        savedOk.value = false
        saveError.value = null
    }

    fun setDisplayName(value: String) = onFieldUpdate { it.copy(displayName = value) }
    fun setShortSummary(value: String) = onFieldUpdate { it.copy(shortSummary = value) }
    fun setGender(value: String) = onFieldUpdate { it.copy(gender = value) }
    fun setAge(value: String) = onFieldUpdate { it.copy(age = value) }

    fun setDomainSummary(domain: String, value: String) = onFieldUpdate { profile ->
        profile.copy(
            domains = profile.domains.map {
                if (it.domain == domain) it.copy(shortSummary = value) else it
            },
        )
    }

    fun setDomainClaim(domain: String, claims: List<String>) = onFieldUpdate { profile ->
        profile.copy(
            domains = profile.domains.map {
                if (it.domain == domain) it.copy(claims = claims) else it
            },
        )
    }

    /** Persist the current draft (with title-line validation). */
    fun saveDraft() {
        viewModelScope.launch {
            saveError.value = null
            val draftValue = draft.value
            if (draftValue.displayName.isBlank()) {
                saveError.value = "展示名称不能为空"
                return@launch
            }
            val saved = profileRepository.save(draftValue)
            draft.value = saved
            savedOk.value = true
        }
    }

    fun consumeSavedOk() {
        savedOk.value = false
    }

    // --- Local avatar asset repository (row A2 portable subset) -------------

    /**
     * Import a *real* user-selected local image selected via the system photo
     * picker. [bytes] are content-read from the granted picker URI and passed to
     * the repository which applies bounded validation (byte cap + actual decode)
     * before persisting an honest `local-import` version. Never a fake image.
     */
    fun importAvatarImage(
        bytes: ByteArray,
        displayLabel: String,
        sourceMimeType: String,
    ) {
        if (isImporting.value) return
        viewModelScope.launch {
            isImporting.value = true
            assetMessage.value = null
            val result = assetRepository.importLocalImage(bytes, displayLabel, sourceMimeType)
            if (result.success) {
                assetMessage.value = AssetMessage("已保存本机照片为数字分身")
                refreshAssetState()
            } else {
                assetMessage.value = AssetMessage(result.errorMessage ?: "导入失败", isError = true)
            }
            isImporting.value = false
        }
    }

    /** Remove one version from the local avatar history. */
    fun deleteVersion(versionId: String) {
        viewModelScope.launch {
            val removed = assetRepository.deleteVersion(versionId)
            if (removed) {
                assetMessage.value = AssetMessage("已删除该分身版本")
            }
            refreshAssetState()
        }
    }

    /** Clear every local avatar version + asset (honest clear behavior). */
    fun clearAvatar() {
        viewModelScope.launch {
            val result = assetRepository.clearAvatar()
            assetMessage.value = AssetMessage(
                if (result.clearedVersionCount > 0) "已清空全部本地分身" else "当前没有可清空的分身",
            )
            refreshAssetState()
        }
    }

    /** Read the latest version history + current image into the UI state. */
    fun refreshAssetState() {
        viewModelScope.launch {
            val history = assetRepository.listVersions()
            val version = assetRepository.currentVersion()
            versionHistory.value = history
            currentAvatar.value = version
            avatarImagePath.value = version?.let { resolveLocalAssetPath(it.imageRelativePath) }
        }
    }

    fun consumeAssetMessage() {
        assetMessage.value = null
    }

    /** Surface an honest error raised outside the repository (e.g. unreadable picker URI). */
    fun showAssetMessage(text: String, isError: Boolean = true) {
        assetMessage.value = AssetMessage(text, isError)
    }

    // --- Phase 24: user-initiated avatar generation (migration row A2) -------

    /**
     * Generate a real digital avatar from the locally curated profile using the
     * configured Seedream provider. Explicit user action (tapping 生成数字分身)
     * is the recorded consent for text-to-image generation (no photo is used).
     *
     * Honest state machine: the gate must be READY **and** a provider must be
     * wired and the profile completeness must reach the minimum threshold —
     * otherwise the action backs out with [AvatarRunState.ERROR] and **no network
     * call is made**. On success the provider's real image bytes are persisted
     * through the app-private [AvatarAssetRepository.persistGeneratedImage] and
     * the run resolves to [AvatarRunState.SUCCESS]. Provider errors are surfaced
     * redacted (never the api key).
     */
    fun generateAvatar() {
        val provider = provider ?: run {
            generationError.value = "尚未接入云端生成服务"
            runState.value = AvatarRunState.ERROR
            return
        }
        viewModelScope.launch {
            val settingsSnapshot = settingsRepositorySnapshot()
            val gate = generationState(settingsSnapshot)
            if (!AvatarGenerationGate.canGenerate(gate)) {
                generationError.value = AvatarGenerationGate.guidance(gate, hasCurrentProfile = true)
                runState.value = AvatarRunState.ERROR
                return@launch
            }
            val profile = profileRepository.snapshot()
            if (localProfileCompleteness(profile) < AvatarGenerationGate.minProfileCompleteness) {
                generationError.value = "本地画像完整度不足，无法生成。请先完善本地画像。"
                runState.value = AvatarRunState.ERROR
                return@launch
            }

            val prompt = buildAvatarGenerationPrompt(profile)
            val config = buildConfig(settingsSnapshot)
            val request = AvatarProviderRequest(
                prompt = prompt.text,
                model = config.textToImageModel,
                params = AvatarGenerationParams(
                    size = config.size,
                    responseFormat = config.responseFormat,
                    outputFormat = config.outputFormat,
                    watermark = config.watermark,
                    stream = false,
                    sequentialImageGeneration = "disabled",
                ),
            )
            generationError.value = null
            runState.value = AvatarRunState.RUNNING
            try {
                val result = provider.generateTextToImage(config, request)
                if (result.imageBytesBase64.isBlank()) {
                    throw com.clawmate.app.profile.avatar.data.generation.AvatarProviderException(
                        com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause.PARSE,
                        "生成服务未返回图片数据",
                    )
                }
                val bytes = com.clawmate.app.profile.avatar.data.generation.SeedreamResponseParser
                    .decodeBoundedBase64(result.imageBytesBase64)
                    ?: throw com.clawmate.app.profile.avatar.data.generation.AvatarProviderException(
                        com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause.UNSUPPORTED_IMAGE,
                        "生成的图片数据无效或超出大小上限",
                    )
                val persisted = assetRepository.persistGeneratedImage(
                    bytes = bytes,
                    outputFormat = config.outputFormat,
                    mode = AvatarGenerationMode.TEXT_TO_IMAGE,
                    model = config.textToImageModel,
                    prompt = prompt.text,
                    profileHash = profileSnapshotHash(profile),
                )
                if (!persisted.success) {
                    throw com.clawmate.app.profile.avatar.data.generation.AvatarProviderException(
                        com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause.INTERNAL,
                        persisted.errorMessage ?: "保存生成的图片失败",
                    )
                }
                runState.value = AvatarRunState.SUCCESS
                assetMessage.value = AssetMessage("已生成数字分身并保存在本机")
                refreshAssetState()
            } catch (e: com.clawmate.app.profile.avatar.data.generation.AvatarProviderException) {
                generationError.value = redactError(e, config)
                runState.value = AvatarRunState.ERROR
            } catch (e: kotlinx.coroutines.CancellationException) {
                runState.value = AvatarRunState.ERROR
                generationError.value = "生成已取消"
                throw e
            } catch (e: Exception) {
                // Never leak provider internals or the api key into the UI.
                generationError.value = redactError(e, config)
                runState.value = AvatarRunState.ERROR
            }
        }
    }

    /**
     * Redact any raw exception into a one-line, secret-free message. Defence in
     * depth on top of the provider: the configured api key (and any sk-*-shaped
     * token) is scrubbed from anything surfaced to the UI.
     */
    private fun redactError(e: Throwable, config: AvatarProviderConfig): String {
        val key = config.apiKey.trim()
        val raw = e.message ?: "生成失败"
        var text = raw.lines().first().trim().take(200).ifBlank { "生成失败" }
        if (key.isNotEmpty()) text = text.replace(key, "•••")
        // Guard against sk-*/Bearer fragments leaking regardless of exact match.
        text = text.replace(Regex("(?i)(sk-[A-Za-z0-9_-]{4,})"), "•••")
        text = text.replace("Bearer ???", "")
        return text
    }

    private suspend fun settingsRepositorySnapshot(): com.clawmate.app.settings.domain.AppSettings =
        settingsRepository.settings.first()

    private fun buildConfig(settings: com.clawmate.app.settings.domain.AppSettings): AvatarProviderConfig =
        AvatarProviderConfig(
            imageBaseUrl = settings.avatarImageBaseUrl.trim().trimEnd('/'),
            apiKey = settings.avatarImageApiKey.trim(),
            textToImageModel = settings.avatarTextToImageModel.trim(),
            size = settings.avatarImageSize.trim(),
            responseFormat = "url",
            outputFormat = "jpeg",
            watermark = true,
            timeoutMs = 60_000L,
        )

    private fun profileSnapshotHash(profile: LocalAvatarProfile): String {
        val text = buildString {
            append(profile.displayName)
            append(profile.gender)
            append(profile.age)
            profile.domains.forEach { append(it.domain).append(':').append(it.shortSummary) }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
    }

    fun resolveLocalAssetPath(relativePath: String): String? =
        assetRepository.absoluteAssetPath(relativePath)?.let { "file://$it" }

    private fun generationState(settings: com.clawmate.app.settings.domain.AppSettings): AvatarGenerationState =
        AvatarGenerationGate.state(
            AvatarProviderSettings(
                imageBaseUrl = settings.avatarImageBaseUrl,
                apiKey = settings.avatarImageApiKey,
                textToImageModel = settings.avatarTextToImageModel,
                imageSize = settings.avatarImageSize,
            ),
        )

    private fun generationGuidance(
        settings: com.clawmate.app.settings.domain.AppSettings,
        profile: LocalAvatarProfile,
    ): String = AvatarGenerationGate.guidance(
        generationState(settings),
        hasCurrentProfile = localProfileCompleteness(profile) > 0,
    )

    class Factory(
        private val profileRepository: ProfileAvatarRepository,
        private val settingsRepository: SettingsRepository,
        private val assetRepository: AvatarAssetRepository,
        private val provider: AvatarProvider? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileAvatarViewModel(profileRepository, settingsRepository, assetRepository, provider) as T
    }
}

