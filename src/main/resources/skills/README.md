# Skill 目录说明

本文件是给开发者看的目录说明，**不会被运行时代码注入给大模型**。

运行时真正给 `SkillCliExecutorService` 使用的是：

1. 优先读取飞书 CLI 内置原生 Skill：`lark-cli skills read <skill-name>`；
2. 再追加本目录里的项目补充规则；
3. 如果原生 Skill 读取失败，才回退到本地补充规则。

也就是说：`skills/lark/*.md` 现在不是飞书原生 Skill 全文，而是项目补充规则和兜底说明。飞书 CLI 的真实能力说明以 `lark-cli skills read` 和 `lark-cli <domain> --help` 为准。

```text
src/main/resources/skills/
├── README.md            开发者目录说明（本文件，不注入模型）
├── lark/                第一层：飞书项目补充规则
│   ├── im.md  base.md  docs.md  calendar.md  vc.md  minutes.md  note.md
│   ├── contact.md  approval.md  attendance.md  drive.md  wiki.md
│   └── markdown.md  mindnotes.md  whiteboard.md
└── business/            第二层：业务能力 Skill
    ├── _template.md     新增业务域的模板
    └── ecommerce-agent.md
```

---

## 一、为什么要分两层

如果把「飞书 CLI 原生命令说明」「本项目特殊约束」和「业务规则怎么判断」写在同一个文档里，会出现三个问题：

1. **职责混在一起**：改一条业务规则，要动到描述 CLI 命令的文档。
2. **上下文变长**：外层规划器会把大量 CLI 命令细节读进去，关键约束被淹没。
3. **共享变难**：飞书命令细节是所有业务域共用的，业务规则只属于某个业务。

分层后，每一层只回答一个问题：

| 层 | 目录 | 回答的问题 | 谁读取 | 什么时候改 |
| --- | --- | --- | --- | --- |
| 第一层：飞书项目补充规则 | `skills/lark/` | 本项目对该飞书业务域的补充约束和兜底说明 | `SkillCliExecutorService.readSkill(domain)` | 项目约束变化时 |
| 第二层：业务能力 | `skills/business/` | 业务需求**该走哪条工具链**、怎么判断 | `AgentPlannerService` 构造外层 prompt 时 | 业务规则变化时 |

---

## 二、第一层：飞书项目补充规则（`skills/lark/`）

**放什么：**

- 本项目固定约束，例如「默认 bot 身份」「群聊回复要引用原消息」「结果要 @ 发送人」；
- 原生 Skill 没写但本项目必须遵守的兜底规则；
- 原生 Skill 读取失败时的最小命令索引。

**不放什么：**

- 飞书 CLI 原生 Skill 全文。原生内容运行时通过 `lark-cli skills read` 读取，不在这里手工复制。
- 业务规则。例如「电商订单优先走 MCP」属于第二层，不写在这里。
- 具体业务系统的字段、表名、SQL。

**命名规则：** 文件名必须等于项目业务域名称，且必须出现在 `FEISHU_CLI_ALLOWED_DOMAINS` 配置里。

**原生 Skill 映射：**

| 项目业务域 | 飞书 CLI 原生 Skill |
| --- | --- |
| `im` | `lark-im` |
| `base` | `lark-base` |
| `docs` / `mindnotes` | `lark-doc` |
| `calendar` | `lark-calendar` |
| `vc` / `minutes` / `note` | `lark-meeting` |
| `contact` | `lark-contact` |
| `approval` | `lark-approval` |
| `attendance` | `lark-attendance` |
| `drive` | `lark-drive` |
| `wiki` | `lark-wiki` |
| `markdown` | `lark-markdown` |
| `whiteboard` | `lark-whiteboard` |

| 文件名 | 业务域 | 典型场景 |
| --- | --- | --- |
| `im.md` | `im` | 群聊、发消息、查群成员 |
| `base.md` | `base` | 多维表格读写 |
| `docs.md` | `docs` | 云文档 |
| `calendar.md` | `calendar` | 日程 |
| `attendance.md` | `attendance` | 考勤结果查询 |
| `approval.md` | `approval` | 审批实例与任务 |

**读取路径：** `SkillCliExecutorService` 会先执行 `lark-cli skills read <原生Skill>`，再读取 `ClassPathResource("skills/lark/" + domain + ".md")` 作为项目补充规则。主业务域优先、其余白名单业务域追加，让复合任务可以跨域执行。

---

## 三、第二层：业务能力（`skills/business/`）

**放什么：**

- 业务意图识别规则，例如「用户提到订单、库存、退款、GMV 时算电商需求」；
- 工具链路由规则，例如「电商数据走 `ecommerce.call_tool`，不要走 `cli.run_skill`」；
- 该业务**不要**做什么，例如「不要让 LLM 自己编造电商数据」「不要让 LLM 直接写 SQL」。

**不放什么：**

- lark-cli 命令细节（属于第一层）；
- 任何会过期的临时数据。

**读取路径：** `ClassPathResource("skills/business/<name>.md")`，由 `AgentPlannerService` 在构造外层规划 prompt 时读取，作为业务知识注入。

---

## 四、两层之间的边界规则

一句话：

> **飞书原生 Skill 回答「飞书 CLI 原生能力是什么」，第一层本地补充规则回答「本项目执行时还有哪些约束」，第二层业务 Skill 回答「该不该执行、走哪条路」。**

具体判断方式：

| 你写的内容 | 应该放哪层 |
| --- | --- |
| `lark-cli base +table-list --help` 的用法 | 飞书原生 Skill / CLI help |
| 「多维表格用 `base` 域」 | 飞书原生 Skill / 本地补充规则 |
| 「用户说本群，指当前 `sourceChatId`」 | 本地补充规则 |
| 「电商退款分析优先用 MCP 拿结构化数据」 | 第二层 |
| 「查低库存要传 `threshold` 参数」 | 第二层 |
| 「审批实例和考勤不要混在一个域」 | 第一层 |

外层 `AgentPlannerService` 只负责**判断意图 + 选粗粒度工具 + 传最小参数**，不负责描述飞书 API 细节。具体飞书命令由 lark-cli 的 `--help` 和第一层 Skill 兜住。

---

## 五、新增一个业务域的步骤

1. 在 `skills/business/` 下复制 `_template.md`，改名为业务名。
2. 只写业务规则和工具链路由，**不要**复制 lark-cli 命令。
3. 如果该业务需要新的飞书域，先确认 `lark-cli skills list` 是否有对应原生 Skill，再在 `SkillCliExecutorService.nativeSkillName` 增加映射，并加入 `FEISHU_CLI_ALLOWED_DOMAINS`。
4. 在 `AgentPlannerService` 里按同样方式读取该业务 Skill，或在需要时改成按业务名动态加载。
5. 用一条真实飞书消息跑一遍，确认模型选对了工具链。

---

## 六、验收标准

分层整理完成的标准是这三条：

1. 只改业务规则时，不需要动 `skills/lark/` 下任何文件。
2. 只改 lark-cli 用法时，不需要动 `skills/business/` 下任何文件。
3. 新同学看这个 README，能说出「原生 Skill、本地补充规则、业务 Skill」三者边界。
