package main

import "testing"

func strp(s string) *string { return &s }

func TestCompareFieldString(t *testing.T) {
	rule := &FieldRule{Field: "status", Type: typeString}
	if compareField(rule, strp(" PAID "), strp("PAID")).different {
		t.Error("trim 后应相等")
	}
	ignore := &FieldRule{Field: "status", Type: typeString, IgnoreCase: true}
	if compareField(ignore, strp("Paid"), strp("PAID")).different {
		t.Error("ignoreCase 应相等")
	}
	if !compareField(rule, strp("Paid"), strp("PAID")).different {
		t.Error("默认区分大小写")
	}
}

func TestCompareFieldBoolean(t *testing.T) {
	// 对齐 Java Boolean.parseBoolean：只有忽略大小写的 "true" 是 true，其余全 false
	rule := &FieldRule{Field: "ok", Type: typeBool}
	for _, c := range []struct{ a, b string }{
		{"true", "TRUE"}, {"true", "True "}, {"false", "FALSE"}, {"yes", "no"},
	} {
		if compareField(rule, strp(c.a), strp(c.b)).different {
			t.Errorf("boolean %q vs %q 不应判差异", c.a, c.b)
		}
	}
	if !compareField(rule, strp("true"), strp("false")).different {
		t.Error("true vs false 应判差异")
	}
}

func TestCompareFieldDecimal(t *testing.T) {
	// 标度不同的相等值不算差异（对齐 BigDecimal 比较语义）
	rule := &FieldRule{Field: "amount", Type: typeDec}
	if compareField(rule, strp("2.00"), strp("2.0")).different {
		t.Error("2.00 vs 2.0 不应判差异")
	}
	// delta 渲染去尾零
	res := compareField(rule, strp("3.00"), strp("2.50"))
	if !res.different || res.delta.Render() != "0.5" {
		t.Errorf("3.00 vs 2.50: different=%v delta=%v", res.different, res.delta)
	}
	// ratio = delta/|right|
	if res.ratio == nil || res.ratio.Render() != "0.2" {
		t.Errorf("ratio=%v，期望 0.2", res.ratio)
	}
	// 一侧 null → 差异且无 delta
	res = compareField(rule, strp("1"), nil)
	if !res.different || res.delta != nil {
		t.Errorf("null 差异: different=%v delta=%v", res.different, res.delta)
	}
	// 数值类型但不是数字 → 差异不抛错
	if !compareField(rule, strp("N/A"), strp("1")).different {
		t.Error("N/A 应判差异")
	}
}

func TestCompareFieldTolerance(t *testing.T) {
	rule := &FieldRule{Field: "amount", Type: typeDec,
		Tolerance: "0.01", tolerance: mustDec(t, "0.01")}
	if compareField(rule, strp("10.00"), strp("10.005")).different {
		t.Error("|delta|=0.005 ≤ 0.01 不应判差异")
	}
	if !compareField(rule, strp("10.00"), strp("10.02")).different {
		t.Error("|delta|=0.02 > 0.01 应判差异")
	}
	// 相对容差：1% 内不报
	rule2 := &FieldRule{Field: "amount", Type: typeDec,
		TolerancePercent: "0.01", tolerancePercent: mustDec(t, "0.01")}
	if compareField(rule2, strp("100"), strp("100.9")).different {
		t.Error("相对差 0.9% ≤ 1% 不应判差异")
	}
	if !compareField(rule2, strp("100"), strp("101.1")).different {
		t.Error("相对差 1.1% > 1% 应判差异")
	}
	// 基数 0：相对差无意义，任何非零偏差判差异
	rule3 := &FieldRule{Field: "amount", Type: typeDec,
		TolerancePercent: "0.5", tolerancePercent: mustDec(t, "0.5")}
	if !compareField(rule3, strp("0"), strp("0.1")).different {
		t.Error("基数 0 时非零偏差应判差异")
	}
	// 两者都配：任一超限即差异
	rule4 := &FieldRule{Field: "amount", Type: typeDec,
		Tolerance: "0.01", tolerance: mustDec(t, "0.01"),
		TolerancePercent: "0.01", tolerancePercent: mustDec(t, "0.01")}
	if !compareField(rule4, strp("100"), strp("102")).different {
		t.Error("绝对差超限应判差异（任一超限即差异）")
	}
}

func TestCompareKeys(t *testing.T) {
	if compareKeys(typeDec, strp("9"), strp("10")) >= 0 {
		t.Error("数值键 9 < 10（字典序会错位，必须数值序）")
	}
	if compareKeys(typeDec, strp("10.00"), strp("10")) != 0 {
		t.Error("10.00 == 10")
	}
	if compareKeys(typeString, strp("a"), strp("b")) >= 0 {
		t.Error("字符串键 a < b")
	}
	if compareKeys(typeString, nil, strp("a")) >= 0 {
		t.Error("null 排最前")
	}
	if compareKeys(typeBool, strp("false"), strp("true")) >= 0 {
		t.Error("false < true（对齐 Boolean.compare）")
	}
}

func TestRenderValue(t *testing.T) {
	if got := renderValue(typeDec, strp("2.00")); got == nil || *got != "2" {
		t.Errorf("renderValue(2.00) = %v，期望 2", got)
	}
	if got := renderValue(typeString, strp("2.00")); got == nil || *got != "2.00" {
		t.Errorf("string 类型不解析数值: %v", got)
	}
	if renderValue(typeDec, nil) != nil {
		t.Error("nil 保持 nil")
	}
}

func TestCSVValueNullSemantics(t *testing.T) {
	for _, in := range []string{"", "  ", "\\N", "NULL", "null"} {
		if csvValue(in) != nil {
			t.Errorf("csvValue(%q) 应为 nil", in)
		}
	}
	if v := csvValue("NIL"); v == nil {
		t.Error("NIL 不是 null 字面量")
	}
}
