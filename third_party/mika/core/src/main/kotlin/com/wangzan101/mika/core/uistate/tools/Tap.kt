package com.wangzan101.mika.core.uistate.tools

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.COORD
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.REF
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SNAP
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.toPx
import com.wangzan101.mika.core.uistate.DESCRIBED_ELEMENT
import com.wangzan101.mika.core.uistate.DESCRIBED_ROLE
import com.wangzan101.mika.core.vision.LOCATE_SCREEN_CHANGE
import com.wangzan101.mika.core.vision.hamming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.described
import com.wangzan101.mika.core.uistate.lastOrSnapshot
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.located
import com.wangzan101.mika.core.uistate.locatedInfo
import com.wangzan101.mika.core.uistate.locator
import com.wangzan101.mika.core.vision.LocateResult
import com.wangzan101.mika.core.uistate.screen
import com.wangzan101.mika.core.uistate.session
import com.wangzan101.mika.core.uistate.withPortRef

internal class Tap(f: ScreenFamily) : ScreenTool(f, "tap") {
    override val verb = Verb.Tap
    override val batchable = true
    override val routable = f.routable
    override val label = "点击"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Tap
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "ref" to str(REF), "snapshotId" to str(SNAP),
            "x" to int(COORD, 0, 999), "y" to int(COORD, 0, 999),
            "target" to str("Short label of what is being tapped (coordinate taps)."),
            DESCRIBED_ELEMENT to str(DESCRIBE_TAP), DESCRIBED_ROLE to str(DESCRIBE_ROLE),
            "clicks" to int("1 taps once (default), 2 double-taps.", 1, 2),
        )) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref(located = true)
        val x = a.coord("x"); val y = a.coord("y")
        val clicks = a.int("clicks", 1, 2) ?: 1
        val target = a.str("target", max = 80)
        // 执行入口已把描述换成编号;走到这里还带着描述说明没经执行入口,不猜
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an element here; use ref+snapshotId")
        if (ref == null && ctx.described && target == null) {
            throw ArgError("target", "required for a coordinate tap after ${family.tool("describe")}: say what you are tapping")
        }
        if (ref != null) {
            if (x != null || y != null) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
        } else {
            if (x == null) throw ArgError("x", "required with y when no ref is given")
            if (y == null) throw ArgError("y", "required with x when no ref is given")
        }
        val twice = clicks == 2
        if (ref != null) {
            if (LOCATED_REF.matches(ref.first)) return tapLocated(ref.first, ref.second, twice, ctx)
            // Inline observation replaces lastSnapshot after navigation. Retain the
            // resolved visible label from before dispatch for the action evidence.
            val label = ctx.lastSnapshot?.elements?.firstOrNull { it.ref == ref.first && !it.secret }?.name
            val result = ctx.withPortRef(ref) { r ->
                ctx.action(if (twice) ctx.screen.doubleTapRef(r!!.first, r.second) else ctx.screen.tapRef(r!!.first, r.second))
            }
            val actual = result.content.argStr("relocatedTo") ?: label
            return if (actual.isNullOrBlank() || result.content["ok"] != JsonPrimitive(true)) result else
                result.with(record = JsonObject(result.record + ("tappedLabel" to JsonPrimitive(actual.take(120)))))
        }
        // ClawMate adaptation: describe is free-form prose and its coordinates can contradict
        // the element tree. Resolve its declared target through Mika's checked Locator before
        // dispatch, reusing the same corroboration and stale-frame checks as find_visually.
        if (ctx.described && target != null && ctx.locator != null) {
            val label = ctx.lastOrSnapshot().elements.map { it.name }.distinct().filter { name ->
                name.isNotBlank() && (target == name || "“$name”" in target ||
                    "\"$name\"" in target || "「$name」" in target || "'$name'" in target)
            }.singleOrNull()
            val result = ctx.locator!!.locate(target, label)
            return when (result) {
                is LocateResult.Found -> tapLocated(result.ref, result.snapshotId, twice, ctx)
                is LocateResult.NotFound -> throw ArgError("target", result.message)
            }
        }
        val s = ctx.lastOrSnapshot()
        return ctx.action(tapAt(ctx, toPx(x!!, s.width), toPx(y!!, s.height), twice))
    }

    private suspend fun tapAt(ctx: ToolContext, x: Int, y: Int, twice: Boolean) =
        if (twice) ctx.screen.doubleTapXY(x, y) else ctx.screen.tapXY(x, y)

    /** 点 find_visually 找到的点:先重新截图,和找到时的画面差太多(或截不到)就不点。 */
    private suspend fun tapLocated(ref: String, snapshotId: String, twice: Boolean, ctx: ToolContext): ToolExecution {
        val (x, y) = locatedPixel(ctx, ref, snapshotId) ?: return refExpired(Feedback["located_stale"], "located_stale")
        val info = ctx.locatedInfo[ref]
        val target = info?.target
        val r = ctx.action(tapAt(ctx, x, y, twice))
        if (target == null || r.content["ok"] != JsonPrimitive(true)) return r
        // 没佐证的点:点了有没有用只能看效果,提示里先说清楚
        val hint = listOfNotNull(Feedback["located.unverified"].takeIf { info.unverified }, (r.content["hint"] as? JsonPrimitive)?.content)
        val extra = mapOf("located" to JsonPrimitive(target)) +
            (if (info.unverified) mapOf("unverifiedPoint" to JsonPrimitive(true)) else emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap())
        val evidence = info.seen?.takeIf { !info.unverified && it.isNotBlank() }
        return r.with(content = JsonObject(r.content + extra), record = if (evidence == null) r.record else
            JsonObject(r.record + ("tappedLabel" to JsonPrimitive(evidence.take(120)))))
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String =
        r.record.argStr("tappedLabel") ?: refTarget(o, r, ctx.lastSnapshot) ?: o.argStr("target") ?: "${o["x"]},${o["y"]}"
}

/**
 * find_visually 找到的点([ref] 是 @vN)此刻的像素位置:先重新截图,和找到时的画面差太多(或截不到)为 null,不往那里点。
 * 换算像素按此刻的一屏(读不到再退回最近一次)。
 */
internal suspend fun locatedPixel(ctx: ToolContext, ref: String, snapshotId: String): Pair<Int, Int>? {
    val (p, hash) = ctx.located.get(ref, snapshotId) ?: return null
    val screen = ctx.screen
    val png = if (screen.isProtected()) null else screen.screenshotPng()
    if (png == null || hamming(ctx.config.vision!!.ops.aHash(png), hash) > LOCATE_SCREEN_CHANGE) return null
    val s = ctx.session.peek() ?: ctx.lastOrSnapshot()
    return toPx(p.x, s.width) to toPx(p.y, s.height)
}

/** 按编号操作时动作清单里写的对象:看图找到的点写它找的是什么,其余写元素的名字,都没有写编号。 */
internal fun refTarget(o: JsonObject, r: ToolExecution, last: ScreenSnapshot?): String? = o.argStr("ref")?.let { ref ->
    (r.content["located"] as? JsonPrimitive)?.content
        ?: last?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref
}

internal const val DESCRIBE_TAP = "Instead of ref+snapshotId: the element's visible text or accessible name, as shown on the screen. It must match exactly one element on the screen right now."
internal const val DESCRIBE_ROLE = "Optional with element: the element's role as shown in the element list (e.g. button, link, textbox), to tell same-named elements apart."
