package main

import (
	"math/big"
	"strings"
)

// Row：一行数据。列名统一小写（对齐 Java Row.normalize），键值可能是 nil（CSV 空语义）。
type Row struct {
	cols []string
	vals map[string]*string
}

func (r *Row) get(col string) *string {
	if r == nil {
		return nil
	}
	return r.vals[strings.ToLower(strings.TrimSpace(col))]
}

// rowJSON 整行紧凑 JSON（差异原始凭证用）。列顺序保持文件顺序，值全为字符串
// 或 null（与 Java Row.toJson 同口径）。超过 maxChars 整体截断并带标记 ——
// 凭证是给人复盘的，不是无损备份：宽行截断比把内存顶爆更重要。
func (r *Row) rowJSON(maxChars int) string {
	if r == nil {
		return ""
	}
	var sb strings.Builder
	sb.WriteByte('{')
	for i, col := range r.cols {
		if i > 0 {
			sb.WriteByte(',')
		}
		sb.WriteString(jsonQuote(col))
		sb.WriteByte(':')
		v := r.vals[col]
		if v == nil {
			sb.WriteString("null")
		} else {
			sb.WriteString(jsonQuote(*v))
		}
	}
	sb.WriteByte('}')
	s := sb.String()
	if len(s) > maxChars {
		s = s[:maxChars-len(truncationMarker)] + truncationMarker
	}
	return s
}

const truncationMarker = "…(已截断)"

func jsonQuote(s string) string {
	var sb strings.Builder
	sb.WriteByte('"')
	for _, c := range s {
		switch c {
		case '"':
			sb.WriteString("\\\"")
		case '\\':
			sb.WriteString("\\\\")
		case '\n':
			sb.WriteString("\\n")
		case '\r':
			sb.WriteString("\\r")
		case '\t':
			sb.WriteString("\\t")
		default:
			if c < 0x20 {
				const hex = "0123456789abcdef"
				sb.WriteString("\\u00")
				sb.WriteByte(hex[(c>>4)&0xF])
				sb.WriteByte(hex[c&0xF])
			} else {
				sb.WriteRune(c)
			}
		}
	}
	sb.WriteByte('"')
	return sb.String()
}

// renderValue 渲染一个单元格为展示文本：数值类型的值如果可解析，渲染成
// stripTrailingZeros 的十进制串（对齐 Java Row.render 对 BigDecimal 的处理，
// 避免 "2.00" vs "2.0" 这种只差标度的假差异污染展示与摘要）。nil 渲染为 nil。
func renderValue(t FieldType, v *string) *string {
	if v == nil {
		return nil
	}
	if t == typeDec || t == typeLong {
		if d, ok := ParseDecimal(*v); ok {
			s := d.Render()
			return &s
		}
	}
	s := *v
	return &s
}

// CompareResult 对齐 Java CompareResult。
type CompareResult struct {
	different bool
	delta     *Decimal
	ratio     *Decimal
}

// compareField 对齐 ValueComparator.compare：
//   - 两侧 null → same；一侧 null → 差异（"一边有值一边为空"本身就是要人看的差异）
//   - STRING：trim + 可选忽略大小写
//   - BOOLEAN：Java Boolean.parseBoolean 语义（只有忽略大小写的 "true" 是 true，其余全 false）
//   - LONG/DECIMAL：BigDecimal 比较；任一侧不是数字 → 差异（不抛错，保证任务跑完）
//   - 绝对/相对容差都配时，任一超限即差异；都没配时 delta != 0 即差异
//   - 基数为 0 时相对差没有意义：任何非零偏差都判为差异
func compareField(rule *FieldRule, left, right *string) CompareResult {
	if left == nil && right == nil {
		return CompareResult{}
	}
	if left == nil || right == nil {
		return CompareResult{different: true}
	}
	switch rule.Type {
	case typeString:
		l, r := strings.TrimSpace(*left), strings.TrimSpace(*right)
		same := l == r
		if rule.IgnoreCase {
			same = strings.EqualFold(l, r)
		}
		return CompareResult{different: !same}
	case typeBool:
		return CompareResult{different: parseJavaBool(*left) != parseJavaBool(*right)}
	default: // long / decimal
		l, lok := ParseDecimal(*left)
		r, rok := ParseDecimal(*right)
		if !lok || !rok {
			return CompareResult{different: true}
		}
		delta := l.Sub(r)
		var ratio *Decimal
		if r.Sign() != 0 {
			ratio = DivRatio(delta, r)
		}
		different := false
		if rule.tolerance == nil && rule.tolerancePercent == nil {
			different = delta.Sign() != 0
		} else {
			if rule.tolerance != nil && absDec(delta).Cmp(rule.tolerance) > 0 {
				different = true
			}
			if rule.tolerancePercent != nil {
				if ratio == nil {
					different = different || delta.Sign() != 0
				} else {
					different = different || absDec(ratio).Cmp(rule.tolerancePercent) > 0
				}
			}
		}
		return CompareResult{different: different, delta: delta, ratio: ratio}
	}
}

func parseJavaBool(s string) bool {
	return strings.EqualFold(strings.TrimSpace(s), "true")
}

func absDec(d *Decimal) *Decimal {
	if d.mant.Sign() < 0 {
		return &Decimal{mant: new(big.Int).Neg(d.mant), scale: d.scale}
	}
	return d
}

// compareKeys 对齐 ValueComparator.compareKeys：必须与"CSV 按 ORDER BY 导出"的
// 排序语义一致，否则归并会错位。
//   - null 排最前（对齐 Java：left==null → -1）
//   - STRING 用字节序。注意 Java String.compareTo 是 UTF-16 code unit 序，两者在
//     BMP 内一致；业务键绝大多数是 ASCII（订单号/流水号），worker 在文档里明确
//     建议导出 ORDER BY 与键类型一致
//   - 数值类型解析失败时退回字节序（对齐 Java 的降级分支）
func compareKeys(t FieldType, left, right *string) int {
	if left == nil && right == nil {
		return 0
	}
	if left == nil {
		return -1
	}
	if right == nil {
		return 1
	}
	switch t {
	case typeBool:
		lb, rb := parseJavaBool(*left), parseJavaBool(*right)
		switch {
		case lb == rb:
			return 0
		case !lb:
			return -1
		default:
			return 1
		}
	case typeLong, typeDec:
		l, lok := ParseDecimal(*left)
		r, rok := ParseDecimal(*right)
		if lok && rok {
			return l.Cmp(r)
		}
	}
	return strings.Compare(*left, *right)
}
