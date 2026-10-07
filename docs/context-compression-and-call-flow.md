# clean 项目上下文压缩和代码调用顺序

本文档记录 `feishu-admin-ai-bot-clean` 项目里会话记忆优化后的实现方式，以及用户在飞书里发消息后，代码内部的主要执行顺序。

## 1. 优化目标

原来的记忆方式只保存最近 N 条消息，旧消息超过数量后会被直接删除。

优化后改成：

```text
旧消息超过阈值
  ↓
把旧消息压缩进摘要表
  ↓
保留最近几条真实对话
  ↓
下次调用大模型时读取：历史摘要 + 近期对话
```

这样做的好处：

- 长期对话不会无限膨胀；
- 旧信息不会直接丢失；
- 大模型每次读取的上下文更短；
- 群聊和私聊都能保持连续上下文；
- 后续迁移到向量记忆或 LLM 摘要时有明确扩展点。

## 2. 核心配置

配置位置：

```text
src/main/resources/application.yml
.env.example
```

配置项：

```ini
FEISHU_MEMORY_ENABLED=true
FEISHU_MEMORY_MAX_MESSAGES=20
FEISHU_MEMORY_COMPRESS_THRESHOLD=20
FEISHU_MEMORY_RECENT_MESSAGES=8
FEISHU_MEMORY_COMPRESS_BATCH_SIZE=12
FEISHU_MEMORY_SUMMARY_MAX_CHARS=4000
```

字段说明：

| 配置项 | 说明 |
| --- | --- |
| `FEISHU_MEMORY_ENABLED` | 是否开启会话记忆 |
| `FEISHU_MEMORY_MAX_MESSAGES` | 兼容旧配置，当前代码不直接用它判断压缩 |
| `FEISHU_MEMORY_COMPRESS_THRESHOLD` | 当前会话消息超过多少条后触发压缩 |
| `FEISHU_MEMORY_RECENT_MESSAGES` | 压缩后继续保留多少条最近真实消息 |
| `FEISHU_MEMORY_COMPRESS_BATCH_SIZE` | 单次最多压缩多少条旧消息 |
| `FEISHU_MEMORY_SUMMARY_MAX_CHARS` | 压缩摘要最大字符数，超过后保留最近内容 |

### 2.1 当前默认行为

```text
每个群聊或私聊会话
  ├─ 历史摘要：最多 4000 个字符
  └─ 近期真实消息：最近 8 条
```

当前真正参与记忆控制的是下面 4 个参数：

```ini
FEISHU_MEMORY_COMPRESS_THRESHOLD=20
FEISHU_MEMORY_RECENT_MESSAGES=8
FEISHU_MEMORY_COMPRESS_BATCH_SIZE=12
FEISHU_MEMORY_SUMMARY_MAX_CHARS=4000
```

`FEISHU_MEMORY_MAX_MESSAGES` 目前只是兼容旧配置，修改它不会直接改变当前压缩行为。

## 3. 数据库表

原始消息表：

```sql
agent_conversation_memory
```

作用：

- 保存真实用户消息；
- 保存真实机器人回复；
- 群聊和私聊都只保存一份当前会话记忆；
- 每轮对话正常情况下保存 2 条：用户一条，机器人一条。

压缩摘要表：

```sql
agent_conversation_summary
```

作用：

- 保存旧消息压缩后的摘要；
- 通过 `memory_scope + memory_key` 唯一定位一个会话；
- 记录累计压缩消息条数；
- 避免旧消息直接删除后完全丢失上下文。

## 4. 代码调用顺序

### 4.1 飞书事件入口

```text
FeishuEventController
  ↓
FeishuEventParserService
  ↓
AdminAgentService.handleMessageAsync
```

说明：

- Controller 只负责接收飞书事件；
- Parser 负责把飞书原始 JSON 转成 `FeishuMessageEvent`；
- `AdminAgentService` 负责异步处理消息。

### 4.2 主流程编排

```text
AdminAgentService
  ↓
发送处理中表情或处理中提示
  ↓
ConversationMemoryService.saveUserMessage
  ↓
AgentOrchestratorService.run
  ↓
ConversationMemoryService.saveAssistantMessage
  ↓
FeishuOpenApiService 回复飞书
```

说明：

- 用户消息先保存；
- Agent 编排器再规划和调用工具；
- 机器人最终回复再保存；
- 处理中表情和临时提示不进入记忆。

### 4.3 Agent 调用上下文

```text
AgentOrchestratorService
  ↓
ConversationMemoryService.readMemoryText
  ↓
读取 agent_conversation_summary
  ↓
读取 agent_conversation_memory 最近 N 条
  ↓
拼成「历史摘要 + 近期对话」
  ↓
交给 AgentPlannerService / LLM
```

