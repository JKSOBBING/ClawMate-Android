package com.clawmate.app.settings

import com.clawmate.app.settings.domain.SETTINGS_SECTIONS
import com.clawmate.app.settings.domain.SettingsSectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section navigation / selected state (mirrors SettingsCenterTypes.ets). */
class SettingsSectionTest {

    @Test
    fun `seven sections in the correct order`() {
        val ids = SETTINGS_SECTIONS.map { it.id }
        assertEquals(
            listOf(
                SettingsSectionId.PersonalPrivacy,
                SettingsSectionId.DataCollection,
                SettingsSectionId.ModelAgents,
                SettingsSectionId.TaskAutomation,
                SettingsSectionId.StorageAbout,
                SettingsSectionId.WechatAutoSummary,
                SettingsSectionId.RuntimeLogs,
            ),
            ids,
        )
    }

    @Test
    fun `normalize only accepts real section ids`() {
        assertEquals(0, SettingsSectionId.normalize(SettingsSectionId.PersonalPrivacy.ordinalIndex))
        assertEquals(4, SettingsSectionId.normalize(SettingsSectionId.StorageAbout.ordinalIndex))
        assertEquals(5, SettingsSectionId.normalize(SettingsSectionId.WechatAutoSummary.ordinalIndex))
        assertEquals(6, SettingsSectionId.normalize(SettingsSectionId.RuntimeLogs.ordinalIndex))
        assertEquals(SettingsSectionId.NONE, SettingsSectionId.normalize(999))
        assertEquals(SettingsSectionId.NONE, SettingsSectionId.normalize(-1))
    }

    @Test
    fun `isSelected reflects real sections only`() {
        assertTrue(SettingsSectionId.isSelected(2))
        assertTrue(SettingsSectionId.isSelected(5))
        assertTrue(SettingsSectionId.isSelected(6))
        assertFalse(SettingsSectionId.isSelected(-1))
        assertFalse(SettingsSectionId.isSelected(7))
    }

    @Test
    fun `section titles and subtitles resolve deterministically`() {
        val pp = SETTINGS_SECTIONS.first { it.id == SettingsSectionId.PersonalPrivacy }
        assertEquals("个人与隐私", pp.title)
        assertTrue(pp.subtitle.isNotEmpty())
        assertEquals(
            "个人与隐私",
            com.clawmate.app.settings.domain.settingsSectionTitle(SettingsSectionId.PersonalPrivacy),
        )
        assertEquals(
            "模型与智能体",
            com.clawmate.app.settings.domain.settingsSectionTitle(SettingsSectionId.ModelAgents),
        )
        assertTrue(com.clawmate.app.settings.domain.settingsSectionSubtitle(SettingsSectionId.StorageAbout).isNotEmpty())
        assertEquals("微信自动汇总", com.clawmate.app.settings.domain.settingsSectionTitle(SettingsSectionId.WechatAutoSummary))
        assertTrue(com.clawmate.app.settings.domain.settingsSectionSubtitle(SettingsSectionId.WechatAutoSummary).isNotEmpty())
        assertEquals("运行日志", com.clawmate.app.settings.domain.settingsSectionTitle(SettingsSectionId.RuntimeLogs))
        assertTrue(com.clawmate.app.settings.domain.settingsSectionSubtitle(SettingsSectionId.RuntimeLogs).isNotEmpty())
    }

    @Test
    fun `badges match the source initials`() {
        assertEquals("P", badge(SettingsSectionId.PersonalPrivacy))
        assertEquals("D", badge(SettingsSectionId.DataCollection))
        assertEquals("M", badge(SettingsSectionId.ModelAgents))
        assertEquals("T", badge(SettingsSectionId.TaskAutomation))
        assertEquals("S", badge(SettingsSectionId.StorageAbout))
        assertEquals("W", badge(SettingsSectionId.WechatAutoSummary))
        assertEquals("L", badge(SettingsSectionId.RuntimeLogs))
    }

    private fun badge(id: SettingsSectionId): String =
        SETTINGS_SECTIONS.first { it.id == id }.badge
}
