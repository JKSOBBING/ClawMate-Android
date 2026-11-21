package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import org.junit.Assert.*
import org.junit.Test

class AccountCollectionPageTest {
    private fun node(name: String, box: List<Int>, click: Boolean = false, role: String = "TextView") =
        UiElement(name, role, name, 0, 0, box, null, click, false, false)
    private fun screen(vararg fields: UiElement) = ScreenSnapshot("s", null, fields.toList(), 1080, 2200)
    private fun line(text: String, x: Int, y: Int) = VisibleTextLine(text, listOf(x, y, x + 80, y + 20))

    @Test fun builtInRoutesAndOlderTaskFilesResolveToTheSameAccountLists() {
        val x = AccountCollectionPage.XHS
        assertEquals(x, AccountCollectionPage.forRoute(x.pkg, listOf("我", "浏览记录")))
        assertEquals(x, AccountCollectionPage.forRoute(x.pkg, listOf("菜单", "浏览记录")))
        assertEquals(AccountCollectionPage.ELE, AccountCollectionPage.forRoute("me.ele", listOf("我的", "全部订单")))
        assertNull(AccountCollectionPage.forRoute(x.pkg, listOf("我", "收藏")))
    }

    @Test fun profileShortcutIsNotHistoryAndToolbarLabelsAreNotRecords() {
        val page = AccountCollectionPage.XHS
        assertFalse(page.onList(screen(node("浏览记录", listOf(200, 460, 500, 570))), emptyList()))
        val list = screen(node("浏览记录", listOf(400, 45, 600, 70)),
            node("笔记", listOf(130, 125, 220, 145)), node("直播", listOf(730, 125, 820, 145)),
            node("真实标题", listOf(10, 200, 490, 590), true, "ViewGroup"),
            node("作者", listOf(30, 525, 290, 555), true, "ViewGroup"))
        assertTrue(page.onList(list, emptyList()))
        assertEquals(listOf("真实标题"), page.records(list, emptyList()))
    }

    @Test fun recognizesBiliTitlesAndExplicitEmptyState() {
        val page = AccountCollectionPage.BILI
        val list = screen(node("历史记录", listOf(420, 50, 620, 77)), node("视频", listOf(200, 100, 345, 131)),
            node("全部", listOf(70, 100, 160, 131)), node("标题", listOf(440, 310, 960, 348)),
            node("作者", listOf(505, 370, 850, 395)))
        assertTrue(page.onList(list, emptyList()))
        assertEquals(listOf("标题"), page.records(list, emptyList()))
        assertEquals(listOf("暂无历史记录"), page.records(screen(node("暂无历史记录", listOf(330, 480, 660, 520))), emptyList()))
    }

    @Test fun acceptsMergedOrderLabelsAndSkipsTheClippedNextCard() {
        val rows = listOf(VisibleTextLine("测试商家 已送达", listOf(90, 280, 940, 306)),
            VisibleTextLine("可见商品 ￥２０．５０", listOf(290, 397, 946, 420)), line("共1件", 840, 432),
            line("另一家", 160, 940), line("已完成", 840, 940))
        assertEquals(listOf("测试商家 · 已送达 · 可见商品 · 共1件 · 页面金额 ¥20.50"),
            AccountCollectionPage.ELE.records(screen(), rows))
    }

    @Test fun unreadableCardFieldsStayExplicitAndADiscountBelowTheCardCannotBecomeItsPrice() {
        val rows = listOf(line("示例商家", 180, 260), line("不可辨", 830, 260), line("？", 840, 329),
            line("共2件", 840, 358), line("优惠 ¥9.90", 780, 437))
        assertEquals(listOf("示例商家 · 状态未核验 · 商品名未读到 · 共2件 · 金额未核验"),
            AccountCollectionPage.ELE.records(screen(), rows))
    }

    @Test fun ordersRequireListAndBothMerchantAndStatus() {
        val page = AccountCollectionPage.ELE
        val rows = listOf(line("全部订单", 20, 110), line("待评价", 440, 110),
            line("验收餐厅", 180, 260), line("已送达", 830, 263), line("测试套餐", 290, 328), line("￥18.50", 840, 332),
            line("共1件", 840, 360), line("默认", 290, 361), line("再来一单", 770, 510), line("已取消", 820, 775))
        assertTrue(page.onList(screen(), rows))
        assertFalse(page.onList(screen(), rows.drop(1)))
        assertEquals(listOf("验收餐厅 · 已送达 · 测试套餐 · 共1件 · 页面金额 ¥18.50"), page.records(screen(), rows))
    }
}
