package com.clawmate.platform.gui

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.*
import org.junit.Test

class WeiboChatSearchTest {
    @Test fun extractsOnlyTheRequestedTopic() {
        assertEquals("algs拉斯维加斯", WeiboChatSearch.query("去微博找一下algs拉斯维加斯的热点内容"))
        assertEquals("城市 马拉松", WeiboChatSearch.query("在微博里搜一下「城市 马拉松」"))
        assertNull(WeiboChatSearch.query("去微博找一下比赛，然后给帖子点赞"))
        assertNull(WeiboChatSearch.query("去微博找一下十条最新的比赛内容"))
        assertNull(WeiboChatSearch.query("打开微博"))
    }

    @Test fun requiresEachPartOfMixedScriptQueryInTheActualPost() {
        val terms = WeiboChatSearch.queryTerms("algs拉斯维加斯")
        assertEquals(listOf("algs", "拉斯维加斯"), terms)
        assertTrue(WeiboChatSearch.matchesExcerpt("拉斯维加斯的 ＡＬＧＳ 决赛", terms))
        assertFalse(WeiboChatSearch.matchesExcerpt("ALGS 赛区资格赛", terms))
        assertFalse(WeiboChatSearch.matchesExcerpt("摄影日记里的硅酮软膏", terms))
    }

    @Test fun requiresSearchHeaderAndCollectsOnlyPostBodies() {
        fun node(label: String, id: String, y: Int) = UiElement("@e", "TextView", label, 1, 1,
            listOf(0, y, 900, y + 30), "com.sina.weibo:id/" + id, false, false, false)
        val body = node("这是结果中的博文正文内容", "contentTextView", 430)
        val screen = ScreenSnapshot("s", "com.sina.weibo", listOf(
            node("城市 马拉松", "tv_search_result_title_text", 50), node("综合", "search_item_text", 120).copy(states = setOf("selected")),
            node("推荐搜索与热门词条", "recommend", 280), body, body
        ), 1080, 2300)
        assertTrue(WeiboChatSearch.verified(screen, "城市 马拉松"))
        assertFalse(WeiboChatSearch.verified(screen, "其他赛事"))
        assertFalse(WeiboChatSearch.verified(screen.copy(protected = true), "城市 马拉松"))
        assertEquals(listOf(body.name), WeiboChatSearch.posts(screen))
    }

    @Test fun mixedTopicCanBeInTwoDifferentPartsOfTheSamePost() {
        val results = WeiboChatSearch.Results("algs札幌冠军赛")
        val post = "ALGS Y4冠军赛确认在日本札幌举行"
        results.addPage(listOf(post, post, "ALGS 的其他赛区冠军赛", "日本札幌的观赛指南", "电影节广告"))
        assertEquals(1, results.count)
        assertEquals(0, results.partialCount)
        assertTrue(post in results.evidence())
        assertFalse(WeiboChatSearch.matchesExcerpt("上海的一所大学", listOf("上海大学")))
        assertFalse(WeiboChatSearch.matchesExcerpt("ALGS 哈哈哈", listOf("algs", "哈哈哈哈")))
    }

    @Test fun partialMatchesKeepOriginalQueryAndNeedTwoDifferentCorroboratingBodies() {
        val results = WeiboChatSearch.Results("algs札幌冠军赛斯")
        val first = "ALGS札幌冠军赛现场记录"
        val second = "札幌冠军赛的 ALGS 比赛日程"
        val separate = "ALGS冠军赛今年在札幌举行"
        results.addPage(listOf(first, first, separate, "ALGS 的冠军赛", "札幌旅游广告"))
        assertEquals(0, results.count)
        results.addPage(listOf(second))
        assertEquals(3, results.partialCount)
        assertTrue("微博 · algs札幌冠军赛斯" in results.evidence())
        assertTrue("末字「斯」未核实" in results.evidence())
        assertFalse("旅游广告" in results.evidence())
        results.addPage(listOf("ALGS札幌冠军赛斯 完整匹配正文", first))
        assertEquals(4, results.count)
        assertEquals(3, results.partialCount)
    }
}
