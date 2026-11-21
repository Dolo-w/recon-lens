# 扩展架构 v3：执行分发器 + 连接器 SPI + CI + Docker

> 状态：**已全部落地**（第一批 795d81c：CI/Docker/SPI/Orb；第二批：ExecutionDispatcher + golden-set；2026-11-21 深夜纯化：正式对账全走 worker，删 auto 三档改两档）
> 原则不变：拉取与导入分离、fail-loud、差异生命周期口径统一、ArchUnit 边界硬约束。

## 一、总览

```
                     ┌────────────┐
   JobController ──► │ ReconEngine│──► prepare（水位/就绪判定）
                     └─────┬──────┘
                           │ run / runAsync
                     ┌─────▼───────┐
                     │ ExecutionDispatcher │  ← 新增（dispatch 包）
                     └───┬─────┬───┘
              行数 ≤ 阈值 │     │ 行数 > 阈值 或 显式指定
        ┌────────────────▼┐  ┌─▼──────────────────────────┐
        │ INPROCESS（现状）│  │ GO_WORKER（新增路径）        │
        │ JdbcRowCursor   │  │ 1. 导出两侧为键序 CSV        │
        │ MergeMatcher    │  │ 2. ProcessBuilder 调 worker │
        │ ShardRunner     │  │ 3. 读 diffs JSONL           │
        │ 直接写 recon_diff│  │ 4. 映射导回 recon_diff       │
        └─────────────────┘  └────────────────────────────┘
                           两种路径产出完全同口径的
                     job 状态机 + recon_diff + MatchStats
```

## 二、模块 ①：ExecutionDispatcher（Java↔worker 打通）

### 2.1 决策策略
- 配置键：`recon.execution.mode: auto | inprocess | go-worker`（默认 **auto**）
- auto 逻辑：建任务时顺带 `COUNT(*)` 两侧行数（水位探测阶段已有 DB 访问，零额外成本），
  `max(rowsLeft, rowsRight) > recon.execution.worker-threshold`（默认 **50_000**）→ GO_WORKER，否则 INPROCESS
- 显式指定优先：JobRequest 可带 `execution: GO_WORKER` 强制走（演示/压测用）

### 2.2 GO_WORKER 路径（dispatch 包，新增 4 个类）
| 类 | 职责 |
|---|---|
| `WorkerCsvExporter` | 把两侧数据按键序导出为临时 CSV（复用连接器落盘的 RFC4180 约定；**导出前校验键序**，乱序 fail-loud——worker 也预检，双保险） |
| `WorkerProcessRunner` | ProcessBuilder 拉起 worker：`--left --right --spec --out --stats --checkpoint`；超时（默认 30min）杀进程标 FAILED；stderr 捕获进 job error |
| `WorkerSpecWriter` | RuleSpec → worker 规格 JSON（字段映射已有 profile 逻辑可参考）；事件时间/水位/PENDING 语义逐字段对齐 |
| `WorkerDiffImporter` | diffs JSONL 流式读 → 映射 DiffRecord → 批量写 recon_diff；job 统计（MatchStats）从 worker stats.json 摘要合并 |

### 2.3 不变式（硬要求，测试钉住）
1. 两种执行器产出**同一 job 状态机**（PENDING→RUNNING→SUCCEEDED/FAILED/PARTIAL）、同一 recon_diff 列集、同一 MatchStats 口径
2. worker 缺失/路径错 → 启动即 FAILED（fail-loud），不静默回退 INPROCESS
3. worker 路径差异与 Java 路径差异**字段级一致**（golden-set 对照测试：同一份数据两边跑，diff 集合等价）
4. checkpoint 文件留在 job 工作目录，PARTIAL 可 `--resume` 语义映射成 recon 的「重跑」
5. ArchUnit：dispatch 包不得依赖 agent/chat

### 2.4 worker 二进制解析
- `recon.execution.worker-path`：显式路径优先
- 未配置时按约定探测：`go-worker/recon-worker-{os}-{arch}{ext}`（linux-amd64 / darwin-arm64 / windows-amd64.exe）
- 探测不到 + auto 决策命中 GO_WORKER → 任务 FAILED，error 写明「未找到 worker 二进制，装好后重试」

## 三、模块 ②：BillingConnector SPI（第二连接器前置）

