package com.clawmate.platform.mika.memory

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import com.wangzan101.mika.core.memory.*
import com.wangzan101.mika.core.skills.*
import kotlinx.coroutines.*

/** Android management adapter for Mika's MemoryStore. All writes use the original store. */
object MikaMemoryUi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun show(activity: Activity) = load(activity) { runtime -> menu(activity, runtime) }
    private fun dialog(a: Activity) = AlertDialog.Builder(a, com.clawmate.platform.R.style.ClawMateDialog)

    private fun load(activity: Activity, next: (MikaMemoryRuntime) -> Unit) {
        scope.launch {
            try {
                val runtime = withContext(Dispatchers.IO) { MikaMemoryRuntime.of(activity).also { it.store.reloadIfNeeded(); it.tasks.reloadIfNeeded() } }
                if (!activity.isFinishing && !activity.isDestroyed) next(runtime)
            } catch (_: Exception) { toast(activity, "记忆暂不可用，请解锁手机后重试") }
        }
    }

    private fun menu(a: Activity, r: MikaMemoryRuntime) {
        dialog(a).setTitle("Mika 记忆")
            .setItems(arrayOf("用户记忆（${r.store.items.value.size} 条）", "任务摘要（${r.tasks.items.value.size} 条）",
                "记忆与学习开关", "添加记忆", "撤销最近一次变更", "清除全部 Mika 记忆", "用户画像", "个人技能与学习草稿")) { _, index -> when (index) {
                0 -> memories(a, r)
                1 -> tasks(a, r)
                2 -> switches(a, r)
                3 -> edit(a, r, null)
                4 -> change(a, r) { val token = r.lastUndo; check(token != null && r.store.undo(token)) { "undo_unavailable" } }
                5 -> dialog(a).setTitle("清除 Mika 记忆？")
                    .setMessage("删除用户记忆、画像、任务摘要、个人技能及草稿和归档。聊天原文、运行记录和操作路线仍保留；重新使用后可以产生新条目。")
                    .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> change(a, r) { r.clear() } }.show()
                6 -> profiles(a, r)
                7 -> skills(a, r)
            } }.setNegativeButton("关闭", null).show()
    }

    private fun switches(a: Activity, r: MikaMemoryRuntime) {
        val s = r.settings.switches.value
        val labels = arrayOf("使用记忆（关闭后新任务不读取或写入）", "自动记住用户说过的事", "保存和检索任务摘要", "允许在明确要求时保存模型归纳（标注来源）", "从办成的任务学习技能（先生成草稿）")
        dialog(a).setTitle("记忆开关")
            .setMultiChoiceItems(labels, booleanArrayOf(s.useMemory, s.autoRemember, s.rememberTasks, s.inferMemories, s.learnSkills)) { _, index, checked ->
                scope.launch {
                    try { withContext(Dispatchers.IO) { r.change { r.settings.update { old -> when (index) {
                        0 -> old.copy(useMemory = checked)
                        1 -> old.copy(autoRemember = checked)
                        2 -> old.copy(rememberTasks = checked)
                        3 -> old.copy(inferMemories = checked)
                        else -> old.copy(learnSkills = checked)
                    } } } } } catch (_: Exception) { toast(a, "开关保存失败，请重新打开核对") }
                }
            }.setPositiveButton("完成") { _, _ -> menu(a, r) }.show()
    }

    private fun memories(a: Activity, r: MikaMemoryRuntime) {
        val items = r.store.items.value.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        if (!r.store.available || r.file.brokenNotice.value != null) {
            dialog(a).setTitle("记忆存储提示").setMessage(if (!r.store.available) "记忆暂时无法解密，原文件未被覆盖。请解锁后重试。" else "有一份记忆无法读取，已另存保留。当前列表只显示可用条目。")
                .setPositiveButton("知道了", null).show()
        }
        if (items.isEmpty()) { dialog(a).setTitle("用户记忆").setMessage("暂无记忆。可在云端聊天中说“请记住……”，或在此手动添加。")
            .setPositiveButton("添加") { _, _ -> edit(a, r, null) }.setNegativeButton("返回") { _, _ -> menu(a, r) }.show(); return }
        dialog(a).setTitle("用户记忆 · 点选管理")
            .setItems(items.map { (if (it.pinned) "置顶 · " else "") + (if (it.status == MemoryStatus.pending) "待批准 · " else "") + it.content }.toTypedArray()) { _, index -> detail(a, r, items[index]) }
            .setPositiveButton("添加") { _, _ -> edit(a, r, null) }.setNegativeButton("返回") { _, _ -> menu(a, r) }.show()
    }

    private fun detail(a: Activity, r: MikaMemoryRuntime, m: MemoryItem) {
        val source = when (m.source.type) { "manual" -> "用户手动填写"; "inferred" -> "模型归纳，用户未亲口说过"; else -> "用户原话" }
        dialog(a).setTitle(if (m.status == MemoryStatus.pending) "待批准的做法" else "记忆详情")
            .setMessage("${m.content}\n\n来源：$source\n原话：${m.source.quote.orEmpty()}\n使用次数：${m.useCount}" +
                if (m.status == MemoryStatus.pending) "\n批准后才会用于任务。" else "")
            .setPositiveButton("管理") { _, _ ->
                val choices = listOf("编辑", "删除", if (m.pinned) "取消置顶" else "置顶") + if (m.status == MemoryStatus.pending) listOf("批准做法") else emptyList()
                dialog(a).setTitle("管理记忆").setItems(choices.toTypedArray()) { _, i -> when (i) {
                    0 -> edit(a, r, m)
                    1 -> change(a, r) { r.note(listOf(r.store.forget(m.id).getOrThrow())) }
                    2 -> change(a, r) { r.store.setPinned(m.id, !m.pinned) }
                    3 -> change(a, r) { check(r.store.approve(m.id)) }
                } }.setNegativeButton("取消", null).show()
            }.setNegativeButton("返回") { _, _ -> memories(a, r) }.show()
    }

    private fun edit(a: Activity, r: MikaMemoryRuntime, m: MemoryItem?) {
        val input = EditText(a).apply { setText(m?.content.orEmpty()); hint = "200 字以内的事实、偏好或做法" }
        val dialog = dialog(a).setTitle(if (m == null) "添加记忆" else "编辑记忆").setView(input)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank() || text.length > MemoryStore.MAX_CONTENT) { input.error = "请输入 1–200 字"; return@setOnClickListener }
            dialog.dismiss()
            if (m == null) dialog(a).setTitle("记忆类型")
                .setItems(arrayOf("用户事实", "个人偏好", "以后采用的做法（添加后需批准）")) { _, i -> change(a, r) {
                    r.note(listOf(r.store.add(MemoryKind.entries[i], text, emptyList(), MemorySource("manual", atMs = System.currentTimeMillis())).getOrThrow()))
                } }.setNegativeButton("取消", null).show()
            else change(a, r) { r.note(listOf(r.store.update(m.id, text, null, MemorySource("manual", atMs = System.currentTimeMillis())).getOrThrow())) }
        } }
        dialog.show()
    }

    private fun tasks(a: Activity, r: MikaMemoryRuntime) {
        val items = r.tasks.items.value.sortedByDescending { it.createdAt }
        if (items.isEmpty()) { dialog(a).setTitle("任务摘要").setMessage("暂无任务摘要。开启后会记录新的手机任务，可在云端聊天中查询。")
            .setPositiveButton("返回") { _, _ -> menu(a, r) }.show(); return }
        dialog(a).setTitle("任务摘要").setItems(items.map { it.goal }.toTypedArray()) { _, i ->
            val t = items[i]
            dialog(a).setTitle(t.goal).setMessage(t.conclusion + "\n\n" + t.steps.joinToString("\n") { it.label })
                .setPositiveButton("删除") { _, _ -> change(a, r) { check(r.tasks.forget(t.id)) } }
                .setNegativeButton("返回") { _, _ -> tasks(a, r) }.show()
        }.setNegativeButton("返回") { _, _ -> menu(a, r) }.show()
    }

    private fun profiles(a: Activity, r: MikaMemoryRuntime) {
        val items = runCatching { r.profile.snapshot() }.getOrElse { toast(a, "画像暂时无法读取"); return }
        dialog(a).setTitle("用户画像 · 下次任务生效")
            .setItems(items.map { it.text }.toTypedArray()) { _, i ->
                val entry = items[i]
                dialog(a).setTitle(entry.text).setItems(arrayOf("编辑", "删除")) { _, action ->
                    if (action == 1) change(a, r) { check(r.profile.delete(entry.text) is ProfileChange.Applied) }
                    else profileEdit(a, r, entry)
                }.setNegativeButton("返回", null).show()
            }.setPositiveButton("添加") { _, _ -> profileEdit(a, r, null) }
            .setNegativeButton("返回") { _, _ -> menu(a, r) }.show()
    }
    private fun profileEdit(a: Activity, r: MikaMemoryRuntime, entry: ProfileEntry?) {
        val input = EditText(a).apply { setText(entry?.text.orEmpty()); hint = "一句话描述自己，最多160字" }
        val d = dialog(a).setTitle("编辑画像").setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isBlank() || value.length > 160) { input.error = "请输入1–160字"; return@setOnClickListener }
            d.dismiss(); change(a, r) {
                val result = if (entry == null) r.profile.add(value) else r.profile.replace(entry.text, value)
                check(result is ProfileChange.Applied) { (result as ProfileChange.Rejected).code }
            }
        } }; d.show()
    }
    private fun skills(a: Activity, r: MikaMemoryRuntime) {
        val drafts = r.skills.drafts().entries.toList()
        val saved = r.skills.list()
        val labels = drafts.map { "待收下 · ${it.value.name}" } + saved.map {
            "${when (it.status) { SkillStatus.trial -> "试用"; SkillStatus.suspended -> "停用"; else -> "在用" }} · ${it.name}"
        }
        if (labels.isEmpty()) {
            dialog(a).setTitle("个人技能").setMessage(r.learningNotice.ifBlank { "还没有个人技能。办成多步任务后会尝试起草；也可以在聊天里明确要求保存一份做法。草稿收下后才会用于任务。" })
                .setPositiveButton("返回") { _, _ -> menu(a, r) }.show(); return
        }
        dialog(a).setTitle("个人技能与学习草稿").setItems(labels.toTypedArray()) { _, i ->
            if (i < drafts.size) skillDetail(a, r, drafts[i].value, drafts[i].key)
            else skillDetail(a, r, saved[i - drafts.size], null)
        }.setNegativeButton("返回") { _, _ -> menu(a, r) }.show()
    }
    private fun skillDetail(a: Activity, r: MikaMemoryRuntime, s: PersonalSkill, draftId: String?) {
        val choices = if (draftId != null) arrayOf("收下并试用", "编辑后收下", "不要这份草稿") else
            arrayOf("编辑", if (s.status == SkillStatus.suspended) "恢复使用" else "停用", "删除", "恢复历史版本")
        dialog(a).setTitle(s.name).setMessage(s.markdown + "\n\n来源：" + (s.learnedFrom ?: if (s.createdByUser) "用户要求保存" else "手动导入") +
            "\n最近结果：" + s.outcomes.joinToString()).setPositiveButton("管理") { _, _ ->
            dialog(a).setTitle("管理技能").setItems(choices) { _, index ->
                if (draftId != null) when (index) {
                    0 -> change(a, r) { check(r.skills.approve(draftId) != null) }
                    1 -> skillEdit(a, r, s, draftId)
                    else -> change(a, r) { r.skills.reject(draftId) }
                } else when (index) {
                    0 -> skillEdit(a, r, s, null)
                    1 -> change(a, r) { r.skills.update(s.name) { it.copy(status = if (it.status == SkillStatus.suspended) it.restoredStatus() else SkillStatus.suspended) } }
                    2 -> change(a, r) { r.skills.remove(s.name) }
                    else -> {
                        val revisions = r.skills.revisions(s.name)
                        dialog(a).setTitle("恢复历史版本").setItems(revisions.map { java.util.Date(it.atMs).toString() + " · " + it.operation }.toTypedArray()) { _, i ->
                            change(a, r) { check(r.skills.restore(revisions[i].id)) }
                        }.setNegativeButton("取消", null).show()
                    }
                }
            }.setNegativeButton("取消", null).show()
        }.setNegativeButton("返回") { _, _ -> skills(a, r) }.show()
    }
    private fun skillEdit(a: Activity, r: MikaMemoryRuntime, s: PersonalSkill, draftId: String?) {
        val input = EditText(a).apply { setText(s.markdown); minLines = 8; maxLines = 14; textSize = 14f }
        val d = dialog(a).setTitle("编辑技能").setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val md = PersonalSkills.withName(input.text.toString(), s.name)
            val tools = com.wangzan101.mika.core.assembly.toolDispatcher(MemoryOnlyScreen, skills = r.catalog)
            val problem = SkillDrafter.validate(md, tools.index.names + "do_steps", tools.index.nameOf(com.wangzan101.mika.core.tool.Verb.RunBundledScript), r.catalog.runnableScripts())
            if (problem != null) { input.error = "格式未通过：$problem"; return@setOnClickListener }
            d.dismiss(); change(a, r) {
                if (draftId != null) check(r.skills.approve(draftId, md) != null)
                else { check(r.skills.get(s.name)?.markdown == s.markdown); r.skills.put(s.copy(markdown = md, status = SkillStatus.trial, outcomes = emptyList())) }
            }
        } }; d.show()
    }

    private fun change(a: Activity, r: MikaMemoryRuntime, action: () -> Unit) {
        toast(a, "正在保存…")
        scope.launch {
            try { withContext(Dispatchers.IO) { r.change(action) }; if (!a.isDestroyed && !a.isFinishing) menu(a, r) }
            catch (e: Exception) { toast(a, when ((e as? MemoryRejected)?.code) { "sensitive" -> "内容包含敏感信息，未保存"; "memory_full" -> "记忆已满，请先删除不再需要的条目"; else -> "操作未完成，请重新打开记忆核对" }) }
        }
    }
    private fun toast(a: Activity, text: String) { if (!a.isDestroyed) Toast.makeText(a, text, Toast.LENGTH_SHORT).show() }
}
