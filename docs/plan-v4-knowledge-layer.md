# v4 知识层规划：让系统越用越懂账

> 状态：**已实施（2026-11-21，K1→K4 全部闭环）**。前置摸底：归因知识库骨架已存在（`recon_diff_kb` +
> `DiffKnowledgeBase` 三层成本分层），本规划是在真实地基上补缺口，不是从零建。

## 一、现状盘点（摸底结论）

### 已经有的（比直觉的好）

| 机制 | 现状 |
|---|---|
| **归因知识库 `recon_diff_kb`** | 案例级：CauseKey = `ruleCode + diffType + fieldName + featureSignature` 精确匹配；`lookup/remember/teach` 三动作；信任梯度 LLM=0.5 / 人工=0.9（模型自述永远压不过人工确认） |
| **三层归因成本分层** | 知识库命中 → 直接用（零 LLM 成本）→ 未命中走 LLM → LLM 不可用走模板，来源标注在 `Attribution.Layer` |
| **生命周期台账** | `recon_diff_lifecycle` append-only：认领/核销/豁免 + operator + note 全留痕 |
| **SchemaCatalog** | 表/列的存在性与类型（`recon_describe_table`） |

### 缺口（精确到机制）

| # | 缺口 | 本质 |
|---|---|---|
| K1 | **人工闭环没接进知识库** | 最贵的数据（人处理差异后的定性结论）躺在台账里，第 101 条同类差异查不到前 100 条的答案 |
| K2 | **模式级知识为零** | kb 是案例级精确匹配（换规则/换字段就 miss）；"单位换算差 100 倍"这类跨规则、跨客户的领域病没有沉淀，也无法从机械特征（delta 比率）自动联想 |
| K3 | **字段语义字典为零** | SchemaCatalog 只有列名+类型；`amount` 是含税还是净额，模型只能猜 |
| K4 | **厂商计费语义 / SOP** | Lago/Orb 的出账行为语义不在场；排查 SOP 应放外部知识库 |

## 二、总设计：知识分两层，引擎消费优先

**能被引擎消费的知识 → 进 recon-lens**（变成硬约束/检索目标）：
K1 案例回流、K2 模式库、K3 语义字典。

**叙述性知识 → 外部资料库**：K4 SOP、厂商文档解读——recon-lens 是引擎不是 wiki。

设计原则沿用全产品已验证的三条：
1. **机器可复算优先**：模式匹配是机械特征判断（比率/时间差），不是"AI 感觉像"；
2. **信任梯度**：机器联想 < LLM 推断 < 人工确认，来源永远标注；
3. **fail 保守**：匹配不上就诚实说"无候选"，绝不脑补一个原因。

## 三、四个 Phase 详细设计

### Phase K1：人工闭环 → 知识库回流（先挖最便宜的黄金矿）

**改动**：`POST /api/diffs/{id}/lifecycle` 的核销/豁免动作，在写台账的同时把定性结论
`teach()` 进 `recon_diff_kb`（`source=HUMAN, confidence=0.9`）。

**前提改动**：豁免/核销的 `note` 从纯自由文本升级为 **`reasonId`（模式库分类 ID）+ 补充说明**。
这里和 K2 咬合：定性下拉框的选项就是 K2 模式库的分类法。先 K1 后 K2 时，
reasonId 允许空（自由文本兜底），K2 落地后变必选。

**效果**：处理过 100 条差异的系统，第 101 条同特征差异自动带出历史答案与处置方式。
数据成本为零——结论本来就在产生，只是没接进检索链路。

**工作量**：约 1 天（lifecycle service 一个方法 + kb teach 复用 + 测试）。

### Phase K2：经验模式库（模式级知识，跨规则生效）

**数据形态**：结构化 YAML（classpath + 支持外部目录热更，与规则/价格表同款哲学）：

```yaml
patterns:
  - id: UNIT_SCALE_X100
    family: 金额量级            # 大类：金额 / 时间 / 存在性 / 聚合口径
    name: 单位换算错误
    match:                      # 机械特征，全部可复算
      deltaRatioIn: [99.5, 100.5]   # delta/abs(左值) 恰为 ~100
    hypothesis: 两侧金额单位不一致（分/元/厘），核对 profile 单位换算配置
    probeHint: 对比两侧 profile 的 _cents 除数
  - id: TIMEZONE_OFFSET_8H
    family: 时间错位
    match:
      eventTimeDiffHours: [7.9, 8.1]
    hypothesis: 两侧时间戳时区口径不同（UTC vs UTC+8）
```

