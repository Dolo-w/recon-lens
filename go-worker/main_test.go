package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 端到端测试：与 Java 侧 EventTimeReconTest 同族的用例，验证 worker 的
// 归并/缺失/重复键/事件时间/水位 PENDING/断点续跑全部语义。

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("写 %s: %v", path, err)
	}
}

func readJSONL(t *testing.T, path string) []Diff {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读 %s: %v", path, err)
	}
	var diffs []Diff
	for _, line := range stringLines(data) {
		var d Diff
		if err := json.Unmarshal([]byte(line), &d); err != nil {
			t.Fatalf("解析差异行 %q: %v", line, err)
		}
		diffs = append(diffs, d)
	}
	return diffs
}

func stringLines(data []byte) []string {
	var out []string
	start := 0
	for i, b := range data {
		if b == '\n' {
			if i > start {
				out = append(out, string(data[start:i]))
			}
			start = i + 1
		}
	}
	if start < len(data) {
		out = append(out, string(data[start:]))
	}
	return out
}

func baseSpec() map[string]any {
	return map[string]any{
		"jobId": "test-job",
		"left":  map[string]any{"label": "账单", "keyField": "order_no", "keyType": "string", "eventTime": "created_at"},
		"right": map[string]any{"label": "结算", "keyField": "order_no", "keyType": "string"},
		"compare": []any{
			map[string]any{"field": "amount", "type": "decimal", "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"},
			map[string]any{"field": "status", "type": "string", "diffType": "STATUS_MISMATCH", "severity": "MEDIUM"},
		},
		"missing":  map[string]any{"leftMissingDiffType": "BILL_MISSING", "rightMissingDiffType": "SETTLE_MISSING", "severity": "HIGH"},
		"duplicate": map[string]any{"diffType": "DUPLICATE_KEY", "severity": "HIGH"},
	}
}

func writeSpec(t *testing.T, dir string, spec map[string]any) string {
	t.Helper()
	b, _ := json.Marshal(spec)
	path := filepath.Join(dir, "spec.json")
	write(t, path, string(b))
	return path
}

func basicSpecJSON(t *testing.T, dir string) string {
	return writeSpec(t, dir, baseSpec())
}

