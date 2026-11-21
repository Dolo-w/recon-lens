package main

import (
	"math/big"
	"strings"
)

// Decimal 是精确十进制数：value = mant * 10^(-scale)。
//
// 为什么不用 float64：金额比对里 0.1+0.2 != 0.3 会制造"假差异"，
// recon-lens 的 Java 侧（ValueComparator）全部走 BigDecimal，这里必须对齐。
// 为什么不用第三方库：worker 的卖点是"扔进去就跑"的单二进制，
// 依赖越少越好——比较/减法/HALF_UP 除法就这几十行，自己写比拉依赖更可控。
//
// 语义逐条对齐 Java：
//   - ParseDecimal  ~ ValueComparator.toDecimal（trim、去千分位逗号、科学计数法；解析失败返回 false）
//   - Cmp           ~ BigDecimal.compareTo
//   - Sub           ~ BigDecimal.subtract（结果 scale = 两侧较大值）
//   - DivRatio      ~ delta.divide(r.abs(), 6, HALF_UP)
//   - Render        ~ BigDecimal.stripTrailingZeros().toPlainString()
type Decimal struct {
	mant  *big.Int // 有效数字（含符号）
	scale int      // 小数位数（>=0；exp 前移可能产生，Parse 里保证不为负）
}

var ten = big.NewInt(10)

func pow10(n int) *big.Int {
	return new(big.Int).Exp(ten, big.NewInt(int64(n)), nil)
}

// ParseDecimal 解析十进制文本。失败返回 (nil, false)——与 Java toDecimal 返回 null 一致，
// 调用方把"配置了数值类型但数据不是数字"判为差异而不是报错，保证整个任务能跑完。
// 接受：前后空白、千分位逗号、正负号、可选科学计数法（1.5e3 / 1.5E-3）。
func ParseDecimal(s string) (*Decimal, bool) {
	text := strings.TrimSpace(s)
	text = strings.ReplaceAll(text, ",", "")
	if text == "" {
		return nil, false
	}
	// 拆科学计数法
	negExp := false
	if i := strings.IndexAny(text, "eE"); i >= 0 {
		expText := text[i+1:]
		text = text[:i]
		if expText == "" {
			return nil, false
		}
		if expText[0] == '+' {
			expText = expText[1:]
		} else if expText[0] == '-' {
			negExp = true
			expText = expText[1:]
		}
		if expText == "" || !allDigits(expText) {
			return nil, false
		}
		exp := parseSmallInt(expText)
		if exp < 0 {
			return nil, false // 指数过大，拒绝而不是猜
		}
		if negExp {
			exp = -exp
		}
		text, _ = shiftExp(text, exp)
	}
	neg := false
	if len(text) > 0 && (text[0] == '+' || text[0] == '-') {
		neg = text[0] == '-'
		text = text[1:]
	}
	intPart, fracPart := text, ""
	if i := strings.IndexByte(text, '.'); i >= 0 {
		intPart, fracPart = text[:i], text[i+1:]
	}
	if intPart == "" && fracPart == "" {
		return nil, false
	}
	// fracPart 为空 = 没有小数点（"1000"）或小数点后为空（"5."）；intPart 为空
	// = 小数点前为空（".5"）。空段豁免，非空段必须全是数字
	if (intPart != "" && !allDigits(intPart)) || (fracPart != "" && !allDigits(fracPart)) {
		return nil, false
	}
	digits := intPart + fracPart
	mant, ok := new(big.Int).SetString(digits, 10)
	if !ok {
		return nil, false
	}
	if neg {
		mant.Neg(mant)
	}
	return &Decimal{mant: mant, scale: len(fracPart)}, true
}

func allDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return len(s) > 0
}

func parseSmallInt(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		n = n*10 + int(s[i]-'0')
		if n > 1_000_000 {
			return -1
		}
	}
	return n
}

// shiftExp 把科学计数法的指数并进小数点位置：1.5e3 -> "1500"，1.5e-3 -> "0.0015"。
// 返回移位后的纯十进制文本。指数为负时最多补 scale 位（超过的精度丢弃，
// 与 BigDecimal 的含义不符 —— 所以 Parse 限制 |exp| 不过千，避免静默截断）。
func shiftExp(text string, exp int) (string, bool) {
	dot := strings.IndexByte(text, '.')
	var intPart, fracPart string
	if dot >= 0 {
		intPart, fracPart = text[:dot], text[dot+1:]
	} else {
		intPart, fracPart = text, ""
	}
	if exp > 0 {
		// 小数点右移
		for exp > 0 {
			if fracPart != "" {
				intPart += fracPart[:1]
				fracPart = fracPart[1:]
			} else {
				intPart += "0"
			}
			exp--
		}
	} else if exp < 0 {
		// 小数点左移
		for exp < 0 {
			if intPart != "" {
				fracPart = intPart[len(intPart)-1:] + fracPart
				intPart = intPart[:len(intPart)-1]
			} else {
				fracPart = "0" + fracPart
			}
			exp++
		}
	}
	if intPart == "" {
		intPart = "0"
	}
	if fracPart != "" {
		return intPart + "." + fracPart, true
	}
	return intPart, true
}

