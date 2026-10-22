package com.clawmate.app.chat.model

/**
 * Chat chat image-attachment policy and its in-memory / UI projection.
 *
 * This is the Android port of HarmonyOS
 * `entry/src/main/ets/pages/chat/ChatAttachmentPolicy.ets`, scoped to the
 * selection and capability boundary:
 *
 *  1. Selection   — a normalised list of user-tapped image `content://` URI
 *     *references* (or app-private copied-file names), never raw paths, tokens
 *     or other unauthorized data. The reference is opaque to logging: only a
 *     stable, index-based label is ever surfaced.
 *  2. The configured Android adapter prepares bounded, metadata-free pixels
 *     only after upload consent, then sends real image_url content parts through
 *     ordinary streaming chat. Tools/tasks never consume attachments.
 *  3. Adapters without that pipeline retain the explicit local-only fallback.
 *
 * Design constraints honoured from ChatModels.kt / Room schema: attachments are
 * pending selections stay in memory. Accepted images use bare private-file names
 * in Room; selected content URIs never enter model context, wire or app logs.
 */

/** Maximum number of images that may be attached to a single chat turn. */
const val CHAT_IMAGE_ATTACHMENT_MAX_COUNT: Int = 9

/** Default prompt used when a user attaches image(s) but types no text. */
const val CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT: String = "请分析这张图片。"

/**
 * Opaque reference to a picked image. Intentionally limits what is exposed so
 * raw filesystem paths or granted tokens cannot leak into logs / model context.
 */
sealed interface AttachmentReference {
    /**
     * An authorized `content://` URI handed out by the system photo picker,
     * carried only as its string form for display/adoption. This is the granted
     * reference (not a discoverable path); treat it as untrusted user input.
     *
     * The log-safe label is deliberately neutral — it carries no URI substring,
     * so a token or path fragment can never leak into logs.
     */
    data class ContentUri(val uri: String) : AttachmentReference {
        override val logSafeLabel: String get() = "[content-uri]"
    }

    /**
     * An app-private copied file reference (a bare file name inside the app's
     * own cache/files dir). Kept for the local-copy path; no absolute path.
     */
    data class AppCopyFile(val fileName: String) : AttachmentReference {
        override val logSafeLabel: String get() = "[app-copy]"
    }

    /** A neutral, log-safe description — never the raw URI or token. */
    val logSafeLabel: String
        get() = "[image-attachment]"
}

/** Source of a picked image, mirroring the Harmony *拍照/相册* split. */
enum class AttachmentSource(val label: String) {
    CAMERA("拍照"),
    ALBUM("相册"),
}

/** State of a pending (selected, not yet sent) attachment. */
enum class PendingAttachmentState {
    /** Selected by the user and held locally for a `发送` that has not run yet. */
    SELECTED,
    /** The send was attempted but no upload API exists; kept local. */
    UPLOAD_UNSUPPORTED,
}

/**
 * One image the user has selected in the message composer. [state] is the
 * per-attachment send outcome; [reference] is the opaque, authorized reference.
 */
data class PendingChatAttachment(
    val id: String,
    val reference: AttachmentReference,
    val source: AttachmentSource,
    val displayName: String,
    val state: PendingAttachmentState = PendingAttachmentState.SELECTED,
)

/** Whether this attachment is still waiting to be (or unable to be) sent. */
val PendingChatAttachment.isLocalOnly: Boolean
    get() = state == PendingAttachmentState.UPLOAD_UNSUPPORTED

/**
 * Whether an adapter has a real multimodal send pipeline. The production Android
 * path additionally requires configuration, bounded image preparation and consent.
 */
enum class AttachmentUploadCapability {
    /** No real upload / multimodal attachment path exists — image sends must stay local. */
    NONE,

    /**
     * A real multimodal path exists. A separate confirmation must precede I/O.
     */
    SUPPORTED,
}

/** Honest outcome of a composer "send" once attachments are considered. */
sealed interface AttachmentSendPlan {
    /** Plain text with no images — uses the existing text send path. */
    data class TextOnly(val text: String) : AttachmentSendPlan

    /**
     * Images are pending but there is no upload API ([AttachmentUploadCapability.NONE]).
     * The send is intentionally NOT performed over the network; the attachments
     * stay local. [text] is the user-typed text (may be empty).
     */
    data class LocalOnlyUnsupported(
        val attachments: List<PendingChatAttachment>,
        val text: String,
    ) : AttachmentSendPlan

    /**
     * A real upload path exists and the images can be attached to the request.
     * References still need explicit consent and preparation before transmission.
     */
    data class AttachImages(
        val attachments: List<PendingChatAttachment>,
        val text: String,
    ) : AttachmentSendPlan
}

/** Normalises and de-duplicates a list of raw URI strings (port of `normalizeChatImageAttachments`). */
fun normalizeChatImageAttachmentUris(uris: List<String>): List<String> {
    val normalized = mutableListOf<String>()
    for (raw in uris) {
        if (normalized.size >= CHAT_IMAGE_ATTACHMENT_MAX_COUNT) break
        val uri = raw.trim()
        if (uri.isNotEmpty() && normalized.indexOf(uri) < 0) {
            normalized.add(uri)
        }
    }
    return normalized
}

/** True when at least one attachment is present. */
fun hasChatImageAttachments(attachments: List<PendingChatAttachment>): Boolean =
    attachments.isNotEmpty()

/** True when the composer may send (non-blank text OR images attached). */
fun chatCanSendMessageWithAttachments(
    input: String,
    attachments: List<PendingChatAttachment>,
): Boolean = input.trim().isNotEmpty() || hasChatImageAttachments(attachments)

/**
 * The text that is actually sent for a turn. When the user typed text that text
 * wins; when only images are present the image-only prompt is used (mirroring
 * `CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT` in the Harmony policy).
 */
fun chatMessageTextForSendWithAttachments(
    input: String,
    attachments: List<PendingChatAttachment>,
): String {
    val text = input.trim()
    if (text.isNotEmpty()) return text
    return if (hasChatImageAttachments(attachments)) CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT else ""
}

/**
 * Core send decision: distinguishes a plain-text send from an image send and,
 * for the latter, reports the honest capability outcome. This is where the app
 * refuses to fake a network success for attachments it cannot upload.
 *
 * @param capability the upload capability offered by this adapter.
 */
fun chatAttachmentSendPlan(
    text: String,
    attachments: List<PendingChatAttachment>,
    capability: AttachmentUploadCapability,
): AttachmentSendPlan {
    if (!hasChatImageAttachments(attachments)) {
        return AttachmentSendPlan.TextOnly(text)
    }
    return when (capability) {
        AttachmentUploadCapability.SUPPORTED ->
            AttachmentSendPlan.AttachImages(attachments = attachments, text = text)
        AttachmentUploadCapability.NONE ->
            AttachmentSendPlan.LocalOnlyUnsupported(
                attachments = attachments.map { it.copy(state = PendingAttachmentState.UPLOAD_UNSUPPORTED) },
                text = text,
            )
    }
}

/** Maps a picker outcome (already-normalised URIs) into pending attachment models. */
fun toPendingAttachments(
    ids: List<String>,
    uris: List<String>,
    source: AttachmentSource,
): List<PendingChatAttachment> =
    normalizeChatImageAttachmentUris(uris).mapIndexed { index, uri ->
        PendingChatAttachment(
            id = ids.getOrElse(index) { "attachment-$index" },
            reference = AttachmentReference.ContentUri(uri),
            source = source,
            displayName = "${source.label}-${index + 1}",
        )
    }