最终给模型的记忆格式：

```text
【会话记忆】
历史摘要：
...
近期对话：
user：...
assistant：...
```

## 5. 压缩逻辑

代码位置：

```text
src/main/java/com/sunzeqin/feishuadmin/service/ConversationMemoryService.java
```

核心流程：

```text
saveUserMessage / saveAssistantMessage
  ↓
save
  ↓
compressOldMessages
  ↓
查询当前会话消息总数
  ↓
未超过阈值：结束
  ↓
超过阈值：读取最旧的一批消息
  ↓
读取旧摘要
  ↓
mergeSummary 合并摘要
  ↓
upsertSummary 保存摘要
  ↓
deleteCompressedRows 删除已压缩旧消息
```

当前压缩方式是确定性文本压缩，不额外调用大模型。

原因：

- 不增加 LLM 成本；
- 不受余额不足影响；
- 压缩结果稳定；
- 适合当前演示和排查。

### 5.1 触发规则

每次保存一条用户消息或机器人真实回复后，都会检查当前会话的原始消息数量：

| 条件 | 行为 |
| --- | --- |
| 总消息数 `<= 20` | 不压缩，直接保留原始消息 |
| 总消息数 `> 20` | 读取最旧消息，合并到历史摘要 |
| 单次压缩数量 | `min(总消息数 - 8, 12)` |
| 压缩完成后 | 删除已进入摘要的原始消息，保留最近 8 条 |

例如当前有 21 条消息：

```text
21 - 8 = 13
min(13, 12) = 12
```

本次会把最旧的 12 条合并到摘要，并删除这 12 条原始记录，数据库中继续保留最近 9 条。下一次写入后会再次检查。

### 5.2 摘要截断规则

摘要不是大模型重新生成的语义摘要，而是把旧摘要和本次压缩的消息按文本顺序拼接起来：

```text
旧摘要
  ↓
本次历史压缩片段
  ↓
超过 4000 字符？
  ├─ 否：完整保存
  └─ 是：只保留最后 4000 字符
```

为了明确提示信息发生过截断，保存结果会增加：

```text
【较早摘要已截断】
```

因此日志中看到：

```text
摘要长度=4010
```

是正常现象，约等于 4000 个摘要字符加上截断提示文字。被截掉的是更早的历史，不是最近 8 条真实消息。

后续可升级为：

```text
确定性摘要
  ↓
LLM 摘要
  ↓
向量召回 + 摘要
```

## 6. 日志观察点

重点看这些日志：

```text
会话记忆读取
会话记忆写入
会话记忆压缩
```

日志含义：

| 日志 | 说明 |
| --- | --- |
| 会话记忆读取 | 当前请求读取了多少摘要和近期消息 |
| 会话记忆写入 | 用户或机器人真实消息已保存 |
| 会话记忆压缩 | 旧消息进入摘要表，原始旧消息被清理 |

### 6.1 读取日志示例

```text
会话记忆读取完成：
消息ID=...
读取表=[agent_conversation_summary,agent_conversation_memory]
范围=GROUP
记忆Key=oc_xxx
读取给=AgentOrchestratorService->AgentPlannerService/LLM
摘要长度=4010
近期条数=8
记忆文本长度=5391
完整记忆文本=...
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `范围=GROUP` | 使用群聊共享记忆 |
| `记忆Key` | 群聊 ID；私聊时使用对应会话 ID |
| `摘要长度` | 从 `agent_conversation_summary` 读取的摘要长度 |
| `近期条数` | 从 `agent_conversation_memory` 读取的最近消息数量 |
| `记忆文本长度` | 历史摘要和近期对话拼接后的总长度 |
| `读取表` | 实际读取的历史摘要表和近期消息表 |
| `读取给` | 记忆最终注入的组件：Agent 编排器、规划器和大模型 |
| `完整记忆文本` | INFO 日志中实际注入 Agent 的完整上下文 |

### 6.2 一轮消息的写入规则

正常完成一轮对话时，数据库会写入两条真实消息：

```text
用户消息       role=user
机器人真实回复  role=assistant
```

处理中表情、处理中提示、系统内部规划文本不会作为真实对话写入记忆。

如果日志看到两个“会话记忆写入”，通常不是重复写入，而是这一轮的用户消息和机器人回复各写入一次。

## 7. 部署注意

上线前确认 MySQL 已执行最新表结构：

```text
src/main/resources/db/schema-mysql.sql
```

如果表没有自动生成，可以手动执行其中的 `agent_conversation_summary` 建表语句。

提交代码时建议只提交源码和配置，不提交 `target`：

```powershell
git add .env.example src/main/java src/main/resources docs
git commit -m "新增会话上下文压缩文档"
```
