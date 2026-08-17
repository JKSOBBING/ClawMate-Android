package com.clawmate.app.profile.share.nfc

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.share.PROFILE_SHARE_APP_ID
import com.clawmate.app.profile.share.PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH
import com.clawmate.app.profile.share.PROFILE_SHARE_MIME_TYPE
import com.clawmate.app.profile.share.PROFILE_SHARE_SCHEMA_VERSION
import com.clawmate.app.profile.share.PROFILE_SHARE_UTD

/**
 * Typed, testable vocabulary for the Android NFC profile-knock-share adapter
 * ("一碰分享" — Phase 31).
 *
 * This module is a real Android NDEF adaptation of the HarmonyOS
 * `ProfileKnockShareService.ets` (ShareKit knock-share). It shares the same
 * bounded `.mobi-profile` envelope model and the same validation constraints as
 * the existing portable import pipeline ([com.clawmate.app.profile.share.ProfileShareImporter]).
 *
 * The pure-JVM parts ([ProfileKnockEnvelopeCodec], [ProfileKnockNfcStateMachine])
 * carry no Android runtime dependency so they are fully unit-testable offline;
 * the device-facing boundary ([NfcProfileShareController]) and the companion
 * [com.clawmate.app.profile.share.nfc.NfcProfileShareViewModel] wire that logic
 * to the real [android.nfc.NfcAdapter].
 */

/**
 * The status surface exposed by the NFC profile-share boundary. Distinct from
 * the internal [ProfileKnockNfcStateMachine.Op] which tracks whether a session
 * is listening to send or receive.
 *
 * @property NOT_SUPPORTED the device has no NFC hardware (or it is unreachable).
 * @property DISABLED NFC hardware is present but turned off in system settings.
 * @property READY NFC is available and idle; a send or receive may be started.
 * @property SENDING an explicit send session is armed, waiting for a peer tag.
 * @property RECEIVING an explicit receive session is armed (reader mode), waiting for a peer tag.
 * @property SUCCESS the last explicit session completed (a tag was written / a
 *   valid profile was received and imported).
 */
enum class KnockNfcStatus {
    NOT_SUPPORTED, DISABLED, READY, SENDING, RECEIVING, SUCCESS, CANCELLED, ERROR
}

/** Stable, enumerable reason for an [KnockNfcStatus.ERROR] — failures are never silent. */
enum class ProfileKnockNfcError {
    /** NDEF incoming tag had no parseable MIME/type (rejected before decode). */
    INVALID_NDEF_TYPE,
    /** Incoming NDEF payload exceeded the 512 KiB profile cap. */
    OVERSIZED,
    /** Incoming payload was not valid UTF-8 / not valid JSON (or not an object). */
    MALFORMED,
    /** Incoming envelope declared an unsupported schema version. */
    UNSUPPORTED_VERSION,
    /** Incoming envelope was missing the required profile body / identity fields. */
    MISSING_FIELD,
    /** Incoming envelope was not recognisable as a ClawMate profile. */
    UNRECOGNIZED,
    /** A send/write to the NFC tag failed at the device layer. */
    NDEF_WRITE_FAILED,
    /** An unexpected device-layer failure occurred (e.g. NFC stack fault). */
    DEVICE,
    /** The operation was started while the state machine could not accept it. */
    INVALID_OPERATION,
}

/**
 * Outcome of processing an incoming NDEF payload. A [Received] carries the raw
 * decoded text and a [com.clawmate.app.profile.avatar.domain.LocalAvatarProfile]
 * only when the payload passed every check *and* a non-empty merge actually
 * took place through the existing importer pipeline. [Duplicate] means the exact
 * same message was already imported in this (or an earlier) session and was
 * deliberately not re-imported. [Rejected] always carries a typed [ProfileKnockNfcError].
 */
sealed interface ProfileKnockReceivedOutcome {
    data class Received(
        val importedDomains: List<String>,
        val completeness: Int,
        val localProfile: LocalAvatarProfile,
    ) : ProfileKnockReceivedOutcome

    data object Duplicate : ProfileKnockReceivedOutcome
    data class Rejected(
        val reason: ProfileKnockNfcError,
        val message: String,
    ) : ProfileKnockReceivedOutcome
}

/**
 * Boundary the [NfcProfileShareViewModel] uses to reach the real NFC hardware.
 * Pure tests inject a fake implementation to exercise NOT_SUPPORTED / DISABLED /
 * device-error / cancellation paths without any device.
 */
interface NfcProfileShareGateway {    /** Cheap availability probe. Never assumes a device is present. */
    fun probeAvailability(): KnockNfcStatus

    /**
     * Arm NDEF receiving (foreground dispatch / reader mode) so subsequent tags
     * are delivered to [onMessage] with the declared MIME/type (may be null) and
     * the raw NDEF payload bytes. Returns true when the session armed.
     */
    fun startReceive(onMessage: (String?, ByteArray) -> Unit): Boolean

    /** Disarm receiving and release NFC resources. Must be idempotent. */
    fun stopReceive()

    /**
     * Write [payload] to the tag surfaced by [tagToken]. The token is opaque to
     * the pure logic so tests need no [android.nfc.Tag] instance.
     *
     * @return typed error message on failure, or null on success.
     */
    fun writeTag(tagToken: Any, payload: ByteArray): String?
}

/**
 * Persisted record of already-imported message signatures so a repeated tag /
 * message is not re-imported, including across a lifecycle rebuild or process
 * restart (the pure in-memory session cache alone would be lost on restart).
 * Implementations must be app-private and never store profile content beyond the
 * bounded signature string.
 */
interface ProfileKnockDedupeStore {
    /** True when [signature] has already been imported and consumed. */
    fun isConsumed(signature: String): Boolean

    /** Record [signature] as imported so future duplicates are skipped. */
    fun markConsumed(signature: String)
}
