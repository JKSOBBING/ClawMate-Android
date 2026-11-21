package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import java.text.Normalizer
import kotlinx.coroutines.delay

/** The built-in account tasks only finish on the verified list, with visible records or an explicit empty state. */
internal enum class AccountCollectionPage(val pkg: String, val accountTab: String, val entry: String, val heading: String) {
    BILI("tv.danmaku.bili", "我的", "历史记录", "历史记录"),
    XHS("com.xingin.xhs", "我", "浏览记录", "浏览记录"),
    ELE("me.ele", "我的", "全部", "全部订单");

    companion object {
        fun forRoute(pkg: String, route: List<String>): AccountCollectionPage? = entries.firstOrNull {
            pkg == it.pkg && (route == listOf(it.accountTab, it.entry) ||
                it == ELE && route == listOf("我的", "全部订单") || it == XHS && route == listOf("菜单", "浏览记录"))
        }

        private val profileNames = setOf("我的", "我", "个人中心", "我的主页", "我的淘宝")
        private val navNames = setOf("我的", "我", "个人中心", "我的主页", "我的淘宝", "历史记录", "观看历史", "浏览记录",
            "最近浏览", "浏览历史", "我的足迹", "足迹", "最近看过", "我的订单", "全部订单", "订单", "全部")
        private val money = Regex("""(?:[¥￥]\d+(?:\.\d{1,2})?(?![\d.,])|\d+(?:\.\d{1,2})?元)""")
        private val itemCount = Regex("""共\d+[件份个]""")
        private val orderStates = setOf("已送达", "已完成", "已取消", "已收货", "已退款", "退款成功", "配送中",
            "商家已接单", "等待送达", "待取餐", "交易成功", "交易关闭", "订单已完成", "订单已取消", "订单已送达")
    }

