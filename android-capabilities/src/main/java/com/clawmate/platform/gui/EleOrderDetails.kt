package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.VisibleTextLine
import java.text.Normalizer
import kotlinx.coroutines.delay

private fun String.clean() = Normalizer.normalize(this, Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
private val VisibleTextLine.y get() = (bounds[1] + bounds[3]) / 2
private val VisibleTextLine.x get() = (bounds[0] + bounds[2]) / 2

internal object EleOrderFields {
    val count = Regex("""共([1-9]\d{0,2})[件份个]""")
    private val paid = Regex("""实付(?:款|金额)?[：:]?[¥Y]?(\d+(?:\.\d{1,2})?)$""")
    fun amount(text: String) = paid.find(text.clean())?.groupValues?.get(1)
    fun isOrderDetail(lines: List<VisibleTextLine>): Boolean {
        val labels = lines.map { it.text.clean() }
        return labels.any { it.startsWith("订单号") || it.startsWith("订单编号") } &&
            labels.any { it.startsWith("订单信息") || it.startsWith("价格明细") }
    }
    fun pairedDisclosure(rows: List<VisibleTextLine>): VisibleTextLine? {
        if (!isOrderDetail(rows)) return null
        return rows.singleOrNull { left ->
            left.bounds[0] < 165 && left.text.clean().contains("明细") &&
                rows.any { right -> right.bounds[2] > 812 && kotlin.math.abs(right.y - left.y) <= 12 &&
                    right.text.clean().let { "实付" in it && "优惠" in it } } &&
                rows.any { countLine -> countLine.bounds[2] > 812 && left.y-countLine.y in 18..111 &&
                    Regex("""共.{0,3}[件份个]""").containsMatchIn(countLine.text.clean()) }
        }
    }
    fun priceLabel(lines: List<VisibleTextLine>) = lines.firstOrNull {
        it.bounds[0] < 160 && it.text.clean().replace('價','价').replace('細','细').startsWith("价格明细")
    }

    fun disclosureTitle(text: String): Boolean {
        val word = text.clean().filter(Char::isLetter).replace('價','价').replace('細','细')
        return word in setOf("价格明细", "费用明细", "价明细", "格明细", "价格明", "价格细") ||
            word.length == 4 && word.zip("价格明细").count { (a,b) -> a == b } >= 3
    }
    fun collapsedQuantity(lines: List<VisibleTextLine>, priceY: Int) = lines.filter {
        it.bounds[2] > 790 && priceY - it.y in 18..112
    }.mapNotNull { count.matchEntire(it.text.clean())?.groupValues?.get(1)?.toInt() }.singleOrNull()

    private val quotedChinese = Regex("""["“”]([\u3400-\u9fff]{1,16})["“”]""")
    fun productTitle(text: String): String {
        if ('“' !in text && '”' !in text) return text
        return quotedChinese.replace(text) { "“" + it.groupValues[1] + "”" }
    }

    fun products(names: List<VisibleTextLine>, page: List<VisibleTextLine>, start: Int, end: Int): List<String> {
        // Item names are the dark left-hand column. Specs and quantities are grey, fees start at the margin.
        val prices = page.filter { it.bounds[2] > 780 && it.y in start until end &&
            (it.text.clean().contains("实付") || it.text.clean().matches(Regex("""[¥￥]\d+(?:\.\d{1,2})?"""))) }
            .sortedBy { it.y }
        val groups = mutableListOf<MutableList<VisibleTextLine>>()
        for (name in names.filter { it.bounds[0] in 173..251 && it.y in start until end }.sortedBy { it.y }) {
            if (name.text.clean().isBlank()) continue
            if (groups.isEmpty() || name.y - groups.last().last().y > 29) groups.add(mutableListOf())
            groups.last().add(name)
        }
        return groups.filter { g -> prices.isEmpty() || prices.any { kotlin.math.abs(g.first().y - it.y) <= 23 } }
            .map { group -> group.joinToString("") { it.text.clean() }.trimStart('|', ',', '，', '丨') }
    }
}

internal class EleOrderDetails(private val port: MikaScreenPort, private val snapshot: suspend () -> ScreenSnapshot) {
    private val page = AccountCollectionPage.ELE
    private val visited = mutableSetOf<String>()
    var opens = 0
    var returns = 0
    val reasons = mutableListOf<String>()
    var partial = false
    private var lastViewport = emptyList<AccountCollectionPage.OrderCard>()
    var verifiedOverlaps = 0
        private set
    val pageSizes = mutableListOf<Int>()

    suspend fun cards(raw: List<VisibleTextLine>): List<AccountCollectionPage.OrderCard> {
        val old = page.orderCards(raw)
        suspend fun column(left: Int, right: Int): List<VisibleTextLine> {
            val bands = listOf(130 to 445, 421 to 712, 688 to 985)
            return bands.flatMap { (top, bottom) -> port.readTextRegion(left, top, right, bottom, false) }
                .sortedBy { it.y }.fold(mutableListOf<VisibleTextLine>()) { unique, row ->
                    if (unique.none { it.text.clean() == row.text.clean() && kotlin.math.abs(it.y-row.y) < 8 }) unique.add(row)
                    unique
                }
        }
        val right = column(758, 988)
        val left = column(148, 800)
        val statuses = setOf("已送达", "订单已送达", "准时送达", "已完成", "已取消", "已收货", "已退款", "配送中", "退款成功")
        val quantities = right.filter { EleOrderFields.count.matches(it.text.clean()) && it.bounds[2] > 869 }
        val found = mutableListOf<AccountCollectionPage.OrderCard>()
        for (shop in left.filter { it.bounds[0] in 149..190 && it.y in 151..958 }) {
            val status = right.firstOrNull { kotlin.math.abs(it.y - shop.y) <= 12 && it.text.clean().trimEnd('>', '›') in statuses }
            val count = quantities.firstOrNull { it.y - shop.y in 48..157 }
            val merchant = shop.text.trim().trimEnd('>', '›')
            val storeTitle = merchant.any { it in "(（" } || merchant.contains("店") || merchant.endsWith("…")
            if (status == null && (count == null || !storeTitle)) continue
            if (found.any { kotlin.math.abs(it.headerY - shop.y) <= 13 }) continue
            val previous = old.firstOrNull { kotlin.math.abs(it.headerY - shop.y) <= 15 }
            found += AccountCollectionPage.OrderCard(merchant, status?.text?.clean() ?: previous?.state ?: "状态未核验",
                previous?.product.orEmpty(), count?.text?.clean() ?: previous?.count.orEmpty(), previous?.amount, shop.y,
                count?.y ?: previous?.countY ?: 0, previous?.priceRead ?: "列表价格与件数未配对")
        }
        return (found + old.filter { a -> found.none { kotlin.math.abs(it.headerY - a.headerY) < 19 } })
            .sortedBy { it.headerY }
    }

    private suspend fun read(): List<VisibleTextLine> {
        snapshot()
        return port.readVisibleText().orEmpty()
    }

    private suspend fun tap(x: Int, y: Int) {
        val screen = snapshot()
        check(port.tapXY(x * (screen.width - 1) / 999, y * (screen.height - 1) / 999) is ActionResult.Ok) {
            "订单入口未响应，请重试"
        }
    }

    private fun sameShop(a: AccountCollectionPage.OrderCard, b: AccountCollectionPage.OrderCard): Boolean {
        fun letters(value: String) = value.clean().filter(Char::isLetterOrDigit)
        val x = letters(a.merchant)
        val z = letters(b.merchant)
        if (x == z || minOf(x.length, z.length) >= 6 && (x.startsWith(z) || z.startsWith(x))) return true
        val grams = x.windowed(2).toSet()
        return grams.isNotEmpty() && grams.intersect(z.windowed(2).toSet()).size * 5 >= grams.size * 3
    }

    private fun locate(a: AccountCollectionPage.OrderCard, cards: List<AccountCollectionPage.OrderCard>, y: Int): AccountCollectionPage.OrderCard? {
        val matches = cards.filter { sameShop(a, it) }
        if (matches.size <= 1) return matches.singleOrNull()
        val consistent = matches.filter { (a.amount == null || it.amount == null || a.amount == it.amount) &&
            (a.count.isBlank() || it.count.isBlank() || a.count == it.count) }
        return consistent.singleOrNull() ?: consistent.singleOrNull { kotlin.math.abs(it.headerY - y) < 54 }
    }

    private suspend fun safeEntry(card: AccountCollectionPage.OrderCard, preferCount: Boolean): VisibleTextLine? {
        suspend fun inBand(y: Int, matches: (String) -> Boolean): VisibleTextLine? {
            val labels = port.readTextRegion(733, (y - 23).coerceAtLeast(0), 989, (y + 23).coerceAtMost(999), false)
            return labels.singleOrNull { kotlin.math.abs(it.y - y) < 15 && matches(it.text.clean().trimEnd('>', '›')) }
        }
        if (!preferCount && card.state != "状态未核验") inBand(card.headerY) { it == card.state }?.let { return it }
        if (card.countY > 0) {
            inBand(card.countY) { EleOrderFields.count.matches(it) && (card.count.isBlank() || it == card.count) }
                ?.let { return it }
        }
        return if (card.state == "状态未核验") null else inBand(card.headerY) { it == card.state }
    }

    private fun orderKey(rows: List<VisibleTextLine>): String? {
        val label = rows.firstOrNull { it.bounds[0] < 180 && it.text.clean().startsWith("订单号") } ?: return null
        val text = rows.filter { kotlin.math.abs(it.y - label.y) < 14 }.sortedBy { it.bounds[0] }.joinToString("") { it.text.clean() }
        return Regex("""(?<!\d)\d{15,24}(?!\d)""").find(text)?.value
    }

    private fun detailPage(rows: List<VisibleTextLine>) = rows.any { it.text.clean().let { text ->
        text.startsWith("订单号") || text.startsWith("订单信息") || text.startsWith("订单编号") || text == "价格明细" } }

    private suspend fun disclosure(rows: List<VisibleTextLine>): VisibleTextLine? {
        EleOrderFields.priceLabel(rows)?.let { return it }
        EleOrderFields.pairedDisclosure(rows)?.let { return it }
        val summaries = rows.filter { it.bounds[2] > 730 && it.text.clean().contains("实付") }
        for (summary in summaries) {
            val left = port.readTextRegion(25, (summary.y - 22).coerceAtLeast(0), 272,
                (summary.y + 22).coerceAtMost(999), false)
            left.firstOrNull { it.bounds[0] < 170 && EleOrderFields.disclosureTitle(it.text) }?.let { return it }
        }
        // A full-screen OCR line can join the discount, total and adjacent text. Read the
        // disclosure separately and confirm the total in the same horizontal band.
        for ((top, bottom) in listOf(182 to 486, 462 to 752, 728 to 958)) {
            val left = port.readTextRegion(22, top, 278, bottom, false)
            for (candidate in left.filter { it.bounds[0] < 170 && EleOrderFields.disclosureTitle(it.text) }) {
                val total = port.readTextRegion(520, (candidate.y-22).coerceAtLeast(0), 986,
                    (candidate.y+22).coerceAtMost(999), true)
                if (EleOrderFields.amount(total.sortedBy { it.x }.joinToString("") { it.text }) != null) return candidate
            }
        }
        return null
    }

    private data class Detail(val text: String, val fields: Int)
    private data class Visit(val detail: Detail?, val key: String?, val list: List<AccountCollectionPage.OrderCard>)

    private suspend fun readDetail(rows: List<VisibleTextLine>, label: VisibleTextLine, card: AccountCollectionPage.OrderCard): Detail {
        val countBand = port.readTextRegion(822, (label.y-100).coerceAtLeast(0), 984, (label.y-13).coerceAtLeast(1), false)
        val counts = (countBand + rows).filter { it.bounds[2] > 784 && label.y - it.y in 17..111 }
        val countLine = counts.firstOrNull { EleOrderFields.count.matches(it.text.clean()) } ?: counts.firstOrNull {
            Regex("""共.{0,3}[件份个]""").matches(it.text.clean()) }
        var quantity = countLine?.let { EleOrderFields.count.matchEntire(it.text.clean())?.groupValues?.get(1)?.toInt() }
        val totalLines = port.readTextRegion(535, (label.y - 23).coerceAtLeast(0), 986, (label.y + 23).coerceAtMost(999), true)
        val total = EleOrderFields.amount(totalLines.sortedBy { it.x }.joinToString("") { it.text })
            ?: EleOrderFields.amount(rows.filter { kotlin.math.abs(it.y - label.y) <= 11 }.sortedBy { it.x }.joinToString("") { it.text })
        // Bind the product area to the collapsed card above this verified price disclosure.
        val merchant = rows.filter { it.bounds[0] < 245 && label.y - it.y in 68..164 &&
            it.text.clean().let { t -> t.contains("闪购") || t.contains(card.merchant.clean().take(3)) } }.maxByOrNull { it.y }
        val start = merchant?.let { it.bounds[3] + 4 } ?: countLine?.let { (it.y - 42).coerceAtLeast(0) }
        var products = emptyList<String>()
        var productsComplete = false
        if (start != null) {
            tap(100, label.y)
            delay(430)
            for (attempt in 0..2) {
                val expanded = read()
                val fees = expanded.filter { it.bounds[0] < 150 && it.y > start &&
                    it.text.clean().let { t -> t.startsWith("打包费") || t.startsWith("包装费") || t.startsWith("配送费") || t.startsWith("价格明细") } }
                val end = fees.minOfOrNull { it.bounds[1] } ?: 951
                if (end > start + 21 && (countLine == null || expanded.none { it.y == countLine.y && it.text.clean() == countLine.text.clean() })) {
                    val names = port.readTextRegion(173, start, 735, end, true)
                    val titleLines = names.filter { it.bounds[0] in 173..251 }.map { line ->
                        val top = (line.bounds[1] - 4).coerceAtLeast(start)
                        val bottom = (line.bounds[3] + 4).coerceAtMost(end)
                        val original = if (bottom > top) port.readTextRegion(193, top, 745, bottom, false) else emptyList()
                        val text = original.filter { kotlin.math.abs(it.y - line.y) < 8 }
                            .sortedBy { it.x }.joinToString("") { it.text }
                        val guess = text.ifBlank { line.text }.clean().trimStart('|', ',', '，', '丨')
                        VisibleTextLine(port.verifyPrintedTitle(line, guess), line.bounds)
                    }
                    products = EleOrderFields.products(titleLines, expanded, start, end)
                    if (products.isEmpty() && quantity == 1 && titleLines.size == 1 && fees.isNotEmpty())
                        products = listOf(titleLines.single().text)
                    productsComplete = fees.isNotEmpty() && quantity != null && products.size == quantity
                    if (!productsComplete && products.isNotEmpty() && fees.isNotEmpty()) {
                        val units = port.readTextRegion(188, start, 325, end, false).mapNotNull {
                            Regex("""[×xX✕]([1-9]\d{0,2})""").matchEntire(it.text.clean())?.groupValues?.get(1)?.toInt()
                        }
                        if (units.size == products.size) {
                            val unitTotal = units.sum()
                            if (quantity == null) quantity = unitTotal
                            productsComplete = quantity == unitTotal
                        }
                    }
                    if (products.isNotEmpty()) break
                }
                delay(240)
            }
        }
        val names = products.joinToString("、") { EleOrderFields.productTitle(it) }
        val productText = when {
            names.isEmpty() -> "商品名未核验"
            productsComplete -> names
            else -> "$names（商品明细未读全）"
        }
        val fields = listOf(productsComplete, total != null, quantity != null).count { it }
        val text = listOf(card.merchant, card.state, productText,
            quantity?.let { "共${it}件" } ?: "件数未核验", total?.let { "实付 ¥$it" } ?: "实付金额未核验").joinToString(" · ")
        return Detail(text, fields)
    }


    private suspend fun visit(card: AccountCollectionPage.OrderCard, point: VisibleTextLine, identityOnly: Boolean = false): Visit {
        tap(point.x, point.y)
        port.waitStable(850)
        var rows = emptyList<VisibleTextLine>()
        var label: VisibleTextLine? = null
        for (attempt in 0..4) {
            delay(280)
            rows = read()
            label = disclosure(rows)
            if (label != null) break
            val screen = snapshot()
            if (attempt >= 1 && page.onList(screen, rows)) {
                return Visit(Detail(card.summary() + " · 详情未打开", 0), null, cards(rows))
            }
            if (attempt == 2 && EleOrderFields.isOrderDetail(rows)) {
                // A restored detail can start below the item section, at the invoice or order information.
                val moved = port.swipe(screen.width / 2, screen.height / 3, screen.width / 2, screen.height * 3 / 4, 700)
                check(moved is ActionResult.Ok) { "无法在订单详情中定位价格明细" }
                delay(360)
            }
        }
        val confirmed = label != null || detailPage(rows)
        if (confirmed) opens++
        val key = orderKey(rows)
        val result = when {
            identityOnly || key != null && key in visited -> null
            label != null -> readDetail(rows, label, card)
            else -> Detail(card.summary() + " · 详情未读到价格明细", 0)
        }
        check(port.back() is ActionResult.Ok) { "未能返回订单列表" }
        repeat(5) {
            delay(250)
            val after = read()
            if (!EleOrderFields.isOrderDetail(after) && EleOrderFields.priceLabel(after) == null) {
                val current = cards(after)
                if (current.isNotEmpty() && (page.onList(snapshot(), after) || locate(card, current, card.headerY) != null)) {
                    if (confirmed) returns++
                    return Visit(result, key, current)
                }
            }
        }
        error("返回后未核实到订单列表，已停止采集")
    }

    suspend fun pageRecords(initial: List<AccountCollectionPage.OrderCard>): List<String> {
        val result = mutableListOf<String>()
        pageSizes += initial.size
        var offset = 0
        var current = initial
        for (original in initial) {
            var best: Visit? = null
            for (attempt in 0..1) {
                if (attempt > 0) current = cards(read())
                val card = locate(original, current, original.headerY + offset) ?: continue
                val point = safeEntry(card, preferCount = attempt > 0) ?: continue
                val checked = visit(card, point)
                current = checked.list
                val restored = locate(card, current, card.headerY)
                if (restored != null) offset = restored.headerY - original.headerY
                if (checked.detail == null) { best = checked; break }
                val previous = best
                if (previous?.key != null && checked.key != null && previous.key != checked.key) break
                if (previous == null || checked.detail.fields > (previous.detail?.fields ?: -1)) best = checked
                if (checked.detail.fields == 3) break
            }
            val selected = best
            if (selected == null) { partial = true; continue }
            val detail = selected.detail ?: continue
            selected.key?.let { visited.add(it) }
            partial = partial || detail.fields < 3
            result += detail.text
            reasons += original.priceRead
        }
        lastViewport = current.ifEmpty { initial }
        return result
    }

    /** Keep the last known order in view so a short first page or a restored scroll position cannot skip a card. */
    suspend fun advance(): Boolean {
        val anchor = lastViewport.lastOrNull() ?: return false
        val screen = snapshot()
        suspend fun drag(from: Int, to: Int): Boolean = port.swipe(screen.width / 2, screen.height * from / 1000,
            screen.width / 2, screen.height * to / 1000, 1050) is ActionResult.Ok
        val distance = (anchor.headerY - 258).coerceIn(150, 578)
        check(drag(820, 820 - distance)) { "订单列表无法翻页" }
        repeat(3) { attempt ->
            port.waitStable(900)
            var current = cards(read())
            val overlap = lastViewport.any { old ->
                val match = locate(old, current, old.headerY - distance)
                match != null && (old.count.isBlank() || match.count.isBlank() || old.count == match.count) &&
                    (old.amount == null || match.amount == null || old.amount == match.amount)
            }
            if (overlap) {
                verifiedOverlaps++
                return true
            }
            // Adjacent orders from the same shop can have indistinguishable list labels.
            // Its detail supplies the same order number used for deduplication during this run.
            for (candidate in current.take(2)) {
                val card = locate(candidate, current, candidate.headerY) ?: continue
                val point = safeEntry(card, preferCount = true) ?: continue
                val visit = visit(card, point, identityOnly = true)
                if (visit.key != null && visit.key in visited) {
                    verifiedOverlaps++
                    return true
                }
                current = visit.list
            }
            if (attempt < 2 && !drag(410, 540)) return false
        }
        return false
    }
}
