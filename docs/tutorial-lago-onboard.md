# 教程：30 分钟接一个 Lago 厂商

> 目标读者：拿到 Lago 计费平台导出数据、想做账单稽核的工程师/财务。
> 全程不需要写 Java 代码——改的每一行都是配置（YAML 规则文件 / REST 参数）。
>
> 本教程中的表头、参数均逐字核证自 recon-lens 测试与 Lago 源码
> （`lago/api/app/services/data_exports/csv/invoice_fees.rb` / `invoices.rb`，2026-11）。

## 你将得到什么

走完本教程，你会拥有一条**每月可重复执行**的稽核流水线：

1. Lago 发票明细进了你自己的库（`lago_fees` 表，分单位已自动换算成元）
2. 一条对账规则把「Lago 账单」和「我方计量记录」按业务键逐笔比对
3. 差异清单 + 归因 + **一份可以直接发给客户/财务的争议证据包 CSV**

前置条件：

- JDK 17+（Windows 可直接用 `mvnw.cmd`）
- Lago 侧能导出发票 CSV（UI 里 Invoice 列表 → Export CSV），或持有 Lago API key

## 路线 A：文件导入（零配置，最快见效）

### 第 1 步（~5 分钟）：拿到 Lago 导出 CSV

Lago UI → Invoices → 勾选账期 → Export CSV。得到一份表头如下的文件
（这就是 profile `lago-invoice-fees` 逐字对齐的 20 列）：

```
invoice_lago_id,invoice_number,invoice_issuing_date,fee_lago_id,fee_item_type,
fee_item_code,fee_item_name,fee_item_description,fee_item_invoice_display_name,
fee_item_filter_invoice_display_name,fee_item_grouped_by,subscription_external_id,
subscription_plan_code,fee_from_date,fee_to_date,fee_amount_currency,fee_units,
fee_precise_unit_amount,fee_taxes_amount_cents,fee_total_amount_cents
```

注意：`fee_total_amount_cents` / `fee_taxes_amount_cents` 单位是**分**。导入时
profile 会自动 ÷100 换算成元，你不需要手工处理。

### 第 2 步（~3 分钟）：启动 recon-lens，导入成表

```bash
./mvnw spring-boot:run        # Windows 用 mvnw.cmd
```

把 CSV 放进导入目录（默认 `./data/import`，可在 `recon.agent.file-import.dir` 改），
然后用 MCP 或会话调 `recon_import_file`。**先预览再确认**，两段式：

```
recon_import_file { file: "lago_invoices_2026-10.csv", action: "preview",
                   profile: "lago-invoice-fees" }
```

预览会显示：命中 profile 的列改名与类型映射（`fee_total_amount_cents` →
`total_amount DECIMAL(24,6)`）、金额归一说明、尾部多余列的通用推断结果。
表头对不上会**直接报错并列出期望表头**——不会静默导入一份口径错乱的表。

确认无误后：

```
recon_import_file { file: "lago_invoices_2026-10.csv", action: "confirm",
                   table: "lago_fees", profile: "lago-invoice-fees" }
```

完成：`lago_fees` 表已灌数，且已在表白名单里注册（能被对账规则引用）。

### 第 3 步（~5 分钟）：把我方计量数据也导进来

对账是两张表的事。把你这侧的用量/计费记录也用 `recon_import_file` 导入
（没有厂商 profile，走通用导入即可；金额列给 DECIMAL）。

两边**业务键口径必须一致**——比如都按 `invoice_number + item_code` 能唯一定位一笔计费。

### 第 4 步（~7 分钟）：写一条对账规则

新建 `rules/lago-vs-bill.yml`（或放进 `recon.rules-location` 指向的目录，重启自动生效）：

```yaml
code: LAGO_VS_BILL
name: Lago 发票明细 vs 我方计量（发票号+计费项）
description: 按 发票号+计费项 复合维度逐笔比对总金额，相对偏差 0.01% 内视为一致

left:
  label: Lago
  table: lago_fees
  keyType: STRING
  # 引擎生成 CONCAT(invoice_number,'|',item_code) AS __biz_key 按它归并
  dimension: [invoice_number, item_code]

right:
  label: 我方计量
  table: my_usage
  keyType: STRING
  dimension: [invoice_number, item_code]

shard:
  count: 2
  parallelism: 2
  # 两侧必须按同一业务键口径切分：这里按发票号末位取模
  whereLeft: "MOD(CAST(SUBSTRING(invoice_number, -1) AS SIGNED), {shards}) = {index}"
  whereRight: "MOD(CAST(SUBSTRING(invoice_number, -1) AS SIGNED), {shards}) = {index}"

compare:
  - field: total_amount
    type: DECIMAL
    tolerancePercent: 0.0001   # 0.01%
    diffType: AMOUNT_MISMATCH
    severity: HIGH

missing:
  leftMissingDiffType: MISSING_LEFT    # 只有我方有 = Lago 少开票
  rightMissingDiffType: MISSING_RIGHT  # 只有 Lago 有 = 我方漏计
  severity: HIGH

duplicate:
  diffType: DUPLICATE_KEY
  severity: MEDIUM

options:
  maxAttempts: 2
```