**引擎消费点**（ExplainService 分层前面加 L0 信号联想）：

```
L0 模式联想：机械特征（delta 比率、时间差、模式串）匹配 YAML → 候选假设（标注"机器联想"）
L1 案例知识库：CauseKey 精确命中（标注历史案例与人工确认次数）
L2 LLM 归因：现场推理（confidence=0.5 写回）
L3 内置模板：兜底
```

**初始内容**：手写 10–15 个账单对账常见病——单位换算、时区错位、月初月末切账边界、
重复扣款、退款冲销未同步、汇率日切差、partial period 拆分、credit 未入账、
聚合口径差（去重/不去重）、幂等重放……**这份清单本身就是产品化的核心资产**。

**MCP 暴露**：归因响应统一带 `sources: [{layer, patternId?, confidence}]`；
新增 `recon_list_patterns`（列模式库）、归因失败时模型可查模式库自助。

**工作量**：约 2 天（YAML 加载器 + L0 匹配器 + 15 个模式 + 测试 + MCP）。

### Phase K3：字段语义字典

**数据形态**：表级 YAML：

```yaml
tables:
  lago_invoice_fees:
    columns:
      amount:
        semantic: 净额（不含税），与 invoice.total 的口径差见 total 注释
        unit: 元（导入时已按 profile 从分换算）
      status:
        enum: { SETTLED: 已结算, PENDING: 未到结算日, VOIDED: 已作废 }
```

**引擎消费点**：
- `recon_describe_table` 升级：返回列 + 语义 + 枚举含义（模型写规则/解释差异的上下文质量直接提升）；
- 规则起草闸门：规则引用带"口径"标注的列时，比对两侧口径冲突（净额 vs 含税）直接 WARN——知识变成硬约束；
- 来源：importer 按 profile 顺带登记骨架 + 人工补录语义。

**工作量**：约 1.5 天（YAML 加载 + describe 增强 + 闸口 WARN + 测试）。

### Phase K4：厂商语义摘录 + SOP 外部化（已完成）

- Lago/Orb 的计费行为要点（分换算/勾稽/冲抵/作废语义）摘录进 profile 的
  `billingSemantics()` + `semanticsVersion()`（`lago-api-2026-11` / `orb-api-2026-11`），随版本重核；
- 出口：`ColumnProfiles.semanticsDigest(profileId)`；`recon_lago_sync` 拉完账单自动附带；
- 完整排查 SOP 放外部资料库，recon-lens 只留结构化摘录与 SOP（`docs/vendor-semantics.md`）。

**工作量**：半天（实际一天内与 K3 同日完成）。

## 四、实施顺序与理由

```
K1（1天）──► K2（2天）──► K3（1.5天）──► K4（0.5天）
   └ reasonId 选项即 K2 分类法 ┘
```

- **K1 最先**：不动知识格式、数据零成本、当周见效；且它的 reasonId 下拉正是 K2 分类法的第一个消费点——两个 Phase 咬合设计。
- **K2 其次**：模式的 match 特征（delta 比率）直接复用 K1 回流的定性数据校准区间。
- **K3/K4 随后**：独立、无依赖，穿插做都行。

## 五、验收口径（防"知识层做成摆设"）

1. **越用越准可量化**：kb 命中率计数器（已有）+ L0 联想命中率——跑一个月后"新差异 0 人工介入归因率"应上升；
2. **来源永远可追溯**：每条归因标注 layer + patternId/案例 ID，模型不背锅给人；
3. **知识不能污染引擎**：YAML 模式库加载失败 = 降级到现有 L1/L2/L3，绝不让坏知识挡住对账主链路；
4. **测试钉住**：每类消费点（lifecycle 回流、L0 匹配、describe 增强）都有端到端测试。

## 六、不做什么（明确排除）

- **不做向量库/嵌入检索**：案例级有 CauseKey 精确键，模式级有机械特征匹配，当前规模（单客户差异量级）轮不到向量检索；规模到了再说；
- **不做知识编辑 UI**：模式库/字典就是 YAML 文件（版本化 + 测试），改文件走 PR，别发明第二套规则治理；
- **不让 LLM 写知识库**：LLM 归因只能以 0.5 进 kb，且人工一改即覆盖——这条已有机制，保持。