    private fun String.nav() = Normalizer.normalize(this, Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
    private fun List<Int>.top() = this[1]
    private fun List<Int>.width() = this[2] - this[0]
    private fun List<Int>.height() = this[3] - this[1]
    private fun List<Int>.midY() = (this[1] + this[3]) / 2
    private fun lines(screen: ScreenSnapshot) = screen.elements.filter { !it.editable && !it.secret && it.name.isNotBlank() }
        .map { VisibleTextLine(it.name, it.bounds) }

    fun onList(screen: ScreenSnapshot, ocr: List<VisibleTextLine>): Boolean {
        val tokens = (lines(screen) + ocr).filter { it.bounds.top() < 235 }.map { it.text.nav() }
        if (this == ELE) return tokens.any { "全部订单" in it } && tokens.any { "待评价" in it || "待收货" in it || "退款" in it }
        val toolbar = tokens.toSet()
        return heading in toolbar && when (this) {
            BILI -> "全部" in toolbar && "视频" in toolbar
            XHS -> "笔记" in toolbar && "直播" in toolbar
            ELE -> false // Handled above because the client paints several tab labels on one OCR line.
        }
    }

    private fun orderEntry(ocr: List<VisibleTextLine>): VisibleTextLine? {
        val header = ocr.singleOrNull { it.text.nav() == "我的订单" } ?: return null
        return ocr.singleOrNull { it.text.nav() == "全部" && it.bounds[0] < 320 &&
            it.bounds.midY() - header.bounds.midY() in 10..180 }
    }

    private fun localEntry(screen: ScreenSnapshot, label: String, bottom: Boolean): UiElement? {
        val exact = screen.elements.filter { !it.secret && !it.editable && it.name.nav() == label &&
            (!bottom || it.bounds.midY() > 810) }
        val candidates = exact.filter { it.clickable }.ifEmpty { exact }
        return candidates.singleOrNull() ?: candidates.minByOrNull { it.bounds.width() * it.bounds.height() }
            ?.takeIf { small -> candidates.all { small.centerX in it.centerX - 30..it.centerX + 30 && kotlin.math.abs(small.bounds.midY() - it.bounds.midY()) < 45 } }
    }

    suspend fun open(port: ScreenPort, snapshot: suspend () -> ScreenSnapshot,
        choose: suspend (String, List<UiElement>, ScreenSnapshot) -> Int) {
        for (label in listOf(accountTab, entry)) {
            val bottom = label == accountTab
            var taps = 0
            var profileReached = false
            var returnedFromDetail = false
            for (attempt in 0 until 10) {
                val screen = snapshot()
                val ocr = if (this == ELE && (attempt > 0 || !bottom)) port.readVisibleText().orEmpty() else emptyList()
                if (onList(screen, ocr)) return
                if (this == ELE && bottom && !returnedFromDetail && EleOrderFields.isOrderDetail(ocr)) {
                    check(port.back() is ActionResult.Ok) { "无法从原订单详情返回" }
                    returnedFromDetail = true
                    delay(400)
                    continue
                }
                val orderButton = if (this == ELE) orderEntry(ocr) else null
                if (bottom && (if (this == ELE) orderButton != null else localEntry(screen, entry, false) != null)) {
                    profileReached = true
                    break
                }
                val node = localEntry(screen, label, bottom).takeUnless { this == ELE && !bottom }
                if (taps == 0 || taps == 1 && attempt >= 4) {
                    if (node != null) {
                        // Prefer the app's own clickable tab. Animated feeds can invalidate a node reference,
                        // so verify its current label and bounds before a coordinate retry.
                        val fresh = snapshot()
                        val same = localEntry(fresh, label, bottom)
                        if (same != null && same.bounds == node.bounds) {
                            if (this == BILI && taps == 0 || taps == 1 && this != BILI)
                                port.tapXY(same.centerX, same.centerY)
                            else port.tapRef(same.ref, fresh.snapshotId)
                            taps++
                        }
                    } else if (!bottom && orderButton != null) {
                        val fresh = snapshot()
                        val bounds = orderButton.bounds
                        if (orderEntry(port.readVisibleText().orEmpty())?.bounds == bounds) {
                            port.tapXY((bounds[0] + bounds[2]) * (fresh.width - 1) / 1998, bounds.midY() * (fresh.height - 1) / 999)
                            taps++
                        }
                    } else if (attempt == 6 && taps == 0) {
                        val options = screen.elements.filter { it.clickable && !it.secret && !it.editable &&
                            it.name.trim() in navNames && (if (bottom) it.bounds.midY() > 810 else it.name.trim() !in profileNames) }
                            .groupBy { it.name.trim() }.filterValues { it.size == 1 }.values.map { it.single() }
                        check(options.isNotEmpty()) { "未找到可核验的“$label”入口" }
                        val chosen = options[choose(label, options, screen)]
                        val fresh = snapshot()
                        val same = fresh.elements.singleOrNull { it.name == chosen.name && it.bounds == chosen.bounds && it.clickable }
                        check(same != null && port.tapRef(same.ref, fresh.snapshotId) is ActionResult.Ok) { "入口已变化，请重新运行" }
                        taps = 2
                    }
                }
                delay(400)
            }
            if (!profileReached) error("未核实到达“${if (bottom) accountTab else heading}”页面，请重试")
        }
    }

    fun records(screen: ScreenSnapshot, ocr: List<VisibleTextLine>): List<String> {
        val body = screen.elements.filter { !it.editable && !it.secret && it.name.isNotBlank() && it.bounds.midY() > 145 }
        val empty = (body.map { it.name } + ocr.map { it.text }).firstOrNull { it.nav() in when (this) {
            BILI -> setOf("暂无历史记录", "还没有历史记录", "暂无观看记录", "这里还没有内容哦")
            XHS -> setOf("暂无浏览记录", "还没有浏览记录", "暂无笔记浏览记录", "暂无记录")
            ELE -> setOf("暂无订单", "暂无订单哦", "还没有订单", "您还没有订单", "暂时没有订单", "暂无相关订单", "没有找到相关订单")
        } }
        val titles = when (this) {
            XHS -> body.filter { it.clickable && it.bounds.width() in 380..530 && it.bounds.height() > 140 }
                .sortedWith(compareBy({ it.bounds.top() }, { it.bounds[0] })).map { it.name }
            BILI -> body.filter { it.role == "TextView" && !it.clickable && it.bounds[0] in 396..480 &&
                it.bounds[2] > 850 && it.bounds.height() >= 13 && it.name !in setOf("直播", "视频", "商品") }
                .sortedBy { it.bounds.top() }.map { it.name }
            ELE -> orderRows(ocr)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return titles.ifEmpty { listOfNotNull(empty) }
    }

    private fun stateAtRight(line: VisibleTextLine): String? {
        if (line.bounds[2] < 700) return null
        val text = line.text.nav().trimEnd('>', '›', '》', '〉')
        return orderStates.firstOrNull { it == text } ?: orderStates.filter(text::endsWith).maxByOrNull { it.length }
    }

    internal data class OrderCard(val merchant: String, val state: String, val product: String,
        val count: String, val amount: String?, val headerY: Int, val countY: Int,
        val priceRead: String) {
        fun summary() = listOf(merchant, state, product.ifBlank { "商品名未读到" }, count,
            amount?.let { "页面金额 $it" } ?: "金额未核验").joinToString(" · ")
    }

    private fun orderRows(ocr: List<VisibleTextLine>) = orderCards(ocr).map { it.summary() }.distinct()

    fun orderCards(ocr: List<VisibleTextLine>): List<OrderCard> {
        val rows = ocr.filter { it.bounds.midY() > 160 }.sortedBy { it.bounds.midY() }
        val quantities = rows.filter { it.bounds[2] > 750 && itemCount.matches(it.text.nav()) }
        // The amount is directly above the item count at the right of each card.
        // The same page can contain coupon amounts or totals below a card.
        return quantities.mapNotNull { count ->
            val price = rows.filter { it.bounds[2] > 745 && kotlin.math.abs(it.bounds[2] - count.bounds[2]) < 85 &&
                count.bounds.midY() - it.bounds.midY() in 12..55 }.maxByOrNull { it.bounds.midY() } ?: return@mapNotNull null
            val amount = money.findAll(price.text.nav()).singleOrNull()?.takeIf { it.range.last == price.text.nav().lastIndex }?.value
            val nearby = rows.filter { price.bounds.midY() - it.bounds.midY() in 32..165 && it.bounds[2] > 770 }
            var headerLine: VisibleTextLine? = null
            val pair = nearby.asReversed().firstNotNullOfOrNull { header ->
                headerLine = header
                val state = stateAtRight(header)
                if (state != null && header.bounds[0] < 470) {
                    header.text.substringBeforeLast(state).trim(' ', '>', '›').takeIf { it.isNotBlank() }?.let { it to state }
                } else if (header.bounds[0] > 680 && (state != null || header.text.nav().length in 2..5)) {
                    val y = header.bounds.midY()
                    val merchant = rows.filter { kotlin.math.abs(it.bounds.midY() - y) <= 12 &&
                        it.bounds[0] in 65..440 && it.bounds[2] > 240 && it.bounds[2] <= header.bounds[0] }
                        .sortedBy { it.bounds[0] }.joinToString("") { it.text.trim() }
                    merchant.takeIf { it.isNotBlank() }?.let { it to (state ?: "状态未核验") }
                } else null
            } ?: return@mapNotNull null
            val name = if (amount != null && price.bounds[0] in 210..480) price.text.nav().substringBefore(amount).trim()
            else {
                val candidate = rows.filter { it.bounds[0] in 225..470 && it.bounds[2] <= price.bounds[0] &&
                    kotlin.math.abs(it.bounds.midY() - price.bounds.midY()) <= 14 && it.text.trim().length >= 2 }
                    .singleOrNull()
                candidate?.takeIf { line -> rows.any { below ->
                    kotlin.math.abs(below.bounds[0] - line.bounds[0]) < 25 && below.bounds.midY() - line.bounds.midY() in 14..80
                } }?.text?.trim().orEmpty()
            }
            val header = requireNotNull(headerLine)
            val raw = price.text.nav()
            val reason = when {
                amount != null -> "列表金额可读"
                raw.none(Char::isDigit) -> "价格行未识别出数字"
                raw.none { it in "¥元" } -> "价格行未识别出货币标记"
                else -> "价格行金额格式或边界不完整"
            }
            OrderCard(pair.first, pair.second, name, count.text.nav(), amount, header.bounds.midY(),
                count.bounds.midY(), reason)
        }
    }
}
