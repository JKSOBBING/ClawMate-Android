package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.VisibleTextLine
import org.junit.Assert.*
import org.junit.Test

class EleOrderFieldsTest {
    private fun line(s: String, x: Int, y: Int, right: Int = x + 80) =
        VisibleTextLine(s, listOf(x, y - 7, right, y + 7))

    @Test fun readsOnlyTheLabelledFinalPaymentOnTheSummaryRow() {
        assertEquals("18.25", EleOrderFields.amount("总优惠 ￥7.4 实付 ￥１８．２５"))
        assertEquals("0", EleOrderFields.amount("实付 ¥0"))
        assertNull(EleOrderFields.amount("优惠 ¥9.90"))
        assertNull(EleOrderFields.amount("实付 ¥1.234"))
    }

    @Test fun collapsedCountMustBelongToTheBandAboveThePriceDisclosure() {
        val rows = listOf(line("共3件", 850, 390), line("共2件", 850, 190), line("共1件", 120, 400))
        assertEquals(3, EleOrderFields.collapsedQuantity(rows, 445))
    }

    @Test fun normalizesMatchedChineseQuotationMarksInRecognizedProductNames() {
        assertEquals("套餐“香”味-组合“鲜”味", EleOrderFields.productTitle("套餐“香\"味-组合\"鲜\"味"))
        assertEquals("12\"比萨", EleOrderFields.productTitle("12\"比萨"))
    }

    @Test fun partlyRecognizedDisclosureRequiresTheOrderHeadingCountAndSameRowTotal() {
        val title = line("明细", 44, 499)
        val rows = listOf(title, line("总优惠¥7 实付¥16", 560, 498, 966), line("共1件", 881, 444),
            line("订单号", 45, 601), line("订单信息", 45, 642))
        assertEquals(title, EleOrderFields.pairedDisclosure(rows))
        assertNull(EleOrderFields.pairedDisclosure(rows.filterNot { it.text == "共1件" }))
        assertNull(EleOrderFields.pairedDisclosure(rows.dropLast(2)))
    }

    @Test fun identifiesOnlyOrderDetailsAndReadOnlyPriceDisclosures() {
        assertTrue(EleOrderFields.isOrderDetail(listOf(line("订单号", 50, 580),line("订单信息", 40, 680))))
        assertFalse(EleOrderFields.isOrderDetail(listOf(line("全部订单", 30, 120),line("查看订单详情", 40, 220))))
        assertTrue(EleOrderFields.disclosureTitle("價格明細 ﹀"))
        assertTrue(EleOrderFields.disclosureTitle("价恪明细"))
        assertFalse(EleOrderFields.disclosureTitle("立即支付"))
        assertFalse(EleOrderFields.disclosureTitle("订单信息"))
    }

    @Test fun assemblesWrappedNamesAndExcludesFeesAndThumbnails() {
        val names = listOf(line("时令蔬菜拼盘", 200, 455), line("（双人份）", 200, 479),
            line("饮料", 200, 552), line("图标", 70, 530), line("配送费", 210, 610))
        val fields = listOf(line("实付 ¥21", 810, 460), line("实付 ¥3", 810, 558))
        assertEquals(listOf("时令蔬菜拼盘(双人份)", "饮料"), EleOrderFields.products(names, fields, 400, 580))
    }
}
