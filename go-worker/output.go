package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
)

// Diff 对齐 Java domain.DiffRecord（事件级对账后的字段集）：
// 字段为空串表示记录级差异（单边缺失/重复键），非空表示字段值不一致。
// eventAt 是业务发生时刻的原样字符串（不做时区翻译，解析交给计价侧）。
type Diff struct {
	JobID       string  `json:"jobId"`
	ShardID     int     `json:"shardId"`
	Type        string  `json:"diffType"`
	Severity    string  `json:"severity"`
	BizKey      *string `json:"bizKey"`
	Field       *string `json:"field"`
	LeftValue   *string `json:"leftValue,omitempty"`
	RightValue  *string `json:"rightValue,omitempty"`
	Delta       *string `json:"deltaValue,omitempty"`
	Ratio       *string `json:"deltaRatio,omitempty"`
	Explanation *string `json:"explanation,omitempty"`
	EventAt     *string `json:"eventAt,omitempty"`
	// 原始行凭证（整行 JSON，超长截断）：源数据变了也不影响复盘口径
	EvidenceLeft  *string `json:"evidenceLeft,omitempty"`
	EvidenceRight *string `json:"evidenceRight,omitempty"`
}

// diffWriter 抽象两种输出格式：JSONL（逐行 JSON，机器友好）与
// CSV（对齐证据包列，可直接发给客户/财务）。
type diffWriter interface {
	write(d *Diff) error
	flush() error
	offset() int64
}

type jsonlWriter struct {
	w *bufio.Writer
	f *os.File
	n int64
}

func newJSONLWriter(f *os.File) *jsonlWriter {
	return &jsonlWriter{w: bufio.NewWriterSize(f, 1<<18), f: f}
}

func (w *jsonlWriter) write(d *Diff) error {
	b, err := json.Marshal(d)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	n, err := w.w.Write(b)
	w.n += int64(n)
	return err
}

func (w *jsonlWriter) offset() int64 { return w.n }

func (w *jsonlWriter) flush() error { return w.w.Flush() }

// CSV 列对齐证据包（evidence-pack.csv）的核心列：差异本身 + 两侧凭证。
var csvHeader = []string{"job_id", "shard_id", "diff_type", "severity", "biz_key", "field",
	"left_value", "right_value", "delta", "ratio", "event_at", "explanation",
	"evidence_left", "evidence_right"}

type csvDWriter struct {
	w *bufio.Writer
	f *os.File
	n int64
}

func newCSVWriter(f *os.File) (*csvDWriter, error) {
	w := &csvDWriter{w: bufio.NewWriterSize(f, 1<<18), f: f}
	if err := w.writeRecord(csvHeader); err != nil {
		return nil, err
	}
	return w, nil
}

