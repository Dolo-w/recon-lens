package main

import "testing"

// decimal 语义测试：逐条对齐 Java BigDecimal 行为（金额比对的生命线）。

func mustDec(t *testing.T, s string) *Decimal {
	t.Helper()
	d, ok := ParseDecimal(s)
	if !ok {
		t.Fatalf("ParseDecimal(%q) 解析失败", s)
	}
	return d
}

func TestParseDecimal(t *testing.T) {
	cases := []struct {
		in    string
		ok    bool
		scale int // 期望 scale（0 表示没断言）
	}{
		{"12.34", true, 2},
		{"12", true, 0},
		{" 1,234.50 ", true, 2}, // 千分位 + 空白
		{"-1.5", true, 1},
		{"+0.5", true, 1},
		{"1.5e3", true, 0}, // 1500
		{"1.5E-3", true, 4}, // 0.0015（与 Java new BigDecimal("1.5E-3").scale()==4 一致）
		{"", false, 0},
		{"abc", false, 0},
		{"12.3.4", false, 0},
		{"--5", false, 0},
		{".5", true, 1},
		{"5.", true, 0},
	}
	for _, c := range cases {
		d, ok := ParseDecimal(c.in)
		if ok != c.ok {
			t.Errorf("ParseDecimal(%q) ok=%v 期望 %v", c.in, ok, c.ok)
			continue
		}
		if ok && c.scale != 0 && d.scale != c.scale {
			t.Errorf("ParseDecimal(%q) scale=%d 期望 %d", c.in, d.scale, c.scale)
		}
	}
}

func TestDecimalCmp(t *testing.T) {
	// 10.00 与 10 相等（compareTo 不比标度）
	if mustDec(t, "10.00").Cmp(mustDec(t, "10")) != 0 {
		t.Error("10.00 应等于 10")
	}
	if mustDec(t, "9").Cmp(mustDec(t, "10")) >= 0 {
		t.Error("9 应小于 10（数值序，不是字典序）")
	}
	if mustDec(t, "-2").Cmp(mustDec(t, "-1")) >= 0 {
		t.Error("-2 应小于 -1")
	}
	if mustDec(t, "0.1").Cmp(mustDec(t, "0.0999999")) <= 0 {
		t.Error("0.1 应大于 0.0999999")
	}
}

func TestDecimalSub(t *testing.T) {
	// 3.00 - 2.5 = 0.50，渲染去尾零 = "0.5"
	d := mustDec(t, "3.00").Sub(mustDec(t, "2.5"))
	if s := d.Render(); s != "0.5" {
		t.Errorf("3.00-2.5 = %q，期望 0.5", s)
	}
	// 2 - 5 = -3
	if s := mustDec(t, "2").Sub(mustDec(t, "5")).Render(); s != "-3" {
		t.Errorf("2-5 = %q，期望 -3", s)
	}
	// 0 渲染
	if s := mustDec(t, "1.5").Sub(mustDec(t, "1.5")).Render(); s != "0" {
		t.Errorf("1.5-1.5 = %q，期望 0（不是 -0/0.0）", s)
	}
}

func TestDecimalDivRatioHalfUp(t *testing.T) {
	// HALF_UP：恰好一半进位（对齐 Java HALF_UP）
	r := DivRatio(mustDec(t, "1.0000005"), mustDec(t, "1"))
	if r == nil || r.Render() != "1.000001" { // 1.0000005 HALF_UP 到 6 位
		t.Errorf("1.0000005/1 比例 = %v，期望 1.000001", r)
	}
	// 负数
	if s := DivRatio(mustDec(t, "-1"), mustDec(t, "2")).Render(); s != "-0.5" {
		t.Errorf("-1/2 = %q，期望 -0.5", s)
	}
	// 除数为 0 → nil（对齐 Java ratio==null）
	if DivRatio(mustDec(t, "1"), mustDec(t, "0")) != nil {
		t.Error("除数 0 应返回 nil")
	}
	// 相对差与标度无关：0.05/2.50 与 5/250 相同
	a := DivRatio(mustDec(t, "0.05"), mustDec(t, "2.50"))
	b := DivRatio(mustDec(t, "5"), mustDec(t, "250"))
	if a.Render() != b.Render() {
		t.Errorf("0.05/2.50=%s 与 5/250=%s 应一致", a.Render(), b.Render())
	}
}

func TestDecimalRender(t *testing.T) {
	cases := []struct{ in, want string }{
		{"12.3400", "12.34"},
		{"2.00", "2"},
		{"0.00100", "0.001"},
		{"-1.500", "-1.5"},
		{"1000", "1000"},
		{"0.000", "0"},
		{"100.5", "100.5"},
	}
	for _, c := range cases {
		if got := mustDec(t, c.in).Render(); got != c.want {
			t.Errorf("Render(%q) = %q，期望 %q", c.in, got, c.want)
		}
	}
	// 整数部分为 0 的小数：big.Int 语义容易在这里掉前导零
	if got := mustDec(t, "-0.5").Render(); got != "-0.5" {
		t.Errorf("Render(-0.5) = %q，期望 -0.5", got)
	}
}
