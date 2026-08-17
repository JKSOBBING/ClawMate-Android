package com.clawmate.app.settings.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.overlay.AndroidOverlayServiceController
import com.clawmate.app.overlay.OverlayServiceController
import com.clawmate.app.settings.data.SettingsRepository

/** Simple factory for [SettingsViewModel]. */
class SettingsViewModelFactory(
    private val repository: SettingsRepository,
    private val versionName: String,
    private val versionCode: Long,
    /**
     * Phase 26 — an optional [Context] used to wire the real Android overlay
     * service controller so the explicit start/stop action can actually start
     * [com.clawmate.app.overlay.OverlayFloatService]. Absent in device-free
     * construction (the ViewModel falls back to a no-op controller).
     */
    private val overlayServiceController: OverlayServiceController? = null,
    /**
     * Phase 36 — an app-private files directory used by the explicit periodic
     * local-consolidation scheduling host and the explicit model enrich seams.
     * Absent in device-free construction where the seams report honest DISABLED /
     * NOT_CONFIGURED without touching disk or the network.
     */
    private val filesDir: java.io.File? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            val schedulingHost = filesDir?.let {
                com.clawmate.app.datamanager.PeriodicSchedulingHost(it)
            }
            return SettingsViewModel(
                repository,
                versionName,
                versionCode,
                overlayServiceController = overlayServiceController
                    ?: object : OverlayServiceController {
                        override fun startService() {}
                        override fun stopService() {}
                    },
                schedulingHost = schedulingHost,
                batchEnricher = filesDir?.let { com.clawmate.app.datamanager.BatchEventEnricher(it) },
                entityEnrichmentRunner = filesDir?.let { com.clawmate.app.datamanager.EntityEnrichmentRunner(it) },
                entityPersistence = filesDir?.let { com.clawmate.app.datamanager.EntityEnrichmentPersistence(it) },
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    /** Builds a factory wired to the Android overlay service controller for [context]. */
    companion object {
        @JvmStatic
        fun forContext(
            repository: SettingsRepository,
            versionName: String,
            versionCode: Long,
            context: Context,
        ): SettingsViewModelFactory = SettingsViewModelFactory(
            repository = repository,
            versionName = versionName,
            versionCode = versionCode,
            overlayServiceController = AndroidOverlayServiceController(context),
            filesDir = context.filesDir,
        )
    }
}