### 3.1 接口（connector 包）
```java
public interface BillingConnector {
    String name();                                   // "lago" / "stripe" / "orb"
    boolean configured();                            // 凭证齐不齐（不回显内容）
    void validate();                                 // fail-loud 校验（401 立刻抛）
    SyncResult sync(SyncKind kind, Path workDir);    // 分页拉全 → 落盘 CSV，返回 manifest
    record SyncResult(List<Path> files, long rows, String note) {}
}
```
- `ConnectorController` 泛化为多厂商：`GET /api/connectors` 列全部实现 + configured 状态；`POST /api/connectors/{name}/sync` 路由
- Lago 现有实现重构为 `LagoConnector implements BillingConnector`，行为零变化（现有测试全绿为准）
- 新增实现只允许依赖 connector 包内公共设施，ArchUnit 照旧

### 3.2 第二连接器候选（**需拍板**）
| 候选 | 对口度 | 成本 |
|---|---|---|
| **Orb**（usage-based 计费，AI 公司主流选择） | 最高：metered events/subscription/ invoice 三层，与 token 计量叙事完全同构 | REST API 干净，估计 1-1.5 天 |
| **Stripe Billing** | 中：发票/订阅强，但 metered 用法要拆 usage record | API 最普及、文档最全，估计 1-2 天 |
| Metronome | 高（AI 计费原生） | 文档相对少，沙箱账号要申请 |

**vv 倾向 Orb**：客户画像①（AI 公司 CTO）用的就是这类 usage-based 计费，叙事"稽核 AI 计费厂商"比"稽核 Stripe"锐利得多。

## 四、模块 ③：CI 补 Go

build.yml 加 `go-worker` job（与 Java test 并行）：
1. `actions/setup-go`（1.27.x）
2. `go vet ./...`（go-worker 目录）
3. `go test -race ./...` —— 补上本机没有 gcc 跑不了的竞态检测
4. `go build` 三平台产物（amd64）挂 artifact，release.yml 同步产出（worker 二进制随 GitHub Release 发）

## 五、模块 ④：Docker 部署形态

### 5.1 形态：单镜像 + compose（轻量档）
```
recon-lens/
├── Dockerfile              # 多阶段
│   stage1: golang:1.27 → 编译 worker（CGO_ENABLED=0 静态）
│   stage2: maven-temurin-17 → mvnw package
│   stage3: temurin-17-jre → 拷 jar + worker 二进制 + entrypoint
├── docker-compose.yml
│   services: recon (8080) + mysql:8（volume 持久化）
│   env: SPRING_PROFILES_ACTIVE=mysql, MYSQL_*凭证（compose 内网，不对外）
└── docker/demo-seed/       # 可选演示种子（CSV+规则），首次启动 recon_import_file 灌入
```
- worker 就在镜像里（`/usr/local/bin/recon-worker`），`worker-path` 指过去 → Docker 形态天然获得 GO_WORKER 能力
- 不做 Lago 全套 compose（重）；教程里保留现有本地 Lago 路线，加一句「已有 Lago 的填 base-url 即可」

## 六、实施顺序（依赖关系决定）

```
CI 补 Go（半天，独立）──┐
Docker（半天，独立）────┼──► 并行开工
连接器 SPI（1 天）──────┤
执行分发器（2-3 天）────┘   ◄── 依赖 Docker 产出 worker 二进制的命名约定
                            ◄── golden-set 对照测试依赖两个执行器都在
```
建议顺序：CI → Docker（顺便定二进制命名）→ SPI 重构 Lago → 第二连接器 → 执行分发器（最后做，吃前面所有产出）。

## 七、决策点（2026-11-21 老大拍板，全部按 vv 倾向执行）

| # | 问题 | ✅ 决议 |
|---|---|---|
| D1 | 第二连接器选 Orb / Stripe / Metronome | **Orb**（usage-based 与 token 计量叙事同构） |
| D2 | worker 阈值默认值 | **50_000 行起步**，压测后调 |
| D3 | auto 模式默认开还是关 | **默认 inprocess**，`recon.execution.mode: auto` 显式开启 |
| D4 | Docker 演示种子 | **带**（开箱即见差异） |
| D5 | worker 差异导回方式 | **批量 INSERT**（与 Java 路径同构，事务按 shard） |
