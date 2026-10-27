# 分支与 PR 导航

更新：2026-10-27。共享前端只保留一条开发分支，不再按移植功能堆叠分支。

## 日常使用

| 分支 | 用途 |
| --- | --- |
| `main` | 跟随 [上游 main](https://github.com/yydhYYDH/ClawMate-Android/tree/main)，本轮核对为 `13708b4`；不直接堆入待评审功能 |
| `codex/shared-frontend` | 唯一共享前端开发入口，包含 SH-01～14：宿主、共用能力、图片/记忆/工作流、会议、提醒、视觉问答及图库恢复 |

后续相关移植直接在共享开发分支累积小提交，统一更新同一个上游整合 PR；只有独立且可能破坏稳定性的试验才另开临时分支。

## 评审入口

唯一评审入口：[上游 #5：整合 ArkUI-X Android 移植与图库恢复](https://github.com/yydhYYDH/ClawMate-Android/pull/5)。直接以 `yydhYYDH/ClawMate-Android:main` 为基底，使用 fork 的 `codex/shared-frontend` 为内容分支。
原 fork Draft #3～7 已关闭，由该整合 PR 替代，只供历史查询。上游 Compose [#4](https://github.com/yydhYYDH/ClawMate-Android/pull/4) 已合并。

共享分支已合入上游 `13708b4`，保留上游新增的三份工具链脚本；应用源码与 SH-14 提交 `0108ca9` 相同。
不改变正式应用包名或迁移用户数据。无冲突（MERGEABLE）与 GitHub Auto-merge 是不同状态：后者还需要上游开启功能，并满足写权限、审核和必需检查。
GitHub 本轮返回 `mergeable=true`、`mergeable_state=clean`，没有合并冲突。当前账号只能管理 fork，没有上游写权限；合并及启用 Auto-merge 的请求均被 GitHub 以权限不足拒绝（`EnablePullRequestAutoMerge`），Auto-merge 未启用，需上游维护者操作。不得通过削弱检查或伪造成功状态绕过这些条件。

## 历史追溯

清理前 13 条功能分支及完整提交号保存在 [分支归档](archive/BRANCHES_2026-10-27.json)。
原始共享提交由标签 `archive/shared-before-consolidation-20261027` 保留，Compose 评审父链由 `archive/compose-review-20261027` 保留。
删除的只是被替代的分支引用，不删除提交或历史 PR。本地与 fork 远端均仅保留上述两条日常分支。
