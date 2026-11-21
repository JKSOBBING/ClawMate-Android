package com.clawmate.platform.mika

import com.wangzan101.mika.core.cap.phone.AppTempo
import com.wangzan101.mika.core.cap.phone.OperatingPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pace store mirrors upstream's FeatureFlags pair: one global gear, per-app overrides seeded
 * once from the bundled careful Apps, and a corrupt file must degrade to defaults, never throw.
 */
class MikaPaceSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file get() = File(temp.root, "mika-pace.json")

    @Test
    fun `missing storage starts at the upstream default and seeds careful apps`() {
        val settings = MikaPaceSettings(file) { mapOf("com.example.shop" to OperatingPace.HUMAN) }
        val plan = settings.load()

        assertEquals(OperatingPace.DEFAULT, plan.global)
        assertEquals(OperatingPace.STANDARD, plan.global)
        assertEquals(OperatingPace.HUMAN, plan.of("com.example.shop"))
        assertEquals(AppTempo.CAREFUL, plan.of("com.example.shop").tempo)
        assertTrue(plan.capped("com.example.shop"))
        assertTrue("seed is persisted once", file.isFile)

        // A second read does not re-seed: an override removed by the user stays removed.
        settings.setOverride("com.example.shop", null)
        val reloaded = MikaPaceSettings(file) { mapOf("com.example.shop" to OperatingPace.HUMAN) }.load()
        assertEquals(OperatingPace.STANDARD, reloaded.of("com.example.shop"))
    }

    @Test
    fun `the global gear survives a restart`() {
        MikaPaceSettings(file).apply { setGlobal(OperatingPace.HUMAN) }
        assertEquals(OperatingPace.HUMAN, MikaPaceSettings(file).global())
    }

    @Test
    fun `a per-app override beats the global gear and can be removed`() {
        val settings = MikaPaceSettings(file)
        settings.setGlobal(OperatingPace.STANDARD)
        settings.setOverride("com.example.app", OperatingPace.FAST)

        val configured = MikaPaceSettings(file).load()
        assertEquals(OperatingPace.FAST, configured.of("com.example.app"))
        assertEquals(AppTempo.FAST, configured.of("com.example.app").tempo)
        assertFalse("only an explicit HUMAN override counts as capped", configured.capped("com.example.app"))
        assertEquals(OperatingPace.STANDARD, configured.of("com.example.other"))

        settings.setOverride("com.example.app", null)
        assertEquals(OperatingPace.STANDARD, MikaPaceSettings(file).load().of("com.example.app"))
    }

    @Test
    fun `an empty package name is ignored`() {
        val settings = MikaPaceSettings(file)
        settings.setOverride("   ", OperatingPace.HUMAN)
        assertTrue(settings.overrides().isEmpty())
    }

    @Test
    fun `corrupt storage degrades to defaults instead of throwing`() {
        file.writeText("{ not json")
        val plan = MikaPaceSettings(file).load()
        assertEquals(OperatingPace.DEFAULT, plan.global)
        assertTrue(plan.overrides.isEmpty())
    }

    @Test
    fun `unknown gear names fall back to the default`() {
        file.writeText("""{"global":"TURBO","overrides":{"com.example.app":"bogus","com.example.slow":"human"}}""")
        val plan = MikaPaceSettings(file).load()
        assertEquals(OperatingPace.STANDARD, plan.global)
        assertEquals(OperatingPace.STANDARD, plan.of("com.example.app"))
        assertEquals(OperatingPace.HUMAN, plan.of("com.example.slow"))
    }

    @Test
    fun `all-fast plans run without pacing`() {
        val plan = MikaPaceSettings(file).apply { setGlobal(OperatingPace.FAST) }.load()
        assertTrue(plan.allFast)
    }

    @Test
    fun `the task budget follows the gear so pacing cannot fake a timeout`() {
        assertEquals(115_000L, com.clawmate.platform.gui.MikaPhoneRun.budgetFor(OperatingPace.FAST))
        assertEquals(150_000L, com.clawmate.platform.gui.MikaPhoneRun.budgetFor(OperatingPace.STANDARD))
        assertEquals(200_000L, com.clawmate.platform.gui.MikaPhoneRun.budgetFor(OperatingPace.HUMAN))
        // 默认档位就是 STANDARD，所以默认预算与默认档位一致。
        assertEquals(
            com.clawmate.platform.gui.MikaPhoneRun.budgetFor(OperatingPace.DEFAULT),
            com.clawmate.platform.gui.MikaPhoneRun.BUDGET_STANDARD_MS,
        )
    }

    @Test
    fun `the settings dialog cycles every gear then returns to following the global one`() {
        assertEquals(OperatingPace.FAST, MikaPaceCycle.next(null))
        assertEquals(OperatingPace.STANDARD, MikaPaceCycle.next(OperatingPace.FAST))
        assertEquals(OperatingPace.HUMAN, MikaPaceCycle.next(OperatingPace.STANDARD))
        assertNull(MikaPaceCycle.next(OperatingPace.HUMAN))
    }

    @Test
    fun `cycling the dialog writes the same value a task start reads`() {
        val settings = MikaPaceSettings(file)
        settings.setOverride("com.example.app", MikaPaceCycle.next(settings.overrides()["com.example.app"]))
        assertEquals(OperatingPace.FAST, MikaPaceSettings(file).overrides()["com.example.app"])
        settings.setOverride("com.example.app", MikaPaceCycle.next(MikaPaceSettings(file).overrides()["com.example.app"]))
        assertEquals(OperatingPace.STANDARD, MikaPaceSettings(file).overrides()["com.example.app"])
    }
}
