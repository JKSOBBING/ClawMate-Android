package com.clawmate.app

import android.app.Application
import android.util.Log
import com.clawmate.app.config.EndpointConfig
import java.util.Properties

/**
 * Application entry point. Owns the long-lived containers (database, network,
 * preferences) that survive configuration changes for the app lifetime.
 *
 * HarmonyOS source reference (read-only): /root/mobiinfra-oh
 * - entry/src/main/ets/entryability/EntryAbility.ets  (app shell / lifecycle)
 */
class ClawMateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        loadEndpointConfig()
        Log.i(TAG, "ClawMateApplication initialized")
    }

    /**
     * Load environment-specific endpoints/keys from the git-ignored
     * `raw/local_config.properties` when present. Safe empty defaults
     * otherwise. See config/EndpointConfig.kt.
     *
     * `resources.getIdentifier(...)` is used (rather than a compile-time
     * `R.raw.local_config_properties`) so the app builds even when the
     * git-ignored override file is absent.
     */
    private fun loadEndpointConfig() {
        val props = Properties()
        val id = resources.getIdentifier("local_config_properties", "raw", packageName)
        if (id != 0) {
            runCatching {
                val input = resources.openRawResource(id)
                input.use { props.load(it) }
            }
        }
        EndpointConfig.load(props)
    }

    companion object {
        private const val TAG = "ClawMateApp"
    }
}