// 主管道：字段差异（含金额标度无关）+ 双向缺失 + 重复键不配对 + 事件时间。
func TestRunEndToEnd(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount,status,created_at\n"+
		"A-001,2.00,PAID,2026-03-15 10:00:00\n"+
		"A-002,5,PAID,2026-03-15 11:00:00\n"+
		"A-004,7,PAID,2026-03-20 09:00:00\n"+
		"A-006,1,PAID,2026-03-01 09:00:00\n")
	write(t, right, "order_no,amount,status\n"+
		"A-001,2,PAID\n"+
		"A-002,4,PENDING\n"+
		"A-003,9,PAID\n"+
		"A-006,1,PAID\n"+
		"A-006,2,PAID\n")

	specPath := basicSpecJSON(t, dir)
	out := filepath.Join(dir, "diffs.jsonl")
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"),
	})
	if code != exitOK {
		t.Fatalf("exit=%d error=%s", code, stats.Error)
	}
	if stats.Matched != 2 {
		t.Errorf("matched=%d，期望 2（A-001、A-004）", stats.Matched)
	}
	diffs := readJSONL(t, out)
	// 期望差异：A-001 amount 2.00 vs 2（标度无关，不算）→ 实际差异：
	// A-002 amount 5 vs 4、A-002 status、A-003 缺左、A-004 缺右、A-006 重复键
	if len(diffs) != 5 {
		t.Fatalf("差异 %d 条，期望 5: %+v", len(diffs), diffs)
	}
	byType := map[string][]Diff{}
	for _, d := range diffs {
		byType[d.Type] = append(byType[d.Type], d)
	}
	if len(byType["AMOUNT_MISMATCH"]) != 1 || len(byType["STATUS_MISMATCH"]) != 1 ||
		len(byType["BILL_MISSING"]) != 1 || len(byType["SETTLE_MISSING"]) != 1 ||
		len(byType["DUPLICATE_KEY"]) != 1 {
		t.Fatalf("类型分布不对: %v", stats.ByType)
	}
	// 金额差异：delta = 1，ratio = 0.25（5 vs 4）
	am := byType["AMOUNT_MISMATCH"][0]
	if am.Delta == nil || *am.Delta != "1" || am.Ratio == nil || *am.Ratio != "0.25" {
		t.Errorf("金额差异 delta/ratio = %v/%v，期望 1/0.25", am.Delta, am.Ratio)
	}
	if am.LeftValue == nil || *am.LeftValue != "5" || am.RightValue == nil || *am.RightValue != "4" {
		t.Errorf("金额差异值 = %v/%v，期望 5/4（去尾零渲染）", am.LeftValue, am.RightValue)
	}
	// 事件时间：取左行 created_at
	if am.EventAt == nil || *am.EventAt != "2026-03-15 11:00:00" {
		t.Errorf("eventAt=%v，期望左行 created_at", am.EventAt)
	}
	// 缺失型差异：field 为空串，一侧值为描述
	bm := byType["BILL_MISSING"][0] // A-003 只有右表有
	if *bm.Field != "" || bm.RightValue == nil || bm.LeftValue != nil {
		t.Errorf("缺失型差异形态不对: %+v", bm)
	}
	if bm.EvidenceRight == nil || *bm.EvidenceRight == "" {
		t.Error("缺失型差异应带存在侧整行凭证")
	}
	// 重复键：只报差异不配对 —— A-006 右侧 2 条
	dup := byType["DUPLICATE_KEY"][0]
	if dup.LeftValue == nil || *dup.LeftValue != "账单 1 条(id=A-006)" {
		t.Errorf("重复键左侧摘要 = %v", dup.LeftValue)
	}
	if dup.RightValue == nil || *dup.RightValue != "结算 2 条(id=A-006/A-006)" {
		t.Errorf("重复键右侧摘要 = %v", dup.RightValue)
	}
	if stats.Scanned["left"] != 4 || stats.Scanned["right"] != 5 {
		t.Errorf("扫描数 = %v，期望 left=4 right=5", stats.Scanned)
	}
}

func TestRunWatermarkPending(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount,status,created_at\nA-001,1,PAID,2026-03-15\nA-002,2,PAID,2026-03-15\n")
	write(t, right, "order_no,amount,status\nA-001,1,PAID\n")
	specPath := basicSpecJSON(t, dir)
	out := filepath.Join(dir, "diffs.jsonl")
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"),
		// 右侧水位 11-01 早于就绪线 11-20 → 右侧未就绪 → 缺失标 PENDING
		RightWatermark: "2026-11-01T00:00:00Z", Deadline: "2026-11-20T00:00:00Z",
	})
	if code != exitOK {
		t.Fatalf("exit=%d error=%s", code, stats.Error)
	}
	diffs := readJSONL(t, out)
	found := false
	for _, d := range diffs {
		if d.Type == "SETTLE_MISSING_PENDING" {
			found = true
			if d.Explanation == nil || *d.Explanation == "" {
				t.Error("PENDING 差异应带解释")
			}
		}
	}
	if !found {
		t.Errorf("应出现 SETTLE_MISSING_PENDING: %+v", diffs)
	}
}

func TestRunUnorderedRejected(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount,status,created_at\nA-005,1,PAID,x\nA-002,2,PAID,x\n")
	write(t, right, "order_no,amount,status\nA-002,2,PAID\n")
	specPath := basicSpecJSON(t, dir)
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: filepath.Join(dir, "diffs.jsonl"), StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"),
	})
	if code != exitData {
		t.Fatalf("乱序数据应失败: exit=%d", code)
	}
	if stats != nil && stats.Shards.Committed > 0 {
		t.Error("预检失败的运行不应提交任何 shard")
	}
}

