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
     */
    private fun loadEndpointConfig() {
        val props = Properties()
        runCatching {
            val input = resources.openRawResource(R.raw.local_config_properties)
            input.use { props.load(it) }
        }
        EndpointConfig.load(props)
    }

    companion object {
        private const val TAG = "ClawMateApp"
    }
}
