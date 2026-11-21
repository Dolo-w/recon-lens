package main

import (
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"strings"
)

// 任务规格：recon-lens 规则（RuleSpec）里"可比对子集"的 JSON 形态。
// 取数（table/sql/连接器）与分片策略留在 Java 侧；worker 只管"两侧已按业务键
// 升序排好的 CSV"怎么比对。字段命名与 YAML 规则一一对应，方便从规则复制。
type Spec struct {
	JobID       string        `json:"jobId,omitempty"`
	Left        SideSpec      `json:"left"`
	Right       SideSpec      `json:"right"`
	Compare     []FieldRule   `json:"compare"`
	Missing     MissingSpec   `json:"missing"`
	Duplicate   DuplicateSpec `json:"duplicate"`
	MaxAttempts int           `json:"maxAttempts,omitempty"`
}

type SideSpec struct {
	Label     string    `json:"label,omitempty"`
	KeyField  string    `json:"keyField"`
	KeyType   FieldType `json:"keyType,omitempty"`   // string | boolean | long | decimal，默认 string
	EventTime string    `json:"eventTime,omitempty"` // 事件时间列（事件级对账）
}

type FieldRule struct {
	Field            string    `json:"field"`
	Type             FieldType `json:"type,omitempty"` // string | boolean | long | decimal，默认 string
	IgnoreCase       bool      `json:"ignoreCase,omitempty"`
	Tolerance        string    `json:"tolerance,omitempty"`        // 绝对容差（十进制串）
	TolerancePercent string    `json:"tolerancePercent,omitempty"` // 相对容差（十进制串）
	DiffType         string    `json:"diffType,omitempty"`         // 默认 FIELD_<大写列名>_MISMATCH
	Severity         string    `json:"severity,omitempty"`         // LOW | MEDIUM | HIGH，默认 MEDIUM

	tolerance        *Decimal // 解析后的内部形态（json 之外的派生字段）
	tolerancePercent *Decimal
}

type MissingSpec struct {
	LeftMissingDiffType  string `json:"leftMissingDiffType,omitempty"`
	RightMissingDiffType string `json:"rightMissingDiffType,omitempty"`
	Severity             string `json:"severity,omitempty"`
}

type DuplicateSpec struct {
	DiffType string `json:"diffType,omitempty"`
	Severity string `json:"severity,omitempty"`
}

type FieldType string

const (
	typeString FieldType = "string"
	typeBool   FieldType = "boolean"
	typeLong   FieldType = "long"
	typeDec    FieldType = "decimal"
)

const defaultSeverity = "MEDIUM"

// loadSpec 读规格文件并补默认值、做结构性校验。
// 校验原则与 Java 规则闸门一致：把配置错误变成启动时一条可读的失败原因，
// 而不是跑到一半炸掉。
func loadSpec(path string) (*Spec, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读不了规格文件 %s: %v", path, err)
	}
	var spec Spec
	if err := json.Unmarshal(data, &spec); err != nil {
		return nil, fmt.Errorf("规格文件不是合法 JSON: %v", err)
	}
	if err := spec.normalize(); err != nil {
		return nil, err
	}
	if err := spec.validate(); err != nil {
		return nil, err
	}
	return &spec, nil
}

func (s *Spec) normalize() error {
	if s.Left.KeyField == "" {
		return fmt.Errorf("spec.left.keyField 必填（两侧 CSV 按它升序排列，归并比对的正确性依赖这个前提）")
	}
	if s.Right.KeyField == "" {
		return fmt.Errorf("spec.right.keyField 必填")
	}
	s.Left.KeyType = normalizeFieldType(s.Left.KeyType)
	s.Right.KeyType = normalizeFieldType(s.Right.KeyType)
	if s.Left.Label == "" {
		s.Left.Label = "left"
	}
	if s.Right.Label == "" {
		s.Right.Label = "right"
	}
	if s.MaxAttempts <= 0 {
		s.MaxAttempts = 1
	}
	if s.Missing.LeftMissingDiffType == "" {
		s.Missing.LeftMissingDiffType = "LEFT_MISSING"
	}
	if s.Missing.RightMissingDiffType == "" {
		s.Missing.RightMissingDiffType = "RIGHT_MISSING"
	}
	if s.Missing.Severity == "" {
		s.Missing.Severity = "HIGH"
	}
	if s.Duplicate.DiffType == "" {
		s.Duplicate.DiffType = "DUPLICATE_KEY"
	}
	if s.Duplicate.Severity == "" {
		s.Duplicate.Severity = "HIGH"
	}
	for i := range s.Compare {
		if err := s.Compare[i].normalize(); err != nil {
			return err
		}
	}
	return nil
}

func (f *FieldRule) normalize() error {
	if f.Field == "" {
		return fmt.Errorf("spec.compare[].field 必填")
	}
	f.Type = normalizeFieldType(f.Type)
	if f.DiffType == "" {
		f.DiffType = "FIELD_" + strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(f.Field), " ", "_")) + "_MISMATCH"
	}
	if f.Severity == "" {
		f.Severity = defaultSeverity
	}
	if f.Type == typeBool || f.Type == typeString {
		if f.Tolerance != "" || f.TolerancePercent != "" {
			return fmt.Errorf("spec.compare.%s: 容差只对数值类型（long/decimal）有意义，%s 类型请去掉 tolerance", f.Field, f.Type)
		}
		return nil
	}
	if f.Tolerance != "" {
		t, ok := ParseDecimal(f.Tolerance)
		if !ok {
			return fmt.Errorf("spec.compare.%s: tolerance \"%s\" 不是合法十进制数", f.Field, f.Tolerance)
		}
		f.tolerance = t
	}
	if f.TolerancePercent != "" {
		p, ok := ParseDecimal(f.TolerancePercent)
		if !ok || p.Cmp(&Decimal{mant: big.NewInt(0), scale: 0}) < 0 {
			return fmt.Errorf("spec.compare.%s: tolerancePercent \"%s\" 必须是非负十进制数", f.Field, f.TolerancePercent)
		}
		f.tolerancePercent = p
	}
	return nil
}

func normalizeFieldType(t FieldType) FieldType {
	switch strings.ToLower(strings.TrimSpace(string(t))) {
	case "string", "":
		return typeString
	case "boolean":
		return typeBool
	case "long", "int", "integer":
		return typeLong
	case "decimal", "numeric", "money", "amount":
		return typeDec
	default:
		return t // 未知类型留给 validate 报错
	}
}

// severityOf 对齐 Severity.from：空白/未知值回落 MEDIUM，绝不因为 severity 打错字停整个任务。
func severityOf(raw string) string {
	switch strings.ToUpper(strings.TrimSpace(raw)) {
	case "LOW":
		return "LOW"
	case "HIGH":
		return "HIGH"
	default:
		return defaultSeverity
	}
}

func (s *Spec) validate() error {
	for _, side := range []*SideSpec{&s.Left, &s.Right} {
		if side.KeyType != typeString && side.KeyType != typeBool && side.KeyType != typeLong && side.KeyType != typeDec {
			return fmt.Errorf("keyType \"%s\" 不认识（可用: string/boolean/long/decimal），键比较必须与 CSV 的 ORDER BY 语义一致，宁可启动失败", side.KeyType)
		}
	}
	for i := range s.Compare {
		f := &s.Compare[i]
		if f.Type != typeString && f.Type != typeBool && f.Type != typeLong && f.Type != typeDec {
			return fmt.Errorf("compare.%s: type \"%s\" 不认识（可用: string/boolean/long/decimal）", f.Field, f.Type)
		}
	}
	return nil
}
