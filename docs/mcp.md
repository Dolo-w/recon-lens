# 用 MCP 让 Agent 直接调对账

REST 接口是给"人"和"调用方系统"用的：你得知道 URL、参数、返回值长什么样。
但运营问的那句话是**"这批账单和结算到底差在哪、为什么差"**——这种问题现在多半是丢给 Agent 的。
MCP（Model Context Protocol）就是让 Agent 能直接调工具的协议：模型自己决定调 `recon_run` 跑对账、
调 `recon_list_diffs` 看差异、调 `recon_explain_job` 拿归因，全程不用人拼 URL。

recon-lens 暴露 6 个工具，**和 REST 接口共用同一套 service**（`ReconEngine` / `ReconRepository` / `ExplainService`），
所以两条路的结果必然一致，不存在两套逻辑。

## 启动

```bash
mvn -DskipTests package
java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mcp
```

`mcp` profile 做三件事（见 `src/main/resources/application-mcp.yml`）：

| 配置 | 为什么必须 |
| --- | --- |
| `spring.main.web-application-type=none` | stdio 模式不需要 Web 容器，也不该占端口 |
| `spring.main.banner-mode=off` | 启动横幅会写 stdout，而 stdout 是 JSON-RPC 通道 |
| `recon.engine.run-demo-on-startup=false` | 启动演示会打印报告，同样污染通道 |

## 客户端配置

任何 MCP 客户端（Claude Desktop、Cursor、自研 Agent）都是同一份 JSON，把 `command` 换成绝对路径即可：

```json
{
  "mcpServers": {
    "recon-lens": {
      "command": "java",
      "args": [
        "-jar",
        "/绝对路径/recon-lens-0.1.0.jar",
        "--spring.profiles.active=mcp"
      ]
    }
  }
}
```

## 工具清单

| 工具 | 入参 | 返回 |
| --- | --- | --- |
| `recon_list_rules` | 无 | 已加载规则的编码、两侧数据源、比对字段、分片与重试配置 |
| `recon_run` | `ruleCode`（必填）、`batchId`、`remark` | 同步跑完一次对账，返回任务号、扫描量、差异分类、分片明细 |
| `recon_job_summary` | `jobId` | 任务状态 / 分片完成度 / 差异分类与级别 / 分片明细 / 错误原因 / 耗时 |
| `recon_list_diffs` | `jobId`（必填）、`diffType`、`offset`、`limit` | 差异明细：业务键、字段、左右值、相对偏差、已有归因 |
| `recon_explain_diff` | `diffId` | 单条差异的归因说明 |
| `recon_explain_job` | `jobId`（必填）、`limit` | 批量补归因并回显前几条 |

工具返回的是**给人看的中文文本**，不是 JSON —— Agent 拿到就能直接转述给运营，不用再解释字段含义。
参数缺失、规则不存在、任务号不存在这类错误都会被包成 `isError=true` 的结果文本，而不是让服务端崩掉。

## 自己验证（不用装客户端）

仓库里带了一个 30 行的 stdio 冒烟脚本，直接对着协议说话：

```bash
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar
```

它依次做 `initialize` → `tools/list` → 6 个工具的真实调用，并断言：

- `tools/list` 里有 6 个工具，`recon_run` 的 `inputSchema` 声明了必填 `ruleCode`
- 未知规则被包成 `isError`，而不是抛异常断开连接
- **stdout 全程只有 JSON-RPC**（有 0 行噪声才通过）
- 关闭 stdin 后进程自行退出（`exit code=0`）

一次真实运行的关键输出：

```
initialize -> serverInfo={"name":"recon-lens","version":"0.1.0"} protocol=2024-11-05
tools/list -> recon_list_rules, recon_run, recon_job_summary, recon_list_diffs, recon_explain_diff, recon_explain_job
recon_run  -> 任务 #1｜规则 BILL_VS_SETTLE｜批次 mcp-smoke｜状态 SUCCESS
              分片 4/4 完成｜扫描 左=20 右=21｜匹配 19｜差异 6
              差异分类：AMOUNT_MISMATCH=2，DUPLICATE_KEY=1，MISSING_LEFT=1，MISSING_RIGHT=1，STATUS_MISMATCH=1
recon_list_diffs -> #2 [MEDIUM] STATUS_MISMATCH 业务键=ORD20260004 字段=status 左=UNPAID → 右=PAID
                    #1 [HIGH] AMOUNT_MISMATCH 业务键=ORD20260003 字段=amount 左=3000 → 右=2999（相对偏差 0.0333%）
recon_explain_job -> 任务 #1 已生成 6 条归因（解释器=rule-template，LLM 未启用或未配置，已用规则模板降级）
```

## 两个踩过的坑（stdio 传输的真实约束）

**1. stdout 是协议通道，日志必须让路。**
项目里没有任何 `System.out`，但 logback 自己的状态输出（版本号、configurator 清单等几十行）默认写 stdout ——
第一次冒烟就是它在 JSON-RPC 里插了 30 行。注意**光在配置文件里加 `statusListener` 太晚**（那些状态在解析配置之前就打出来了），
必须在 Spring 初始化日志系统之前设系统属性，所以 `ReconLensApplication.main` 里有一段：

```java
if (mcpProfileActive(args)) {
    System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
}
```

日志本身则由 `logback-spring.xml` 在 `mcp` profile 下全部改道 stderr。

**2. 协议版本由服务端回落。**
当前 SDK（`io.modelcontextprotocol.sdk:mcp:0.18.4`）支持到 `2024-11-05`：
客户端请求 `2025-06-18` 时服务端会接受握手但回落到自己支持的版本，并在 stderr 打一行 WARN。协议要求客户端以服务端返回的版本为准，所以不算错误。

## 边界

- **不做鉴权**：stdio 进程由调用它的客户端拉起，权限边界就是父子进程边界；对外暴露请走 REST + 网关
- **不做 HTTP/SSE 传输**：多客户端共享一个服务实例的场景应该用 REST 接口
- **只用 tools**：resources / prompts / sampling 都没接，因为对账这个场景就是"调工具拿结果"