// Sign 返回符号（-1/0/1），对齐 BigDecimal.signum。
func (d *Decimal) Sign() int { return d.mant.Sign() }

// Cmp 对齐 BigDecimal.compareTo：只比数值，不比 scale（10.00 与 10 相等）。
func (d *Decimal) Cmp(b *Decimal) int {
	m1 := new(big.Int).Set(d.mant)
	m2 := new(big.Int).Set(b.mant)
	if d.scale < b.scale {
		m1.Mul(m1, pow10(b.scale-d.scale))
	} else if b.scale < d.scale {
		m2.Mul(m2, pow10(d.scale-b.scale))
	}
	return m1.Cmp(m2)
}

// Sub 返回 a - b。scale 取两侧较大值（与 BigDecimal.subtract 相同约定）。
func (d *Decimal) Sub(b *Decimal) *Decimal {
	scale := d.scale
	if b.scale > scale {
		scale = b.scale
	}
	m1 := scaleMant(d, scale)
	m2 := scaleMant(b, scale)
	return &Decimal{mant: new(big.Int).Sub(m1, m2), scale: scale}
}

func scaleMant(d *Decimal, scale int) *big.Int {
	m := new(big.Int).Set(d.mant)
	if d.scale < scale {
		m.Mul(m, pow10(scale-d.scale))
	}
	return m
}

// DivRatio 计算 delta / |denom|，结果固定 6 位小数、HALF_UP 舍入（对齐 RATIO_SCALE = 6）。
// denom 为 0 时返回 nil —— 基数为零时相对差没有意义，Java 侧同样给 null。
// ratio 与各自的 scale 无关：
//
//	delta/|denom| = (dm/10^ds) / (|dm2|/10^bs) = dm * 10^bs / (|dm2| * 10^ds)
func DivRatio(delta, denom *Decimal) *Decimal {
	den := new(big.Int).Mul(absInt(denom.mant), pow10(delta.scale))
	if den.Sign() == 0 {
		return nil
	}
	// 先把分子放大 10^6 再除：整数商会丢掉小数部分，
	// HALF_UP 必须发生在第 6 位小数上（对齐 delta.divide(r.abs(), 6, HALF_UP)）
	num := new(big.Int).Mul(delta.mant, pow10(denom.scale))
	num.Mul(num, pow10(6))
	neg := num.Sign() < 0
	num.Abs(num)
	quo, rem := new(big.Int).QuoRem(num, den, new(big.Int))
	rem.Lsh(rem, 1)
	if rem.Cmp(den) >= 0 {
		quo.Add(quo, big.NewInt(1)) // HALF_UP：恰好一半也进位（与 Java HALF_UP 一致）
	}
	if neg {
		quo.Neg(quo)
	}
	return &Decimal{mant: quo, scale: 6}
}

func absInt(m *big.Int) *big.Int {
	return new(big.Int).Abs(m)
}

// Render 输出去尾零的十进制串（stripTrailingZeros().toPlainString()）。
// 零渲染为 "0"，避免 "-0" 或 "0.00" 污染差异展示。
func (d *Decimal) Render() string {
	m := new(big.Int).Set(d.mant)
	scale := d.scale
	if m.Sign() == 0 {
		return "0"
	}
	neg := m.Sign() < 0
	m.Abs(m)
	zero := big.NewInt(0)
	for scale > 0 {
		rem := new(big.Int).Rem(m, ten)
		if rem.Cmp(zero) != 0 {
			break
		}
		m.Quo(m, ten) // Rem 非零才到这里，整除无舍入
		scale--
	}
	// 绝对值渲染后再补符号：负号混进整数位会让前导零计算错位（"-1" scale=1
	// 会被切成 "-.1" 而不是 "-0.1"）
	s := m.String()
	if scale > 0 {
		if scale >= len(s) {
			s = strings.Repeat("0", scale-len(s)+1) + s
		}
		s = s[:len(s)-scale] + "." + s[len(s)-scale:]
	}
	if neg {
		s = "-" + s
	}
	return s
}