> 注意 `SUBSTRING(x, -1)` 在 MySQL 取末位、H2 语义不同——演示库/生产库的方言差异
> 以 `docs/rule-dsl.md` 的分片写法为准，本例是 MySQL 生产口径。

### 第 5 步（~5 分钟）：跑对账、看差异、出证据包

```
recon_run { ruleCode: "LAGO_VS_BILL" }
```

返回任务号。看结果：

```
recon_job_summary { jobId: 2 }      # 分片状态、差异分类计数
recon_list_diffs { jobId: 2 }       # 逐条差异：我方值/对方值/差额/差异比
recon_explain_job { jobId: 2 }      # 批量补归因
```

最后，杀手级一步——**争议证据包**（REST，浏览器/下载都行）：

```bash
curl -o evidence-pack.csv "http://localhost:8080/api/jobs/2/evidence-pack.csv"
```

打开这份 CSV：逐条差异挂着**当时两侧整行 JSON 凭证**（源表后来变了也不影响复盘
口径）、生命周期状态。这就是可以直接转发给客户/财务对质的材料。

## 路线 B：API 连接器（填 key 自动拉，适合每月定时跑）

不想每月手动导出 CSV？配置 API key：

```yaml
recon:
  connector:
    lago:
      # 必须指到 /api/v1 的 API 根地址（客户端直接在后面拼 /fees 等路径）
      base-url: https://your-lago-host/api/v1
      api-key: ${LAGO_API_KEY}            # 建议走环境变量，不要写死在配置里
      dir: ./data/lago-sync               # 拉下来的 CSV 落盘位置（就是原始凭证）
```

然后：

```bash
curl -X POST http://localhost:8080/api/connectors/lago/sync \
     -H "Content-Type: application/json" -H "X-Api-Key: <你的密钥>" \
     -d '{"resource": "fees"}'
```

连接器会：分页拉全 → 按 profile 表头映射 → **落盘 CSV**（文件本身就是原始凭证，
带 max-pages 保险丝、半截响应不落盘）。落盘后的导入仍走 `recon_import_file`
——拉取与导入是刻意分离的：profile 归一口径只能有一份，第二条灌数路径就是假差异温床。

`GET /api/connectors` 可以查看已配置的连接器目录（只报 key 是否已配，**不回显 key**）。

## 下一层（可选）：金额影响与闭环台账

差异只是"不一致"，财务真正关心的是"差了多少钱"：

1. **录价格**：`POST /api/prices`（或 `recon_list_prices` 看现有目录）——
   `price_code × model × token_type × 时间窗 × tier` 五维价格表
2. **算影响**：`POST /api/prices/impact` 汇总整个任务的可计价差异 × 价格，
   负数 = **少收的钱**；`POST /api/diffs/{id}/price-impact` 单条计价
3. **再导一次证据包**：`GET /api/jobs/{id}/evidence-pack.csv?priceCode=...`，
   这时每条差异会挂上适用价格与生效窗、金额影响列
4. **闭环**：`POST /api/diffs/{id}/lifecycle`（CLAIM → RESOLVE/EXEMPT，append-only
   台账，谁/何时/依据全程留痕）；`GET /lifecycle/summary` 看闭环率

如果你们按 token 计费（AI 公司常见场景），第 1 步的价格表就是护城河所在：
`model × token_type（含 cache 分价）× 生效窗` 能精确处理"月中调价回溯"。

## 常见坑（都踩过，别再踩）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 导入报"表头不匹配" | Lago 开了 progressive billing，表尾多了列 | 正常——表头按**顺序前缀**匹配，表尾多余列走通用推断；只有前 20 列必须一致 |
| 差异金额差 100 倍 | 自己拼 CSV 时忘了 `_cents` 是分 | 别手工处理金额，交给 profile |
| 分片后差异诡异翻倍 | 两侧分片 SQL 口径不一致 | `whereLeft`/`whereRight` 必须对同一列用同一函数 |
| 大量 MISSING 但数据明明在 | 水位未就绪（数据还没进齐就跑了） | 配置 `recon.engine.watermarks.<规则code>`，未就绪侧会标 `*_PENDING` 而不是报差异 |
| 全库差异视图 500 | 老版本 bug，两表同名列未带前缀 | 已修（2026-11），升级即可 |

## 全程不需要写代码的部分，和需要的部分

**不需要**：Java、SQL 之外的任何语言；改引擎源码；重启之外的运维动作。

**需要写配置的**：一条 YAML 规则（第 4 步）——这是唯一"新"的东西，而且写完它
就是可版本化、可审批、可回滚的对账口径本身。

**接下一个厂商**（Stripe / 计量平台 / 内部结算系统）：= 加一份 profile 映射
（20 行配置）+ 按真实导出表头写的测试。导入器、规则、差异、证据包全部复用。