// 断点续跑：跑完全量 → resume 全部跳过 → 无新差异（幂等性验证）。
func TestResumeIdempotent(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount,status,created_at\n"+
		"A-001,2,PAID,x\nA-002,5,PAID,x\nA-003,9,PAID,x\nA-004,7,PAID,x\nA-005,3,PAID,x\n")
	write(t, right, "order_no,amount,status\n"+
		"A-001,2,PAID\nA-002,4,PENDING\nA-003,9,PAID\nA-005,3,PAID\n")
	specPath := basicSpecJSON(t, dir)

	cpPath := filepath.Join(dir, "checkpoint.json")
	out := filepath.Join(dir, "diffs.jsonl")
	// 小分片强制多个 shard，验证前缀提交粒度
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: cpPath, ShardSize: 2,
	})
	if code != exitOK {
		t.Fatalf("首次运行 exit=%d error=%s", code, stats.Error)
	}
	if stats.Shards.Total < 2 {
		t.Fatalf("应有多个分片: %+v", stats.Shards)
	}
	firstDiffs := readJSONL(t, out)
	cp, err := loadCheckpoint(cpPath)
	if err != nil {
		t.Fatalf("checkpoint: %v", err)
	}
	if cp.LastCommittedShard != stats.Shards.Total-1 {
		t.Errorf("checkpoint lastCommitted=%d，期望 %d", cp.LastCommittedShard, stats.Shards.Total-1)
	}
	if cp.OutOffset == 0 {
		t.Error("checkpoint 应记录输出偏移")
	}

	// resume：输出截断到同一 offset、全量跳过 → 不产出新差异
	code, stats2 := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats2.json"),
		CheckpointPath: cpPath, ShardSize: 2, Resume: true,
	})
	if code != exitOK {
		t.Fatalf("resume exit=%d error=%s", code, stats2.Error)
	}
	if stats2.Shards.Committed != 0 || stats2.Diffs != 0 {
		t.Errorf("resume 应零提交: committed=%d diffs=%d", stats2.Shards.Committed, stats2.Diffs)
	}
	if stats2.Skipped["left"] != 5 || stats2.Skipped["right"] != 4 {
		t.Errorf("resume 应跳过全部已提交键组: %v", stats2.Skipped)
	}
	secondDiffs := readJSONL(t, out)
	if len(secondDiffs) != len(firstDiffs) {
		t.Errorf("resume 后差异文件不应变化: %d vs %d", len(secondDiffs), len(firstDiffs))
	}
}

// 断点续跑（增量场景）：跑完 A 部分 → 右侧追加新键 → resume 只处理新键。
func TestResumeIncremental(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	specPath := basicSpecJSON(t, dir)
	cpPath := filepath.Join(dir, "checkpoint.json")
	out := filepath.Join(dir, "diffs.jsonl")

	write(t, left, "order_no,amount,status,created_at\nA-001,1,PAID,x\n")
	write(t, right, "order_no,amount,status\nA-001,1,PAID\n")
	code, _ := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: cpPath,
	})
	if code != exitOK {
		t.Fatal("首次运行应成功")
	}

	// 追加 B-001：resume 后只产出它的差异
	write(t, left, "order_no,amount,status,created_at\nA-001,1,PAID,x\nB-001,10,PAID,x\n")
	write(t, right, "order_no,amount,status\nA-001,1,PAID\n")
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats2.json"),
		CheckpointPath: cpPath, Resume: true,
	})
	if code != exitOK {
		t.Fatalf("resume exit=%d error=%s", code, stats.Error)
	}
	diffs := readJSONL(t, out)
	if len(diffs) != 1 || diffs[0].Type != "SETTLE_MISSING" || *diffs[0].BizKey != "B-001" {
		t.Errorf("增量 resume 应只产出 B-001 的缺失差异: %+v", diffs)
	}
}

// CSV 兼容性：BOM、分号分隔、引号内的逗号与换行。
func TestCSVQuirks(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	// 左侧：UTF-8 BOM + 分号分隔 + 字段内逗号
	write(t, left, "\xEF\xBB\xBForder_no;amount;status;created_at\n"+
		"\"A,001\";1;PAID;x\nA-002;2;\"PAID\nLATE\";x\n")
	write(t, right, "order_no,amount,status\n"+
		"\"A,001\",1,PAID\n"+
		"\"A-002\",\"2\",\"PAID\nLATE\"\n")
	specPath := basicSpecJSON(t, dir)
	out := filepath.Join(dir, "diffs.jsonl")
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"),
	})
	if code != exitOK {
		t.Fatalf("exit=%d error=%s", code, stats.Error)
	}
	if stats.Matched != 2 {
		t.Errorf("引号/BOM 兼容失败 matched=%d", stats.Matched)
	}
	diffs := readJSONL(t, out)
	if len(diffs) != 0 {
		t.Errorf("两侧一致应无差异: %+v", diffs)
	}
}

