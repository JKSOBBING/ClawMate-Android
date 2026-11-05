package com.clawmate.platform.sources

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 内置节日表：中国传统节日、主流西方节日、常见国际日与纪念日。
 *
 * 设计原则：
 *  - 只收录“日期确定”的节日：固定公历日期、固定第几个星期几、或明确的农历日期。
 *  - 农历节日按农历规则换算（见 [LunarCalendar]），不是写死年份。
 *  - 与“常用节假日”（国务院放假安排）互补：节假日决定放不放假，节日回答“今天是什么日子”。
 *  - 每条都带类别 [Category]，界面上可分别开关。
 */
object Festivals {
    /** 来源编号：与系统日历（非负）、常用节假日（-1）、节气（-3）区分。 */
    const val SOURCE_ID = -2L
    const val SOURCE_NAME = "节日"
    const val KIND = "节日"

    enum class Category(val key: String, val label: String) {
        /** 中国传统节日（按农历或公历，如春节、元宵、中秋）。 */
        CHINESE("chinese", "中国传统节日"),
        /** 主流西方节日。 */
        WESTERN("western", "西方节日"),
        /** 国际日与纪念日（联合国/世界性纪念日）。 */
        INTERNATIONAL("international", "国际日与纪念日"),
    }

    /** 一个节日的定义：[dateIn] 给出该年份中的日期。 */
    private class Festival(
        val name: String,
        val category: Category,
        val note: String,
        val dateIn: (Int) -> LocalDate?,
    )

    /** 固定公历日期。 */
    private fun fixed(name: String, category: Category, month: Int, day: Int, note: String) =
        Festival(name, category, note) { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull() }

    /** 某月第 n 个星期 w；n = -1 表示最后一个。 */
    private fun nthWeekday(name: String, category: Category, month: Int, weekday: DayOfWeek, n: Int, note: String) =
        Festival(name, category, note) { year ->
            val first = LocalDate.of(year, month, 1)
            if (n > 0) {
                val offset = (weekday.value - first.dayOfWeek.value + 7) % 7
                first.plusDays((offset + (n - 1) * 7).toLong())
            } else {
                val last = first.withDayOfMonth(first.lengthOfMonth())
                val back = (last.dayOfWeek.value - weekday.value + 7) % 7
                last.minusDays(back.toLong())
            }
        }

    /** 农历日期（闰月不计，与官方习俗一致）。 */
    private fun lunar(name: String, category: Category, month: Int, day: Int, note: String) =
        Festival(name, category, note) { year -> LunarCalendar.solarDate(year, month, day) }

