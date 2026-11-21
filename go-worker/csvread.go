package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"
	"unicode/utf8"
)

// RFC 4180 CSV 读取器。口径对齐 Java FileImporter 的 CsvFile：
//   - UTF-8 严格解码（BOM 吃掉，非法字节报错而不是静默替换 —— GBK 文件静默乱码
//     比报错危险得多，宁可在这里失败）
//   - 分隔符按首行出现次数探测（逗号/分号/制表符）
//   - 双引号转义、引号内的逗号与换行
//   - 单字段 1MB 上限：一个没闭合的引号会把整份文件吞成一个字段
//
// 与 Java 的差异：这里读出来的单元格全部是文本（CSV 没有 DB 类型系统），
// 数值语义由规格里的字段类型决定。
type csvReader struct {
	f         *os.File
	path      string
	reader    *bufio.Reader
	delimiter byte
	headers   []string // 规范化列名（小写）
	line      int64    // 当前数据行号（表头为第 0 行），报错用
}

const maxFieldChars = 1 << 20

func openCsv(path string) (*csvReader, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("读不了文件 %s: %v", path, err)
	}
	r := &csvReader{f: f, path: path, reader: bufio.NewReaderSize(f, 1<<16)}
	if err := skipBOM(r.reader); err != nil {
		r.Close()
		return nil, err
	}
	first, err := readRawLine(r.reader)
	if err != nil && err != io.EOF {
		r.Close()
		return nil, err
	}
	r.delimiter = detectDelimiter(first)
	// 表头行已被探测消费：回到文件头重读（BOM 重新跳过）
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		r.Close()
		return nil, err
	}
	r.reader.Reset(r.f)
	if err := skipBOM(r.reader); err != nil {
		r.Close()
		return nil, err
	}
	headers, err := r.readRecord()
	if err != nil {
		r.Close()
		return nil, err
	}
	if headers != nil {
		r.headers = normalizeHeaders(headers)
	}
	return r, nil
}

func (r *csvReader) Close() error { return r.f.Close() }

// Headers 返回规范化后的列名。
func (r *csvReader) Headers() []string { return r.headers }

// Next 读一行数据。返回 nil 表示 EOF。列数超过表头时报错 —— 静默截断会让
// 数据少一截还显示 SUCCESS，比报错难查得多（与 Java 灌数同口径）。
// 完全空白的行（只有一个空字段）跳过，不算数据也不算行号。
func (r *csvReader) Next() (*Row, error) {
	for {
		rec, err := r.readRecord()
		if err != nil {
			return nil, err
		}
		if rec == nil {
			return nil, nil
		}
		r.line++
		if len(rec) == 1 && rec[0] == "" {
			continue // 空行
		}
		for _, v := range rec {
			if !utf8.ValidString(v) {
				return nil, fmt.Errorf("第 %d 行不是合法 UTF-8：多半是 GBK/ANSI 编码。" + "请另存为 UTF-8（Excel:「另存为 → CSV UTF-8」；命令行: iconv -f GBK -t UTF-8），" + "worker 只接受 UTF-8，不做编码猜测", r.line)
			}
		}
		if len(rec) > len(r.headers) {
			return nil, fmt.Errorf("第 %d 行有 %d 列，表头只有 %d 列：多半是字段里出现了未转义的分隔符，" + "或文件被手工改过。字段里有逗号时要用双引号包起来", r.line, len(rec), len(r.headers))
		}
		m := make(map[string]*string, len(r.headers))
		for i, col := range r.headers {
			if i < len(rec) {
				m[col] = csvValue(rec[i])
			} else {
				m[col] = nil // 尾部缺列按空处理（RFC4180 不禁止）
			}
		}
		return &Row{cols: r.headers, vals: m}, nil
	}
}

// csvValue 把"CSV 空语义"统一成 nil：空串、\N、NULL。
// 对齐 Java 导入管道（isBlank / isNullLiteral）—— 导出的 CSV 里空与 null 不区分，
// worker 里也不区分，避免"一个空一个 ''"的假差异。
func csvValue(s string) *string {
	v := strings.TrimSpace(s)
	if v == "" || v == "\\N" || strings.EqualFold(v, "NULL") {
		return nil
	}
	return &s
}

