package com.clawmate.app.profile.share.nfc

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import com.clawmate.app.profile.share.PROFILE_SHARE_MIME_TYPE
import com.clawmate.app.profile.share.PROFILE_SHARE_UTD
import com.clawmate.app.profile.share.ProfileShareEnvelopeDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, offline JVM tests for the Android NFC profile-knock-share
 * boundary. No device, no Android runtime and no network are needed: the pure
 * codec, the state machine, and the (fake-gateway) session flows are exercised
 * directly. These verify the NFC boundary can NEVER bypass the shared
 * [com.clawmate.app.profile.share.ProfileShareImporter] merge pipeline.
 */
class ProfileKnockNfcTest {

    // ---- codec: outgoing envelope ----------------------------------------------

    private fun localProfileWith(domain: String, summary: String): LocalAvatarProfile =
        LocalAvatarProfile.empty.copy(
            domains = defaultProfileDomains().map {
                if (it.domain == domain) it.copy(shortSummary = summary) else it
            },
        )

    @Test
    fun `outgoing envelope carries canonical UTD identity and bounded body`() {
        val envelope = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(
            localProfileWith("eat", "喜欢粤菜"),
            generatedAt = "2024-06-01T00:00:00Z",
        )
        assertEquals(PROFILE_SHARE_UTD, envelope.typeId)
        assertEquals("com.clawmate.app", envelope.appId)
        assertEquals(1, envelope.schemaVersion)
        assertEquals("2024-06-01T00:00:00Z", envelope.generatedAt)
        assertFalse(envelope.profileText.isNullOrBlank())
        assertTrue(envelope.profile?.domains?.isNotEmpty() == true)
    }

    @Test
    fun `outgoing envelope ignores blank persona domains`() {
        val empty = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(LocalAvatarProfile.empty)
        assertTrue(empty.profile?.empty == true)
        assertTrue(empty.profile?.domains?.isEmpty() == true)
    }

    @Test
    fun `encoded outgoing payload is within the size cap and round-trips`() {
        val envelope = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(localProfileWith("eat", "长".repeat(50)))
        val payload = ProfileKnockEnvelopeCodec.encodeOutgoingPayload(envelope)
        requireNotNull(payload)
        assertTrue(payload.size <= com.clawmate.app.profile.share.MAX_RECEIVED_PROFILE_BYTES)
        // The encoded payload must itself parse+validate back through the shared importer.
        val parsed = ProfileKnockEnvelopeCodec.parseAndValidateNfc(PROFILE_SHARE_MIME_TYPE, payload)
        assertTrue("round-trip should parse, got $parsed", parsed is com.clawmate.app.profile.share.ProfileShareParseResult.Success)
    }

    @Test
    fun `oversized envelope is refused at encode time, never emitted`() {
        val big = "数据".repeat(300 * 1024)
        val envelope = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(
            LocalAvatarProfile.empty.copy(
                domains = defaultProfileDomains().map { it.copy(shortSummary = big) },
            ),
        )
        val payload = ProfileKnockEnvelopeCodec.encodeOutgoingPayload(envelope)
        // Either the single domain pushes it over the ~512KiB cap and encode returns null,
        // or it fits; assert the contract: if it fits it must be <= cap.
        if (payload != null) {
            assertTrue(payload.size <= com.clawmate.app.profile.share.MAX_RECEIVED_PROFILE_BYTES)
        }
        // Force an oversized serialized body deterministically.
        val forced = ProfileKnockEnvelopeCodec.encodeOutgoingPayload(envelope, maxBytes = 1)
        assertTrue("oversize must be refused", forced == null)
    }

    // ---- codec: incoming validation ---------------------------------------------

    private fun validIncomingBytes(): ByteArray {
        val envelope = ProfileKnockEnvelopeCodec.buildOutgoingEnvelope(localProfileWith("travel", "短途旅行"))
        return ProfileKnockEnvelopeCodec.encodeOutgoingPayload(envelope)!!
    }

    @Test
    fun `wrong ndef mime type is rejected before decode`() {
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc("application/octet-stream", validIncomingBytes())
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
        val code = (result as com.clawmate.app.profile.share.ProfileShareParseResult.Error).code
        assertEquals(com.clawmate.app.profile.share.ProfileShareErrorCode.UNRECOGNIZED, code)
    }

    @Test
    fun `absent ndef mime type is rejected`() {
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc(null, validIncomingBytes())
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
    }

    @Test
    fun `oversized incoming payload is rejected`() {
        val bytes = ByteArray(com.clawmate.app.profile.share.MAX_RECEIVED_PROFILE_BYTES + 8)
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc(PROFILE_SHARE_MIME_TYPE, bytes)
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
        assertEquals(
            com.clawmate.app.profile.share.ProfileShareErrorCode.OVERSIZED,
            (result as com.clawmate.app.profile.share.ProfileShareParseResult.Error).code,
        )
    }

