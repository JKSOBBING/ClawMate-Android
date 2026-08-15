package com.clawmate.app.profile

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.clawmate.app.R

/**
 * Profile share entry point — receives `ACTION_VIEW` / `ACTION_SEND` intents
 * for `.mobi-profile` / `application/vnd.mobiinfra.profile+json` summary files
 * (the "profile knock share" flow). On modern Android the URI is a
 * `content://` URI; it is read through [ContentResolver].
 *
 * HarmonyOS source: ShareKit knock-share + file-open wants handled in
 *   entry/src/main/ets/utils/ProfileKnockShareService.ets
 *   entry/src/main/module.json5 (utd / uri skill)
 *
 * Phase 1 only verifies the intent is receivable (a `content://` URI is
 * present) and shows an acknowledgement. The import/merge pipeline is built in
 * a later phase.
 */
class ProfileShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = intent?.data
        if (uri == null) {
            // Some senders deliver the body as an EXTRA_STREAM clip instead of
            // the Intent data URI; both are handled in a later phase.
            Log.w(TAG, "Profile share intent received without a data URI")
        } else {
            Log.i(TAG, "Profile share intent URI: $uri (size=${querySize(uri)})")
        }

        Toast.makeText(this, R.string.profile_share_message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun querySize(uri: Uri): Long {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0).takeIf { it >= 0 } ?: -1L
                } else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    companion object {
        private const val TAG = "ProfileShare"
    }
}
