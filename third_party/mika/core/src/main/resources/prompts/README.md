# prompts/:随 App 发布的提示词

模型看到的固定文字都在这里,代码里不再写成字符串;统一经 `core.prompt.Prompts` / `core.prompt.Feedback` 读取。
文字里的 `{{名字}}` 由读取它的代码填(上限、可选值、屏上的名字等),漏填直接报错。

## 分层

| 目录 | 是什么 | 进哪里 | 谁读 |
|---|---|---|---|
| `system/identity.md` | P0 身份与姿态:替用户办事、在用户授权内、先看后做、不装、不知道就说 | 系统提示词开头 | `SystemPrompt.identity()` |
| `system/principles.md` | P1 通用准则:谁的话算数、怎么办任务、凭依据回答、遇到障碍、找东西、安全;对所有屏、所有能力都成立 | 紧接身份 | `SystemPrompt.identity()` |
| `capabilities/` | P2 能力说明,每种能力一段;`general.md` 是通用工具(报失败、追问、确认)和任务怎么结束的总规则,通用工具一直都在,所以总是拼在准则之后;`groups.md` 说几组工具从哪个入口进(只列本任务给了入口的);`handover.md` 是有屏的组展开后才有的交给用户、下单发送前的核对;手机、网页、沙箱各段只在那一组展开后才拼;`app-access.md` 是应用访问的规则,只在用户禁用了 App 时接在手机一段后面 | 系统提示词 | `SystemPrompt.assemble()` |
| `tools/` | P3 每个工具一份说明(工具定义里的 description):怎么用、参数、结果怎么处理;几个工具共用一份模板时在工具里写明 `promptFile` | 工具定义 | 工具自己 |
| `entries/` | 入口工具在它那一组收着时的短说明(一两句:做什么、调用后还会有哪些工具);展开后换回 `tools/` 的全文,参数不变 | 工具定义 | `ToolRegistry` |
| `feedback/` | 工具结果和主循环给模型的反馈:提示、错误说明(`failure.<错误码>`)、提醒、参考资料的来源标签;每条一个键 | 工具结果、上下文消息 | 工具、`AgentLoop` |
| `roles/` | 内部角色的提示词:核查、描述屏幕、定位、记忆提取、路线匹配、技能草稿、示范整理成技能、App 知识补充、交出回答后的结局判定 | 各角色自己的请求 | 各角色 |

App、站点知识和技能不在这里,见 `knowledge/`、`skills/`。

## 放哪里

- 越往上越通用、越中立:system/ 不提具体工具、App 或技术;只对某种能力成立的写进那段能力说明。
- 和某个工具绑在一起的话(怎么用、参数含义、结果怎么处理)写进 `tools/<工具>.md`,不要写进 system/。
- 只在有某种能力时才成立的规则写进 `capabilities/<能力>.md`,和那组工具同一个开关。
  段落里有一行要用到别的能力的工具时,行首写 `<!-- needs: 工具名 -->`:那个工具提供了才留下这一行。
- `system/` 不点任何工具名(由测试检查;tap、back 这类本身是英文词的不算)。
- 工具结果里的说明文字写进 `feedback/tools.md`,主循环自己发的写进 `feedback/loop.md`;键在两个文件里唯一,每个键都要有代码在用。
- 每条反馈自成一体:首尾不留空白,代码不拿它和别的字拼(`+` 或字符串模板);要组合就在模板里写好分隔,用 `{{占位符}}` 把另一条填进去。
- 一个任务内系统提示词只在展开一组工具时变(缓存因此每组至多失效一次);每轮会变的东西(步数、时间、在哪个 App)不进这里的固定文字。

## 格式

- `tools/`、`system/`、`capabilities/`、`roles/`:一个文件就是一段原文,读取时去掉首尾空白。
- `feedback/*.md`:开头可以有一段 `<!-- 说明 -->`;每条以单独一行 `## 键` 开头(键只用小写字母、数字、`_`、`.`),
  下面到下一条之前是原文,可以多行,首尾空行不算(所以分隔用的换行要写在模板中间)。

## 大小

各层的字节数(UTF-8)写在金样 `core/src/test/resources/prompt-surface/sizes.txt` 里,只作参考、不设硬上限:
每次改动后的增长都出现在提交的差异里。加内容前先看能不能删掉旧的;明显变大时在提交里说明为什么。

## 改了之后

