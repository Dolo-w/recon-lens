# 降级矩阵

> 由 `work/fault-injection.ps1` 生成。契约：**降级，而不是把异常抛给调用方**。

| 注入的故障 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 模型返回坏 JSON | 回退词表，会话仍有回复 | HTTP 200，仍返回了对话内容（727 字符） | PASS |
| 模型进程不可用 | 降到 P1，不 500 | HTTP 200，840 字符 | PASS |
| 起草拿到致命违规 | 会话进入 BLOCKED/PARTIAL，不抛 500 | runId=4，终态 BLOCKED，轨迹里看到闸门拒绝：True | PASS |

未自动化的一项：**数据库方言不支持**（H2 缺 `CRC32`）—— 已在 `docs/report-real-llm.md` 里实测过：
试算 4 个分片全 FAILED → 会话 `PARTIAL` 且 `errorMsg` 写明 `CANARY_FAILED`，
而不是静默 `SUCCESS`。它需要改库（DROP ALIAS），不适合放进常规冒烟。