func sval(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func (w *csvDWriter) write(d *Diff) error {
	return w.writeRecord([]string{
		d.JobID,
		fmt.Sprintf("%d", d.ShardID),
		d.Type,
		d.Severity,
		sval(d.BizKey),
		sval(d.Field),
		sval(d.LeftValue),
		sval(d.RightValue),
		sval(d.Delta),
		sval(d.Ratio),
		sval(d.EventAt),
		sval(d.Explanation),
		sval(d.EvidenceLeft),
		sval(d.EvidenceRight),
	})
}

// writeRecord RFC 4180：值含引号/分隔符/换行时整体加引号，内部引号翻倍。
func (w *csvDWriter) writeRecord(rec []string) error {
	var sb strings.Builder
	for i, v := range rec {
		if i > 0 {
			sb.WriteByte(',')
		}
		if strings.ContainsAny(v, ",\"\r\n") {
			sb.WriteByte('"')
			sb.WriteString(strings.ReplaceAll(v, "\"", "\"\""))
			sb.WriteByte('"')
		} else {
			sb.WriteString(v)
		}
	}
	sb.WriteString("\r\n")
	n, err := w.w.WriteString(sb.String())
	w.n += int64(n)
	return err
}

func (w *csvDWriter) offset() int64 { return w.n }

func (w *csvDWriter) flush() error { return w.w.Flush() }

// checkpoint 是"最大可提交前缀"的提交点：lastCommittedShard 之前的 shard
// 全部完整写进了 out 文件的 [0, outOffset) 字节区间；nextKey 是最后一个已提交
// shard 的末键。崩溃/失败后用 --resume 恢复：out 截断到 outOffset（丢弃未提交
// shard 可能残留的半截差异），扫描跳过 <= nextKey 的键组，从断点继续。
// 学的是 Lago events-processor 的手动提交语义：提交是显式边界，不是"写到哪算哪"。
type checkpoint struct {
	LastCommittedShard int    `json:"lastCommittedShard"`
	OutOffset          int64  `json:"outOffset"`
	NextKey            string `json:"nextKey"`
	NextKeyType        string `json:"nextKeyType"`
	Diffs              int64  `json:"diffs"`
	WrittenAt          string `json:"writtenAt"`
}

func saveCheckpoint(path string, cp *checkpoint) error {
	cp.WrittenAt = time.Now().Format(time.RFC3339)
	b, err := json.MarshalIndent(cp, "", "  ")
	if err != nil {
		return err
	}
	// 原子落盘：先写临时文件再 rename，绝不留半截 checkpoint
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func loadCheckpoint(path string) (*checkpoint, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cp checkpoint
	if err := json.Unmarshal(b, &cp); err != nil {
		return nil, fmt.Errorf("checkpoint 文件损坏: %v", err)
	}
	return &cp, nil
}

// runStats 是最终统计。matched/diffRows 只统计已提交 shard —— 部分提交时
// "已提交范围"内的口径是准确的，未提交的活在 resumeInfo 里，绝不混着报。
type runStats struct {
	Status      string            `json:"status"` // COMPLETED | PARTIAL
	Scanned     map[string]int64  `json:"scanned"`
	Skipped     map[string]int64  `json:"skipped,omitempty"`
	Matched     int64             `json:"matched"`
	DiffRows    int64             `json:"diffRows"`
	ByType      map[string]int64  `json:"byType,omitempty"`
	ByField     map[string]int64  `json:"byField,omitempty"`
	Shards      shardStats        `json:"shards"`
	Diffs       int64             `json:"diffs"`
	Out         string            `json:"out"`
	ResumeHint  string            `json:"resumeHint,omitempty"`
	StartedAt   string            `json:"startedAt"`
	FinishedAt  string            `json:"finishedAt"`
	Error       string            `json:"error,omitempty"`
}

type shardStats struct {
	Committed          int `json:"committed"`
	Total              int `json:"total"`
	LastCommittedShard int `json:"lastCommittedShard"`
	FirstFailedShard   int `json:"firstFailedShard,omitempty"`
}

func writeStats(path string, s *runStats) error {
	s.FinishedAt = time.Now().Format(time.RFC3339)
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

// parseInstant 支持 RFC3339（2026-11-21T12:00:00Z）与
// yyyy-MM-dd[ HH:mm[:ss]]（对齐计价侧 parseEventAt 的宽容度）。
func parseInstant(s string) (time.Time, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02 15:04:05", "2006-01-02 15:04", "2006-01-02"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

// buildReadiness 对齐 ReconEngine.Readiness：水位早于就绪线 → 该侧未就绪。
// 水位解析失败 fail-loud —— 水位判断错会把真缺失标成 PENDING，比报错危险得多。
func buildReadiness(lw, rw, deadline string) (readiness, error) {
	rd := readiness{leftWatermark: lw, rightWatermark: rw, deadline: deadline}
	if deadline == "" {
		return rd, nil
	}
	dl, ok := parseInstant(deadline)
	if !ok {
		return rd, fmt.Errorf("deadline \"%s\" 解析不了（支持 RFC3339 或 yyyy-MM-dd[ HH:mm[:ss]]）", deadline)
	}
	if lw != "" {
		t, ok := parseInstant(lw)
		if !ok {
			return rd, fmt.Errorf("left-watermark \"%s\" 解析不了（支持 RFC3339 或 yyyy-MM-dd[ HH:mm[:ss]]）", lw)
		}
		rd.leftPending = t.Before(dl)
	}
	if rw != "" {
		t, ok := parseInstant(rw)
		if !ok {
			return rd, fmt.Errorf("right-watermark \"%s\" 解析不了（支持 RFC3339 或 yyyy-MM-dd[ HH:mm[:ss]]）", rw)
		}
		rd.rightPending = t.Before(dl)
	}
	return rd, nil
}

var _ io.Writer = (*bufio.Writer)(nil)