func (r *csvReader) readRecord() ([]string, error) {
	var field strings.Builder
	var rec []string
	inQuotes := false
	quotedThisField := false
	anyChar := false
	appendChar := func(c byte) error {
		if field.Len() >= maxFieldChars {
			return fmt.Errorf("单个字段超过 %d 字符：引号多半没闭合，整份文件被当成一个字段在读", maxFieldChars)
		}
		field.WriteByte(c)
		return nil
	}
	flushField := func() {
		rec = append(rec, field.String())
		field.Reset()
		quotedThisField = false
	}
	for {
		c, err := r.reader.ReadByte()
		if err != nil {
			if err != io.EOF {
				return nil, err
			}
			if !anyChar && field.Len() == 0 && len(rec) == 0 {
				return nil, nil
			}
			if inQuotes {
				return nil, fmt.Errorf("最后一条记录的引号没有闭合（读到文件末尾仍处在引号内）：" + "检查最后一行的双引号是否成对，或字段里的引号有没有写成两个 \"\"")
			}
			flushField()
			return rec, nil
		}
		anyChar = true
		if inQuotes {
			if c == '"' {
				next, err2 := r.reader.ReadByte()
				if err2 != nil && err2 != io.EOF {
					return nil, err2
				}
				if err2 == io.EOF {
					inQuotes = false
					continue
				}
				if next == '"' {
					field.WriteByte('"') // 转义引号
				} else {
					inQuotes = false
					if err := r.reader.UnreadByte(); err != nil {
						return nil, err
					}
				}
			} else {
				if err := appendChar(c); err != nil {
					return nil, err
				}
			}
			continue
		}
		switch c {
		case '"':
			if field.Len() == 0 && !quotedThisField {
				inQuotes = true
				quotedThisField = true
			} else {
				if err := appendChar(c); err != nil {
					return nil, err
				}
			}
		default:
			if c == r.delimiter {
				flushField()
			} else if c == '\r' {
				next, err2 := r.reader.ReadByte()
				if err2 != nil && err2 != io.EOF {
					return nil, err2
				}
				if err2 == nil && next != '\n' {
					if err := r.reader.UnreadByte(); err != nil {
						return nil, err
					}
				}
				flushField()
				return rec, nil
			} else if c == '\n' {
				flushField()
				return rec, nil
			} else {
				if err := appendChar(c); err != nil {
					return nil, err
				}
			}
		}
	}
}

// readRawLine 读一行原始文本（探测分隔符用，不处理引号）。
func readRawLine(r *bufio.Reader) (string, error) {
	var sb strings.Builder
	for {
		c, err := r.ReadByte()
		if err != nil {
			if err != io.EOF {
				return "", err
			}
			return sb.String(), io.EOF
		}
		if c == '\n' {
			return sb.String(), nil
		}
		if c != '\r' {
			sb.WriteByte(c)
		}
	}
}

func detectDelimiter(line string) byte {
	best, bestCount := byte(','), -1
	for _, cand := range []byte{',', ';', '\t'} {
		n := strings.Count(line, string(cand))
		if n > bestCount {
			best, bestCount = cand, n
		}
	}
	return best
}

func skipBOM(r *bufio.Reader) error {
	b, err := r.Peek(3)
	if err != nil && err != io.EOF {
		return err
	}
	if len(b) >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF {
		_, _ = r.Discard(3)
	}
	return nil
}

func normalizeHeaders(headers []string) []string {
	out := make([]string, len(headers))
	seen := map[string]int{}
	for i, h := range headers {
		name := strings.ToLower(strings.TrimSpace(h))
		if name == "" {
			name = fmt.Sprintf("col_%d", i+1)
		}
		if n, dup := seen[name]; dup {
			name = fmt.Sprintf("%s_%d", name, n+1)
		}
		seen[name]++
		out[i] = name
	}
	return out
}
