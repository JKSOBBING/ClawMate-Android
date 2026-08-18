package com.clawmate.app.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 45 — pure JVM tests for the chat image-attachment policy and its
 * in-memory / UI projection (ChatAttachmentPolicy.kt). These cover the exact
 * scenarios the migration cadence asks for, all as deterministic pure-Kotlin
 * units (no adb/hdc, no ProcessBuilder, no network):
 *
 *   - URI selection normalisation (dedupe / trim / max 9) and the
 *     picker-less / permission-degraded projection (toPendingAttachments on
 *     cancelled/empty results is a no-op, and it never logs the raw URI).
 *   - Plain-text vs image-attachment message serialization / projection
 *     (text-wins, image-only prompt fallback), matching the Harmony policy.
 *   - "Empty" attachment handling: blank/empty inputs never form attachments
 *     and never satisfy `canSend`.
 *   - Failure / unsupported state: when no upload API exists a send with
 *     images yields [AttachmentSendPlan.LocalOnlyUnsupported] and each
 *     attachment flips to [PendingAttachmentState.UPLOAD_UNSUPPORTED] rather
 *     than faking a network success.
 *   - Forward-compatible capability branch: a SUPPORTED capability yields
 *     [AttachmentSendPlan.AttachImages] (the honest seam for a future real
 *     upload API).
 *   - Session isolation: attachment state is scoped to a composer projection,
 *     and log-safe labels never expose the raw content URI.
 */

class ChatAttachmentPolicyTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun attachment(
        id: String = "a1",
        uri: String = "content://com.android.providers.media.documents/document/image%3A42",
    ) = PendingChatAttachment(
        id = id,
        reference = AttachmentReference.ContentUri(uri),
        source = AttachmentSource.ALBUM,
        displayName = "相册-1",
    )

    private fun attachments(uris: List<String>): List<PendingChatAttachment> =
        toPendingAttachments(
            ids = uris.indices.map { "id-$it" },
            uris = uris,
            source = AttachmentSource.ALBUM,
        )

    // ------------------------------------------------------------------
    // 1. URI selection / normalisation
    // ------------------------------------------------------------------

    @Test
    fun `normalize trims dedupes and caps at max count`() {
        val input = List(12) { "  content://uri/$it  " }
        val normalized = normalizeChatImageAttachmentUris(input)
        assertEquals(CHAT_IMAGE_ATTACHMENT_MAX_COUNT, normalized.size)
        // Duplicate entries collapse.
        assertEquals(
            1,
            normalizeChatImageAttachmentUris(listOf("content://x", "content://x", " content://x ")).size,
        )
        // Blank entries are dropped.
        assertEquals(0, normalizeChatImageAttachmentUris(listOf("", "  ", "\t")).size)
    }

    @Test
    fun `picked uris project into pending attachments with opaque references`() {
        val picked = listOf(
            "content://media/external/images/media/100",
            "content://media/external/images/media/101",
        )
        val pending = attachments(picked)
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.reference is AttachmentReference.ContentUri })
        // Display names are source labels only; the raw URI is never exposed by logSafeLabel.
        assertTrue(pending.all { it.displayName.startsWith("相册-") })
        assertEquals("相册-1", pending[0].displayName)
        assertTrue(pending[0].id.startsWith("id-"))
    }

    // ------------------------------------------------------------------
    // 2. Cancelled / empty picker -> no-op projection (and never logged)
    // ------------------------------------------------------------------

    @Test
    fun `cancelled or empty picker yields no attachments and cannot send`() {
        // A cancelled/denied/back-press picker returns an empty URI list.
        val pending = toPendingAttachments(ids = emptyList(), uris = emptyList(), source = AttachmentSource.ALBUM)
        assertTrue(pending.isEmpty())
        assertFalse(hasChatImageAttachments(pending))
        assertFalse(chatCanSendMessageWithAttachments("", pending))
        assertEquals("", chatMessageTextForSendWithAttachments("", pending))
        // Send plan with no attachments is always text-only.
        assertEquals(
            AttachmentSendPlan.TextOnly("hello"),
            chatAttachmentSendPlan("hello", pending, AttachmentUploadCapability.NONE),
        )
    }

    // ------------------------------------------------------------------
    // 3. Plain-text vs image-attachment serialization / projection
    // ------------------------------------------------------------------

    @Test
    fun `typed text wins when both text and images are present`() {
        val pending = attachments(listOf("content://image/1"))
        assertEquals("你好，帮我看看", chatMessageTextForSendWithAttachments("  你好，帮我看看  ", pending))
    }

    @Test
    fun `image-only send falls back to the image prompt`() {
        val pending = attachments(listOf("content://image/9"))
        assertEquals(CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT, chatMessageTextForSendWithAttachments("", pending))
        assertEquals(CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT, chatMessageTextForSendWithAttachments("   ", pending))
    }

    @Test
    fun `canSend honors text or images`() {
        assertTrue(chatCanSendMessageWithAttachments("hi", emptyList()))
        assertTrue(chatCanSendMessageWithAttachments("", attachments(listOf("content://i/1"))))
        assertFalse(chatCanSendMessageWithAttachments("", emptyList()))
    }

    // ------------------------------------------------------------------
    // 4. Empty-attachment edge cases
    // ------------------------------------------------------------------

    @Test
    fun `blank uri strings are dropped before projection`() {
        val pending = attachments(listOf("  ", "", "content://ok"))
        assertEquals(1, pending.size)
    }

    @Test
    fun `deduped attachments do not grow with repeated picks of same uri`() {
        val one = attachments(listOf("content://dup/1"))
        val two = attachments(listOf("content://dup/1", "content://dup/1"))
        assertEquals(1, one.size)
        assertEquals(1, two.size)
    }

    // ------------------------------------------------------------------
    // 5. Failure / unsupported upload state (no fake network success)
    // ------------------------------------------------------------------

    @Test
    fun `image send with no upload api is localOnlyUnsupported and marks attachments`() {
        val pending = attachments(listOf("content://image/1"))
        val plan = chatAttachmentSendPlan("看图", pending, AttachmentUploadCapability.NONE)
        assertTrue(plan is AttachmentSendPlan.LocalOnlyUnsupported)
        val local = plan as AttachmentSendPlan.LocalOnlyUnsupported
        assertEquals("看图", local.text)
        assertTrue(local.attachments.all { it.state == PendingAttachmentState.UPLOAD_UNSUPPORTED })
        assertTrue(local.attachments.all { it.isLocalOnly })
    }

    @Test
    fun `a supported upload capability yields attachImages instead`() {
        val pending = attachments(listOf("content://image/2"))
        val plan = chatAttachmentSendPlan("", pending, AttachmentUploadCapability.SUPPORTED)
        assertTrue(plan is AttachmentSendPlan.AttachImages)
        // The attached image keeps its original selected state (no fake upload flag).
        assertTrue((plan as AttachmentSendPlan.AttachImages).attachments.all {
            it.state == PendingAttachmentState.SELECTED
        })
    }

    @Test
    fun `logSafeLabel never contains the raw uri`() {
        val pending = attachments(listOf("content://media/secret/token=abc123"))
        for (a in pending) {
            assertFalse(a.reference.logSafeLabel.contains("content://", ignoreCase = true))
            assertFalse(a.reference.logSafeLabel.contains("abc123"))
            assertFalse(a.reference.logSafeLabel.contains("token=abc123"))
        }
        // App-private copy references are likewise neutral (no filename either).
        val copy = PendingChatAttachment(
            id = "c",
            reference = AttachmentReference.AppCopyFile("imported_image_42.jpg"),
            source = AttachmentSource.ALBUM,
            displayName = "相册-1",
        )
        assertFalse(copy.reference.logSafeLabel.contains("imported_image_42"))
        assertFalse(copy.reference.logSafeLabel.contains(".jpg"))
    }

    // ------------------------------------------------------------------
    // 6. Session isolation (projection is scoped; separate turns stay separate)
    // ------------------------------------------------------------------

    @Test
    fun `distinct composer projections are independent`() {
        val sessionA = attachments(listOf("content://a/1", "content://a/2"))
        val sessionB = attachments(listOf("content://b/1"))
        assertEquals(2, sessionA.size)
        assertEquals(1, sessionB.size)
        // Recomputing a plan for A does not mutate B's projection.
        val planA = chatAttachmentSendPlan("", sessionA, AttachmentUploadCapability.NONE)
        assertTrue(planA is AttachmentSendPlan.LocalOnlyUnsupported)
        assertTrue(sessionB.none { it.state == PendingAttachmentState.UPLOAD_UNSUPPORTED })
    }

    @Test
    fun `isLocalOnly reflects the unsupported flag only`() {
        val fresh = attachment()
        val unsupported = fresh.copy(state = PendingAttachmentState.UPLOAD_UNSUPPORTED)
        assertFalse(fresh.isLocalOnly)
        assertTrue(unsupported.isLocalOnly)
    }

    // ------------------------------------------------------------------
    // 7. No-picker / permission degradation (pure projection)
    // ------------------------------------------------------------------

    @Test
    fun `no-picker or no-permission degrades to a cancelled no-op, not fabricated uris`() {
        // Simulate the picker reporting unavailable/cancelled with zero URIs: the
        // ViewModel maps that to no pending attachments and no network send.
        val cancelled = toPendingAttachments(ids = emptyList(), uris = emptyList(), source = AttachmentSource.ALBUM)
        assertTrue(cancelled.isEmpty())
        val busy = chatAttachmentSendPlan("", cancelled, AttachmentUploadCapability.NONE)
        assertTrue(busy is AttachmentSendPlan.TextOnly)

        // A permission-limited picker that returns fewer than requested is fine.
        val limited = attachments(listOf("content://only-one"))
        assertEquals(1, limited.size)
    }
}
