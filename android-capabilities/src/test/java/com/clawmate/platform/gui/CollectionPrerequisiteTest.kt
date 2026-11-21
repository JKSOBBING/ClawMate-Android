package com.clawmate.platform.gui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionPrerequisiteTest {
    @Test fun recognizesBlockingLoginAndAgreementScreens() {
        for (labels in listOf(
            listOf("温馨提示", "同意", "不同意"),
            listOf("个人信息保护指引", "同意并继续", "不同意"),
            listOf("手机号登录/注册", "验证登录"),
            listOf("手机号", "淘宝用户快速登录"),
            listOf("一键登录", "其他登录方式")
        )) assertNotNull(collectionPrerequisite(labels))
    }

    @Test fun allowsGuestNavigationWithOptionalSignIn() {
        assertNull(collectionPrerequisite(listOf("菜单", "首页", "更多", "登录", "我")))
        assertNull(collectionPrerequisite(listOf("隐私政策", "设置", "历史记录")))
    }
}
