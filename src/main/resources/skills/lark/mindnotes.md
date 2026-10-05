# Mindnotes Skill

分层：第一层「飞书原生能力」，只描述 lark-cli 在该业务域能做什么、调用约定是什么。

能力来源：以 `lark-cli <domain> --help` 的实时输出为准（编写时参考 CLI 版本 1.0.95）。

## 适用场景

- 查询思维笔记节点。
- 创建或更新思维笔记节点。

## 执行原则

1. 先查帮助，不要猜命令。
2. 操作前必须确认 mindnote id。
3. 如果用户说“把上文/群聊总结做成思维导图”，且没有提供已有 mindnote id，不要强行创建 mindnotes 节点；优先交给 Java 侧固定流程创建 Markdown 文档形式的思维导图。
4. 如果 `mindnotes` / `wiki` 空间信息不足，立即降级为 `docs +create --doc-format markdown`，不要反复查询帮助或空间列表。

## 手册命令索引

```bash
lark-cli mindnotes --help
lark-cli mindnotes nodes --help
lark-cli mindnotes nodes list --help
lark-cli mindnotes nodes create --help
```
