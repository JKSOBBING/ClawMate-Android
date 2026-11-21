package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.clawmate.platform.mika.MikaPaceCycle
import com.clawmate.platform.mika.MikaPaceSettings
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.knowledge.GuideCatalog
import java.io.File

/**
 * Settings entry for the upstream operating pace (#574): one global gear, plus per-App overrides.
 *
 * It writes the same app-private `mika-pace.json` [MikaPaceSettings] reads at task start, so a
 * change here takes effect on the next phone task without touching ClawMate's own settings.
 * Upstream exposes the same two levels in `AppPaceScreen`; the per-App list here starts from the
 * bundled careful Apps (the same seed upstream presets to 「像人」).
 */
object MikaPaceUi {

    fun settings(activity: Activity) {
        val settings = open(activity)
        val gears = listOf(OperatingPace.FAST, OperatingPace.STANDARD, OperatingPace.HUMAN)
        val plan = runCatching { settings.load() }.getOrDefault(
            com.wangzan101.mika.core.cap.phone.AppPacePlan(OperatingPace.DEFAULT),
        )
        AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle("手机任务操作节奏")
            .setSingleChoiceItems(
                gears.map(::gearLabel).toTypedArray(),
                gears.indexOf(plan.global),
            ) { _, which ->
                runCatching { settings.setGlobal(gears[which]) }.onFailure { toast(activity, "无法保存操作节奏") }
            }
            .setNeutralButton("按应用设置…") { _, _ -> perApp(activity, settings) }
            .setPositiveButton("完成", null)
            .show()
    }

    private fun perApp(activity: Activity, settings: MikaPaceSettings) {
        val plan = runCatching { settings.load() }.getOrNull() ?: return
        val careful = runCatching { GuideCatalog.loadBundled().carefulApps() }.getOrDefault(emptyList())
        val packages = (plan.overrides.keys + careful).distinct().sorted()
        if (packages.isEmpty()) {
            AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
                .setTitle("按应用操作节奏")
                .setMessage("内置知识里没有标记为谨慎的 App，暂时没有可按应用设置的条目。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val entries = packages.map { pkg ->
            val current = plan.overrides[pkg]
            val state = if (current == null) "跟随全局（${gearShort(plan.global)}）" else gearShort(current)
            "${appLabel(activity, pkg)}\n$pkg · $state"
        }.toTypedArray()
        AlertDialog.Builder(activity, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle("按应用操作节奏 · 点选切换")
            .setItems(entries) { _, index ->
                val pkg = packages[index]
                val next = MikaPaceCycle.next(plan.overrides[pkg])
                runCatching { settings.setOverride(pkg, next) }
                    .onFailure { toast(activity, "无法保存该应用的操作节奏") }
                // Re-open after the current dialog has dismissed, so the tapped App shows its new
                // gear instead of a stale label.
                activity.window?.decorView?.post { perApp(activity, settings) }
                    ?: perApp(activity, settings)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun open(activity: Activity): MikaPaceSettings = MikaPaceSettings(
        File(activity.filesDir, "mika-pace.json"),
        seed = {
            runCatching {
                GuideCatalog.loadBundled().carefulApps().associateWith { OperatingPace.HUMAN }
            }.getOrDefault(emptyMap())
        },
    )

    private fun gearLabel(pace: OperatingPace): String = when (pace) {
        OperatingPace.FAST -> "快 · 动作之间不额外停顿"
        OperatingPace.STANDARD -> "标准 · 像正常人操作（默认）"
        OperatingPace.HUMAN -> "像人 · 更慢，限制谨慎的 App 更久"
    }

    private fun gearShort(pace: OperatingPace): String = when (pace) {
        OperatingPace.FAST -> "快"
        OperatingPace.STANDARD -> "标准"
        OperatingPace.HUMAN -> "像人"
    }

    private fun appLabel(activity: Activity, pkg: String): String = runCatching {
        val info = activity.packageManager.getApplicationInfo(pkg, 0)
        activity.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