// 数值业务键：按数值序归并（9 < 10），且 10 与 10.00 是同一个键。
func TestNumericKey(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount\n9,1\n10,2\n")
	write(t, right, "order_no,amount\n9,1\n10.00,2\n")
	spec := baseSpec()
	spec["left"] = map[string]any{"label": "账单", "keyField": "order_no", "keyType": "decimal"}
	spec["right"] = map[string]any{"label": "结算", "keyField": "order_no", "keyType": "decimal"}
	spec["compare"] = []any{
		map[string]any{"field": "amount", "type": "decimal", "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"},
	}
	specPath := writeSpec(t, dir, spec)
	out := filepath.Join(dir, "diffs.jsonl")
	code, stats := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"),
	})
	if code != exitOK {
		t.Fatalf("exit=%d error=%s", code, stats.Error)
	}
	if stats.Matched != 2 {
		t.Errorf("数值键归并 matched=%d，期望 2", stats.Matched)
	}
	if diffs := readJSONL(t, out); len(diffs) != 0 {
		t.Errorf("键 10 与 10.00 应视为同键（无差异）: %+v", diffs)
	}
}

func TestSpecDefaultsAndValidation(t *testing.T) {
	dir := t.TempDir()
	// 缺 keyField → 启动报错
	spec := map[string]any{"left": map[string]any{"keyField": "k"}, "right": map[string]any{}}
	path := writeSpec(t, dir, spec)
	if _, err := loadSpec(path); err == nil {
		t.Error("缺 right.keyField 应报错")
	}
	// 默认值补齐
	spec2 := baseSpec()
	delete(spec2, "missing")
	path2 := writeSpec(t, dir, spec2)
	s, err := loadSpec(path2)
	if err != nil {
		t.Fatalf("loadSpec: %v", err)
	}
	if s.Missing.LeftMissingDiffType != "LEFT_MISSING" || s.Duplicate.DiffType != "DUPLICATE_KEY" {
		t.Errorf("默认值缺失: %+v", s.Missing)
	}
	if len(s.Compare) > 0 && s.Compare[0].DiffType == "" {
		t.Error("compare 的 diffType 应自动生成")
	}
}

// CSV 输出格式：表头 + 引号转义。
func TestCSVOutputFormat(t *testing.T) {
	dir := t.TempDir()
	left := filepath.Join(dir, "left.csv")
	right := filepath.Join(dir, "right.csv")
	write(t, left, "order_no,amount,status,created_at\nA-001,1,PAID,x\n")
	write(t, right, "order_no,amount,status\nA-001,2,PAID\n")
	specPath := basicSpecJSON(t, dir)
	out := filepath.Join(dir, "diffs.csv")
	code, _ := Run(Options{
		SpecPath: specPath, LeftPath: left, RightPath: right,
		OutPath: out, StatsPath: filepath.Join(dir, "stats.json"),
		CheckpointPath: filepath.Join(dir, "checkpoint.json"), Format: "csv",
	})
	if code != exitOK {
		t.Fatal("csv 输出应成功")
	}
	data, _ := os.ReadFile(out)
	lines := stringLines(data)
	for i := range lines {
		lines[i] = strings.TrimRight(lines[i], "\r")
	}
	if len(lines) != 2 {
		t.Fatalf("csv 应有表头+1条差异: %d 行", len(lines))
	}
	if lines[0] != "job_id,shard_id,diff_type,severity,biz_key,field,left_value,right_value,delta,ratio,event_at,explanation,evidence_left,evidence_right" {
		t.Errorf("csv 表头不对: %s", lines[0])
	}
}