    /** 按“某月第 n 天，若逢周末则调整”这类规则不收录：容易与放假安排冲突，交给“常用节假日”。 */
    private val LIST: List<Festival> = listOf(
        // ---- 中国传统节日 ----
        lunar("春节", Category.CHINESE, 1, 1, "农历正月初一"),
        lunar("元宵节", Category.CHINESE, 1, 15, "农历正月十五"),
        lunar("龙抬头", Category.CHINESE, 2, 2, "农历二月初二"),
        lunar("七夕节", Category.CHINESE, 7, 7, "农历七月初七"),
        lunar("中元节", Category.CHINESE, 7, 15, "农历七月十五"),
        lunar("中秋节", Category.CHINESE, 8, 15, "农历八月十五"),
        lunar("重阳节", Category.CHINESE, 9, 9, "农历九月初九"),
        lunar("腊八节", Category.CHINESE, 12, 8, "农历腊月初八"),
        lunar("小年", Category.CHINESE, 12, 23, "农历腊月二十三（北方小年）"),
        // 除夕是“上一农历年”的最后一天：查 2026 年时除夕属于农历 2025 年，
        // 先按 year - 1 换算，并确认它确实落在该公历年内。
        Festival("除夕", Category.CHINESE, "农历腊月的最后一天（除夕）") { year ->
            val eve = LunarCalendar.newYearsEve(year - 1)
            if (eve != null && eve.year == year) eve else LunarCalendar.newYearsEve(year)?.takeIf { it.year == year }
        },
        // ---- 西方节日 ----
        fixed("元旦", Category.WESTERN, 1, 1, "公历新年"),
        fixed("情人节", Category.WESTERN, 2, 14, "Valentine's Day"),
        nthWeekday("母亲节", Category.WESTERN, 5, DayOfWeek.SUNDAY, 2, "五月第二个星期日"),
        nthWeekday("父亲节", Category.WESTERN, 6, DayOfWeek.SUNDAY, 3, "六月第三个星期日"),
        fixed("万圣节前夜", Category.WESTERN, 10, 31, "Halloween"),
        fixed("平安夜", Category.WESTERN, 12, 24, "Christmas Eve"),
        fixed("圣诞节", Category.WESTERN, 12, 25, "Christmas Day"),
        nthWeekday("感恩节", Category.WESTERN, 11, DayOfWeek.THURSDAY, 4, "美国：十一月第四个星期四"),
        // ---- 国际日与纪念日 ----
        fixed("世界湿地日", Category.INTERNATIONAL, 2, 2, "World Wetlands Day"),
        fixed("国际妇女节", Category.INTERNATIONAL, 3, 8, "International Women's Day"),
        fixed("世界消费者权益日", Category.INTERNATIONAL, 3, 15, "World Consumer Rights Day"),
        fixed("世界水日", Category.INTERNATIONAL, 3, 22, "World Water Day"),
        fixed("世界气象日", Category.INTERNATIONAL, 3, 23, "World Meteorological Day"),
        fixed("世界卫生日", Category.INTERNATIONAL, 4, 7, "World Health Day"),
        fixed("世界地球日", Category.INTERNATIONAL, 4, 22, "Earth Day"),
        fixed("世界读书日", Category.INTERNATIONAL, 4, 23, "World Book Day"),
        fixed("劳动节·国际劳动节", Category.INTERNATIONAL, 5, 1, "International Workers' Day"),
        fixed("国际护士节", Category.INTERNATIONAL, 5, 12, "International Nurses Day"),
        fixed("世界环境日", Category.INTERNATIONAL, 6, 5, "World Environment Day"),
        fixed("世界海洋日", Category.INTERNATIONAL, 6, 8, "World Oceans Day"),
        fixed("六一国际儿童节", Category.INTERNATIONAL, 6, 1, "International Children's Day"),
        fixed("国际禁毒日", Category.INTERNATIONAL, 6, 26, "International Day against Drug Abuse"),
        fixed("世界人口日", Category.INTERNATIONAL, 7, 11, "World Population Day"),
        fixed("世界旅游日", Category.INTERNATIONAL, 9, 27, "World Tourism Day"),
        fixed("国际和平日", Category.INTERNATIONAL, 9, 21, "International Day of Peace"),
        fixed("世界动物日", Category.INTERNATIONAL, 10, 4, "World Animal Day"),
        fixed("世界粮食日", Category.INTERNATIONAL, 10, 16, "World Food Day"),
        fixed("联合国日", Category.INTERNATIONAL, 10, 24, "United Nations Day"),
        fixed("世界艾滋病日", Category.INTERNATIONAL, 12, 1, "World AIDS Day"),
        fixed("国际志愿者日", Category.INTERNATIONAL, 12, 5, "International Volunteer Day"),
        fixed("世界人权日", Category.INTERNATIONAL, 12, 10, "Human Rights Day"),
        fixed("国家公祭日", Category.INTERNATIONAL, 12, 13, "中国南京大屠杀死难者国家公祭日"),
    )

    /** 所有节日的名称与类别，供界面展示。 */
    fun catalog(): List<Triple<String, Category, String>> =
        LIST.map { Triple(it.name, it.category, it.note) }

    /** 某年所有节日（去重：同一天同名只保留一条）。 */
    fun of(year: Int): List<Pair<LocalDate, String>> {
        val seen = HashSet<String>()
        return LIST.mapNotNull { festival -> festival.dateIn(year)?.let { Triple(it, festival.name, festival.category) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .filter { seen.add(it.first.toString() + "|" + it.second) }
            .map { it.first to it.second }
    }

    /** 某年某个类别的节日。 */
    fun of(year: Int, category: Category): List<Pair<LocalDate, String>> =
        LIST.filter { it.category == category }
            .mapNotNull { festival -> festival.dateIn(year)?.let { it to festival.name } }
            .sortedBy { it.first }

    /** 区间内的节日条目；[categories] 为空表示不过滤。 */
    fun entries(from: LocalDate, to: LocalDate, categories: Set<Category> = emptySet()): List<HolidayEntry> {
        if (to < from) return emptyList()
        val out = ArrayList<HolidayEntry>()
        var year = from.year
        while (year <= to.year) {
            LIST.forEach { festival ->
                if (categories.isNotEmpty() && festival.category !in categories) return@forEach
                val date = festival.dateIn(year) ?: return@forEach
                if (date < from || date > to) return@forEach
                out.add(HolidayEntry(
                    id = "$KIND:$date:${festival.name}",
                    title = festival.name,
                    description = "${date} 是${festival.name}（${festival.note}）。",
                    start = date.toString(),
                    end = date.toString(),
                    allDay = true,
                    kind = KIND,
                    notice = festival.category.label,
                ))
            }
            year++
        }
        return out.sortedWith(compareBy({ it.start }, { it.title }))
    }

    /** 可用的类别键（用于设置持久化）。 */
    fun categoryOf(key: String): Category? = Category.values().firstOrNull { it.key == key }
}
