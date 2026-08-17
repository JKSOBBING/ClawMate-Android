package com.clawmate.app.profile.share.nfc.device

import android.content.Context
import com.clawmate.app.profile.share.nfc.ProfileKnockDedupeStore

/**
 * SharedPreferences-backed [ProfileKnockDedupeStore].
 *
 * Only bounded message signatures are persisted (never profile content, never
 * secrets). Survives process/lifecycle rebuilds so a repeated tag/message is not
 * re-imported after a rotation or restart — that would otherwise violate the
 * "no duplicate import across lifecycle rebuild" rule of the NFC share boundary.
 */
class PrefsProfileKnockDedupeStore(context: Context) : ProfileKnockDedupeStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isConsumed(signature: String): Boolean = prefs.getBoolean(signature, false)

    override fun markConsumed(signature: String) {
        // Trim to a bounded set first so the store cannot grow unboundedly.
        trimToCap()
        prefs.edit().putBoolean(signature, true).apply()
    }

    private fun trimToCap() {
        val all = prefs.all.filterKeys { it != KEY_VERSION }.keys
        if (all.size < MAX_ENTRIES) return
        val sorted = all.sorted()
        val toDrop = sorted.size - (MAX_ENTRIES / 2)
        val editor = prefs.edit()
        sorted.take(toDrop).forEach { editor.remove(it) }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "clawmate_nfc_profile_share"
        const val KEY_VERSION = "version"
        const val MAX_ENTRIES: Int = 100
    }
}
