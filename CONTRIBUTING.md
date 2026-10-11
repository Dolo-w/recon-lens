# 参与开发

欢迎 issue 和 PR。这个项目的定位是"小而准"：宁可少一个功能，也不要多一份没人看得懂的代码。

## 环境

- JDK 17（Temurin / 其他 OpenJDK 都行）
- Maven 3.9+，或者直接用仓库自带的 wrapper `./mvnw`（Windows：`mvnw.cmd`）
- 不需要装数据库：默认 H2 内存库自带演示数据

```bash
./mvnw test                        # 单元 + 端到端（真 H2、真 SQL、真并发），CI 跑的就是这条
./mvnw -DskipTests package         # 产出 target/recon-lens-0.1.0.jar
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar   # MCP 协议级冒烟，需要 Node 18+
```

百万级压测用例带 `@Tag("bench")`，默认被 surefire 排除，跑法见 [docs/benchmark.md](docs/benchmark.md)。

## 代码约定

- **不轻易加依赖**。要用新依赖请在 PR 里说明为什么现有的做不到，以及它的传递依赖会不会和 Spring Boot BOM 打架。
- **engine 包不碰数据库**：引擎只通过 `DiffSink` / `DiffWriter` / `RowCursor` 这几个接口读写，所以它能在没有 Spring、没有库的情况下单测。
- **规则的可表达性优先于引擎的聪明**。引擎里任何"猜"的行为（比如自动判定哪侧缺失）都要在 `docs/rule-dsl.md` 里写清楚。
- **改行为就要改测试**：新增差异类型、调整容差语义、动分片计划，都请补对应用例，别只改实现。
- 中文注释和中文文档是刻意的：这个项目的主要读者是中文技术团队。

## 提交与 PR

- 提交信息用 `<type>: <中文简述>`，type 取 `feat` / `fix` / `docs` / `test` / `refactor` / `chore`，例如
  `fix: 单边缺失的严重级别应该跟随 missing 段配置`
- PR 描述里写清三件事：**为什么改**（场景 / 复现）、**怎么改的**（关键设计取舍）、**怎么验证的**（跑了哪些命令）
- CI 必须绿（`./mvnw test` + MCP 冒烟）；文档有变化时同步更新 `README.md` / `docs/`，用户可见的行为变化记到 [CHANGELOG.md](CHANGELOG.md)

## 报告问题

用 issue 模板，尽量给出最小复现（规则 YAML + 数据量级 + 报错堆栈根因）。对账结果不对时，
把 `recon_shard` 里各分片的状态和行数一并贴上，通常一眼就能定位是分片切分口径还是比较逻辑的问题。