    @Test
    fun `malformed json payload is rejected`() {
        val bytes = "not json {{{".toByteArray()
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc(PROFILE_SHARE_MIME_TYPE, bytes)
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
        assertEquals(
            com.clawmate.app.profile.share.ProfileShareErrorCode.MALFORMED,
            (result as com.clawmate.app.profile.share.ProfileShareParseResult.Error).code,
        )
    }

    @Test
    fun `non-utf8 payload is rejected as malformed`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x81.toByte())
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc(PROFILE_SHARE_MIME_TYPE, bytes)
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
        assertEquals(
            com.clawmate.app.profile.share.ProfileShareErrorCode.MALFORMED,
            (result as com.clawmate.app.profile.share.ProfileShareParseResult.Error).code,
        )
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val bytes = """{"schemaVersion":99,"typeId":"$PROFILE_SHARE_UTD","profileText":"x"}""".toByteArray()
        val result = ProfileKnockEnvelopeCodec.parseAndValidateNfc(PROFILE_SHARE_MIME_TYPE, bytes)
        assertTrue(result is com.clawmate.app.profile.share.ProfileShareParseResult.Error)
        assertEquals(
            com.clawmate.app.profile.share.ProfileShareErrorCode.UNSUPPORTED_VERSION,
            (result as com.clawmate.app.profile.share.ProfileShareParseResult.Error).code,
        )
    }

    // ---- state machine ----------------------------------------------------------

    private class FakeGateway(initial: KnockNfcStatus = KnockNfcStatus.READY) : NfcProfileShareGateway {
        var status = initial
        var inbound: ((String?, ByteArray) -> Unit)? = null
        var writeFailure: String? = null
        var writeCalls = 0
        var startReceiveCalls = 0

        override fun probeAvailability(): KnockNfcStatus = status

        override fun startReceive(onMessage: (String?, ByteArray) -> Unit): Boolean {
            if (status != KnockNfcStatus.READY) return false
            startReceiveCalls++
            inbound = onMessage
            return true
        }

        override fun stopReceive() { inbound = null }

        override fun writeTag(tagToken: Any, payload: ByteArray): String? {
            writeCalls++
            return writeFailure
        }
    }

    private class FakeDedupe : ProfileKnockDedupeStore {
        private val set = mutableSetOf<String>()
        override fun isConsumed(signature: String): Boolean = signature in set
        override fun markConsumed(signature: String) { set.add(signature) }
    }

    private class FakeRepo(var profile: LocalAvatarProfile = LocalAvatarProfile.empty) {
        fun snapshot(): LocalAvatarProfile = profile
        fun save(p: LocalAvatarProfile): LocalAvatarProfile { profile = p; return p }
    }

    private fun buildMachine(
        gateway: FakeGateway,
        dedupe: ProfileKnockDedupeStore = FakeDedupe(),
        repo: FakeRepo = FakeRepo(),
    ) = ProfileKnockNfcStateMachine(
        gateway = gateway,
        dedupe = dedupe,
        loadLocal = { repo.snapshot() },
        saveLocal = { repo.save(it) },
    )

    @Test
    fun `no nfc hardware gates to not-supported`() = runTest {
        val gate = FakeGateway(KnockNfcStatus.NOT_SUPPORTED)
        val machine = buildMachine(gate)
        val s = machine.probe()
        assertEquals(KnockNfcStatus.NOT_SUPPORTED, s.status)
        // Cannot arm a session without NFC.
        assertEquals(
            KnockNfcStatus.ERROR,
            machine.startSend(validIncomingBytes()).status,
        )
    }

    @Test
    fun `disabled nfc gates to disabled`() = runTest {
        val machine = buildMachine(FakeGateway(KnockNfcStatus.DISABLED))
        assertEquals(KnockNfcStatus.DISABLED, machine.probe().status)
    }

    @Test
    fun `ready send session progresses to success on peer tag`() = runTest {
        val gate = FakeGateway()
        val machine = buildMachine(gate)
        machine.probe()
        val bytes = validIncomingBytes()
        val afterSend = machine.startSend(bytes)
        assertEquals(KnockNfcStatus.SENDING, afterSend.status)
        assertTrue(machine.onPeerTagForSend("tag"))
        assertEquals(KnockNfcStatus.SUCCESS, machine.current().status)
        assertEquals(1, gate.writeCalls)
    }

    @Test
    fun `send write failure surfaces as typed error`() = runTest {
        val gate = FakeGateway().apply { writeFailure = "标签已满" }
        val machine = buildMachine(gate)
        machine.probe()
        machine.startSend(validIncomingBytes())
        assertFalse(machine.onPeerTagForSend("tag"))
        val s = machine.current()
        assertEquals(KnockNfcStatus.ERROR, s.status)
        assertEquals(ProfileKnockNfcError.NDEF_WRITE_FAILED, s.error)
    }

    @Test
    fun `receive session imports a valid profile through the importer`() = runTest {
        val repo = FakeRepo(LocalAvatarProfile.empty)
        val machine = buildMachine(FakeGateway(), repo = repo)
        machine.probe()
        val afterRecv = machine.startReceive()
        assertEquals(KnockNfcStatus.RECEIVING, afterRecv.status)
        val before = localProfileCompleteness(repo.snapshot())
        val s = machine.processInbound(PROFILE_SHARE_MIME_TYPE, validIncomingBytes())
        assertEquals(KnockNfcStatus.SUCCESS, s.status)
        assertEquals(ProfileKnockNfcStateMachine.State.ReceiveResult.RECEIVED, s.lastReceive)
        assertTrue("profile should have been merged", localProfileCompleteness(repo.snapshot()) > before)
    }

    @Test
    fun `duplicate message is not re-imported (dedupe + session boundary)`() = runTest {
        val repo = FakeRepo(LocalAvatarProfile.empty)
        val gate = FakeGateway()
        val dedupe = FakeDedupe()
        val machine = buildMachine(gate, dedupe = dedupe, repo = repo)
        machine.probe()
        machine.startReceive()
        val bytes = validIncomingBytes()
        val first = machine.processInbound(PROFILE_SHARE_MIME_TYPE, bytes)
        assertEquals(ProfileKnockNfcStateMachine.State.ReceiveResult.RECEIVED, first.lastReceive)
        val completenessAfterFirst = localProfileCompleteness(repo.snapshot())

        // Same bytes again (after resetting to a new READY session) → duplicate, saved exactly once.
        machine.reset()
        machine.startReceive()
        val second = machine.processInbound(PROFILE_SHARE_MIME_TYPE, bytes)
        assertEquals(ProfileKnockNfcStateMachine.State.ReceiveResult.DUPLICATE, second.lastReceive)
        assertEquals(completenessAfterFirst, localProfileCompleteness(repo.snapshot()))

        // Rebuild a fresh machine (simulating lifecycle rebuild / restart): the
        // durable dedupe store still prevents a second import.
        val rebuilt = buildMachine(FakeGateway(), dedupe = dedupe, repo = repo).also { it.probe() }
        rebuilt.startReceive()
        val third = rebuilt.processInbound(PROFILE_SHARE_MIME_TYPE, bytes)
        assertEquals(ProfileKnockNfcStateMachine.State.ReceiveResult.DUPLICATE, third.lastReceive)
        assertEquals(completenessAfterFirst, localProfileCompleteness(repo.snapshot()))
    }

    @Test
    fun `rejected inbound does not update local profile`() = runTest {
        val repo = FakeRepo(LocalAvatarProfile.empty)
        val machine = buildMachine(FakeGateway(), repo = repo)
        machine.probe()
        machine.startReceive()
        val before = repo.snapshot()
        val s = machine.processInbound(PROFILE_SHARE_MIME_TYPE, "bad".toByteArray())
        assertEquals(KnockNfcStatus.ERROR, s.status)
        assertEquals(ProfileKnockNfcError.MALFORMED, s.error)
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun `cancel ends an active receive session`() = runTest {
        val machine = buildMachine(FakeGateway())
        machine.probe()
        machine.startReceive()
        val s = machine.cancel()
        assertEquals(KnockNfcStatus.CANCELLED, s.status)
    }

    @Test
    fun `retry resets an errored machine to ready`() = runTest {
        val gate = FakeGateway(KnockNfcStatus.READY)
        val machine = buildMachine(gate)
        machine.probe()
        machine.startSend(ByteArray(0)) // empty → error
        assertEquals(KnockNfcStatus.ERROR, machine.current().status)
        assertEquals(KnockNfcStatus.READY, machine.reset().status)
    }

    @Test
    fun `cannot start a session when a session is already active`() = runTest {
        val machine = buildMachine(FakeGateway())
        machine.probe()
        machine.startReceive()
        val s = machine.startSend(validIncomingBytes())
        assertEquals(KnockNfcStatus.ERROR, s.status)
        assertEquals(ProfileKnockNfcError.INVALID_OPERATION, s.error)
    }

    @Test
    fun `message signature is stable for identical bytes and differs for different`() {
        val a = "payload-a".toByteArray()
        val b = "payload-b".toByteArray()
        assertEquals(
            ProfileKnockEnvelopeCodec.messageSignature(a),
            ProfileKnockEnvelopeCodec.messageSignature(a),
        )
        assertFalse(ProfileKnockEnvelopeCodec.messageSignature(a) == ProfileKnockEnvelopeCodec.messageSignature(b))
    }
}