模型看到的全部文字有金样:`core/src/test/resources/prompt-surface/`(系统提示词、工具定义、反馈、角色)和
`loop-snapshots/`(假模型回放的消息序列)。有意的改动用 `./gradlew :core:test -PupdateSnapshots` 重新生成,
在提交里一起审;改动会影响模型行为的,先跑评测对比。运行记录里的 `prompt_version` 是本任务系统提示词加工具定义的摘要,
对比评测结果时据此区分提示词版本。

## 工具名

- 手机、网页各一套屏幕工具(`phone_*`、`web_*`,动词与参数一样);同名动词共用 `tools/<动词>.md` 模板,模板里的 `{{动词}}` 换成本套的工具名;
  某块屏要自己的说明时写 `tools/<屏>_<动词>.md`。
- `capabilities/general.md` 不点名任何能力的工具(由测试检查);「选哪条渠道」写在 `channels.md`,只在有两块以上的屏时拼进去。
- 知识、技能、提示词里提到的工具名都必须是现在登记的(由测试检查)。改名前的旧名由 `LegacyNames` 按规则换成新名,
  只用于读旧东西(存下的路线、运行记录、个人技能与做法),模型调用旧名时只提示新名、不替它执行。

## English

This directory holds the fixed text sent to the model. Code reads it through `core.prompt.Prompts` and `core.prompt.Feedback`. The caller fills every `{{placeholder}}`, including limits, options and screen labels. An unfilled placeholder raises an error.

### Directory layout

| Location | Content and consumer |
| --- | --- |
| `system/identity.md` | P0 identity and conduct. Act on the user's behalf within authorization, observe first, and disclose uncertainty. Opens `SystemPrompt.identity()`. |
| `system/principles.md` | P1 rules shared by all screens and capabilities, covering authority, task execution, evidence, obstacles, search and safety. Follows identity. |
| `capabilities/` | P2 capability-specific rules assembled by `SystemPrompt.assemble()`. General tool and completion rules always follow the principles. Group entry points reflect available capabilities. Handover, phone, web and sandbox sections appear when their groups open. App-access rules accompany the phone section when an app is disabled. |
| `tools/` | P3 tool descriptions, including usage, parameters and the meaning of results. Several tools can share a template. The registry attaches the text to the tool definition. |
| `entries/` | Short descriptions of collapsed tool groups. Expanding a group restores the full tool text while preserving its parameters. |
| `feedback/` | Keyed messages for tool results and the agent loop, including hints, errors, reminders and source labels. |
| `roles/` | Instructions for verification, screen description, location, memory extraction, route matching, skill drafting and learning from demonstrations, app knowledge and outcome review. Each role uses its own request. |

App and site knowledge lives under `knowledge/`. Skills live under `skills/`.

### Editing rules

- Keep broadly applicable rules in `system/`. Place capability-specific rules in `capabilities/<name>.md` and tool-specific instructions in `tools/<name>.md`. Lines gated on another tool start with `<!-- needs: tool_name -->`.
- System prompts avoid tool names. Tests allow ordinary English words that also name tools, such as “tap” or “back”.
- Put tool-result text in `feedback/tools.md` and loop feedback in `feedback/loop.md`. Each key is unique across both files and has a caller. Write self-contained messages without leading or trailing whitespace. Use separators and `{{placeholders}}` in the template when combining messages.
- A task's system prompt changes when tool groups open, preserving cache reuse. Per-step details such as the current app or step count belong in the changing context.

### Formats, sizes and verification

Each file under `tools/`, `system/`, `capabilities/` or `roles/` is one text block, trimmed on read. A feedback file can start with an HTML comment. Each message begins with `## key` on its own line. Keys use lowercase letters, digits, underscores and dots. Text can span several lines. Deliberate separators belong inside the message template.

UTF-8 sizes are recorded in `core/src/test/resources/prompt-surface/sizes.txt`. They show changes for review. When adding text, check for material that can be removed and explain significant increases.

The prompt-surface golden files cover system text, tools, feedback and roles. The loop snapshots record message sequences with a fake model. In the upstream checkout, regenerate intentional changes with `./gradlew :core:test -PupdateSnapshots`, review the snapshot diff, and compare evaluations for behavior changes. A run's `prompt_version` hashes its system prompt and tool definitions.

### Tool names

Phone and web screen tools use the same verbs and parameters with `phone_*` and `web_*` prefixes. They share `tools/<verb>.md` templates with substituted names. Screen-specific descriptions live in `tools/<screen>_<verb>.md`. Channel selection belongs in `channels.md` and appears when at least two screens are available. Tests check that knowledge, skills and prompts reference registered tools. `LegacyNames` translates old names in saved routes, skills and demonstrations. A model calling an old name receives the current name to use.
