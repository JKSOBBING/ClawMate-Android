# 分支与 PR 导航

更新：2026-10-27。以GitHub实际PR状态为准；下面的“共享1～4”是评审顺序，
与分支名里的历史`pr-04～06`编号不同。旧编号保留，避免破坏堆叠PR的比较基底。

## 先看哪一个

- 看当前已经验证的完整共享版：`codex/meeting-sdk-integration`，功能检查点`7d39bc8`。
- 看新增视觉问答：`codex/workflow-vlm-qa`，已完成SH-13本机图片问答接线与受控验证，尚未递交PR；真实模型质量等边界见迁移日志。
- 看等待上游评审的Compose基线：[上游#4](https://github.com/yydhYYDH/ClawMate-Android/pull/4)。
- 看共享版分层改动：按下面4个Draft PR顺序阅读。

## 正在评审的PR

| 顺序 | PR标题 | 内容分支 | 比较基底 |
| --- | --- | --- | --- |
| 前置 | [Compose 基线：主要页面对齐、工作流图编辑与文件预览](https://github.com/yydhYYDH/ClawMate-Android/pull/4) | `codex/pr-03-compose-ui-upstream` | 上游`main` |
| 共享1/4 | [ArkUI-X 宿主、导航与 OCR/TTS](https://github.com/JKSOBBING/ClawMate-Android/pull/3) | `codex/pr-04-shared-frontend-host` | `codex/pr-03-compose-ui-workflows` |
| 共享2/4 | [共用图片、语音与工作流引擎](https://github.com/JKSOBBING/ClawMate-Android/pull/4) | `codex/pr-05-shared-android-engines` | 共享1的分支 |
| 共享3/4 | [图片聊天、记忆同步与工作流接线](https://github.com/JKSOBBING/ClawMate-Android/pull/5) | `codex/pr-06-shared-feature-integration` | 共享2的分支 |
| 共享4/4 | [会议、自动摘要、采集提醒与构建校验](https://github.com/JKSOBBING/ClawMate-Android/pull/6) | `codex/meeting-sdk-integration` | 共享3的分支 |

同号PR属于不同仓库，例如“上游#4”和“fork#4”是两个PR。
共享4的源码已包含前面所有层；PR的Files changed只显示相对上一层的增量。
这些PR不能独立合并：前置进入上游后，需核对最新main并整理提交父链/基底再递交上游。
上游另有`build/toolchain-scripts`的工具链PR #3，不属于本共享功能链，本轮不改动它。

## 历史分支

| 分支 | 用途 / 状态 |
| --- | --- |
| `main`（fork） | 当前仍为`7f76afb`，不是最新共享版；上游main已为`483b392` |
| `codex/android-migration` | 旧共享整合检查点`198c8ce`，已停止作为主开发入口 |
| `codex/arkui-x-hybrid` | 与旧共享整合检查点相同的历史分支 |
| `codex/compose-only` | Compose页面迁移截止点`cc23078` |
| `codex/pr-01-compose-memory-workflow` | 早期聊天/记忆/工作流批次；上游PR #2已合并 |
| `codex/pr-02-compose-images-cloud` | 图片/云能力批次；内容已进入上游main，fork PR #1已关闭 |
| `codex/pr-03-compose-ui-workflows` | Compose原提交链；fork PR #2已关闭，由上游PR #4的整理分支接替 |
| `codex/pr-03-compose-ui-upstream` | 为上游PR #4整理父链的Compose分支，与旧分支用途不同 |

本轮保留分支名及提交记录，没有删除、合并或强制推送分支；当前已有PR都继续可追溯。
后续开发分支采用`codex/<具体功能>`，PR标题采用`[共享] <具体功能>`；
只有确实需要堆叠时才增加顺序，标题不再混用Compose、hybrid和历史六批编号。
