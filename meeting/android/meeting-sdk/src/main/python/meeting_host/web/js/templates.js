/* Scene templates and per-meeting settings snapshots. */
function workspaceTemplateForm() {
  const t = workspaceTemplates.find((t) => t.id === el("workspace-template").value);
  if (!t) return;
  workspaceTemplateEdit = t.id;
  el("template-name").value = t.name;
  el("template-auto").checked = t.auto_refine;
  el("template-profile").value = t.refinement_profile_id || "";
  el("template-summary").value = t.summary_instructions;
  el("delete-template").disabled = t.builtin;
  el("template-status").textContent = t.builtin
    ? "内置模板可新建副本后修改。"
    : "修改模板不会追溯更改已应用的会议。";
}
async function workspaceLoadTemplates() {
  const selected = el("workspace-template").value;
  workspaceTemplates = await api("/templates");
  el("workspace-template").replaceChildren();
  for (const t of workspaceTemplates) el("workspace-template").append(new Option(t.name, t.id));
  if (workspaceTemplates.some((t) => t.id === selected)) el("workspace-template").value = selected;
  const profiles = await api("/refinement");
  el("template-profile").replaceChildren(new Option("跟随全局选择", ""));
  for (const p of profiles.profiles) el("template-profile").append(new Option(p.name, p.id));
  workspaceTemplateForm();
}
el("workspace-template").onchange = workspaceTemplateForm;
el("new-template").onclick = () => {
  workspaceTemplateEdit = null;
  el("template-name").value += " 副本";
  el("delete-template").disabled = true;
  el("template-status").textContent = "正在创建新模板。";
};
el("save-template").onclick = workspaceAction(async () => {
  if (workspaceTemplates.find((t) => t.id === workspaceTemplateEdit)?.builtin)
    throw Error("请先点“新建副本”");
  const saved = await api(
    "/templates" + (workspaceTemplateEdit ? "/" + workspaceTemplateEdit : ""),
    requestJSON(workspaceTemplateEdit ? "PUT" : "POST", {
      name: el("template-name").value,
      refinement_profile_id: el("template-profile").value || null,
      auto_refine: el("template-auto").checked,
      summary_instructions: el("template-summary").value,
    }),
  );
  await workspaceLoadTemplates();
  el("workspace-template").value = saved.id;
  workspaceTemplateForm();
  el("template-status").textContent = "模板已保存。";
});
el("delete-template").onclick = workspaceAction(async () => {
  await api("/templates/" + workspaceTemplateEdit, { method: "DELETE" });
  await workspaceLoadTemplates();
});
el("apply-template").onclick = workspaceAction(async () => {
  idle();
  await api(
    "/meetings/" + id + "/settings",
    requestJSON("PUT", { template_id: el("workspace-template").value }),
  );
  await refresh();
  el("template-status").textContent = "已应用到当前会议；录音和精修开始时使用此快照。";
});
