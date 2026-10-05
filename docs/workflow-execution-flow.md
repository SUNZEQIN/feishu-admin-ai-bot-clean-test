# 可配置工作流执行流程

## 目标

把数据库里的 `workflow_definition` / `workflow_step` 接入 Agent 执行链路，让常用业务流程不再完全依赖大模型临场规划。

## 执行链路

```text
飞书用户消息
  ↓
AgentPlannerService 外层规划
  ↓
workflow.list 查询启用工作流
  ↓
workflow.run 执行指定 workflowCode
  ↓
WorkflowExecutionService 按 stepNo 顺序执行
  ↓
MCP / CLI / LLM_SUMMARY / FEISHU_REPLY
  ↓
AgentOrchestratorService 读取 finalReply 并回复飞书
```

## 工具对应关系

| executorType | 执行器 | 当前行为 |
| --- | --- | --- |
| `MCP` | `EcommerceMcpClientService` | 调用 `toolName` 对应的电商 MCP 工具 |
| `CLI` | `SkillCliExecutorService` | 调用 `cli.run_skill` 走飞书 CLI |
| `LLM_SUMMARY` | `WorkflowExecutionService` 内部 LLM | 基于前置步骤结果生成中文总结 |
| `FEISHU_REPLY` | `WorkflowExecutionService` | 生成 `finalReply`，由外层统一回复飞书 |
| `JAVA_TOOL` | 暂未开放 | 当前直接失败，避免配置了未实现工具却静默成功 |

## 日志阶段

工作流执行统一使用：

```text
[阶段10 工作流执行]
```

关键日志包括：

```text
查询候选工作流：关键词=...，启用数量=...，命中数量=...
开始执行：工作流编码=...，名称=...，步骤数=...，初始入参字段=...
步骤开始：工作流编码=...，步骤=...，执行器类型=...，工具名称=...，入参字段=...，输出变量=...
步骤完成：工作流编码=...，步骤=...，输出摘要=...，耗时=...ms
执行完成：工作流编码=...，上下文字段=...，最终回复长度=...，耗时=...ms
```

## 示例

用户说：

```text
查一下陈金金最近12个月订单，整理成卡片发群里
```

执行过程：

```text
1. Agent 调用 workflow.list，关键词=用户原文
2. 命中 ecommerce_order_summary
3. Agent 调用 workflow.run
4. 步骤1：MCP -> ecommerce.query_customer_orders
5. 步骤2：LLM_SUMMARY -> 总结订单数据
6. 步骤3：FEISHU_REPLY -> 生成 finalReply
7. 外层编排器直接回复飞书
```
