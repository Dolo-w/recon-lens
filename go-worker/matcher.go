package main

import (
	"context"
	"strings"
)

// 归并式对账的核心，逐条对齐 Java MergeMatcher 的语义：
//
//	业务键相等 → 取同键组；组内任一侧多于一条 → 只报重复键差异，不再配对。
//	  （重复键组内的行序取决于导出顺序，按位凑对会把"哪行和哪行比"交给文件的脾气，
//	   同一份数据两次跑可能给出不同的字段差异集 —— 重复键本身就是数据问题，先修数据。）
//	组内两侧各恰好一条 → 逐字段比对 → 字段值不一致
//	左侧键更小 → 只有左表有 → 右侧缺失
//	右侧键更小 → 只有右表有 → 左侧缺失
//
// 与 Java 的差异：worker 的取数是"已按业务键升序导出的 CSV 文件"，
// 所以这里只做归并分组（produce，单线程顺序），字段比对下沉到 compare 阶段
// 并发执行 —— 比对是纯函数，顺序不影响结果，但 shard 的提交顺序必须保持，
// 这是"最大可提交前缀"的前提。
type workKind int

const (
	kindPair        workKind = iota // 两侧各一条，逐字段比对
	kindMissingRight                // 只有左表有 → 右侧缺失
	kindMissingLeft                 // 只有右表有 → 左侧缺失
	kindDuplicate                   // 同键多条，只报差异不配对
)

type workItem struct {
	kind       workKind
	left       *Row
	right      *Row
	leftGroup  []*Row // duplicate 用
	rightGroup []*Row
}

// shardWork 是一个分片的比对工作包。分片是逻辑边界（每 shardSize 个键组事件
// 一个 shard），不是物理切割 —— 归并流一次扫完，shard 只决定"提交粒度"。
type shardWork struct {
	id       int
	items    []workItem
	scannedL int64
	scannedR int64
	matched  int64
	lastKey  string // 本 shard 最后一个键组的主键渲染值（resume 跳过用）
}

// keyIter 包装 CSV 游标，提供 peek/next 语义（对齐 Java RowCursor），
// 并在消费过程中顺带校验业务键有序性 —— 归并比对的前提是两侧都按业务键
// 升序排列，乱序不报错地归并会错位出假差异，宁可在这里失败。
type keyIter struct {
	r        *csvReader
	peeked   *Row
	scanned  int64
	skipped  int64
	lastKey  *string // 有序性校验：上一个键的原始值
	skipKey  *string // resume：跳过 <= skipKey 的键组（渲染值比较）
	keyType  FieldType
	keyField string
	err      error
}

func newKeyIter(r *csvReader, side SideSpec, skipKey *string) *keyIter {
	return &keyIter{r: r, keyField: side.KeyField, keyType: side.KeyType, skipKey: skipKey}
}

func (it *keyIter) peek() *Row {
	if it.err != nil {
		return nil
	}
	if it.peeked == nil {
		row, err := it.r.Next()
		if err != nil {
			it.err = err
			return nil
		}
		it.peeked = row
	}
	return it.peeked
}

func (it *keyIter) next() *Row {
	row := it.peek()
	if row != nil {
		it.peeked = nil
		it.scanned++
		it.checkOrdered(row)
	}
	return row
}

// checkOrdered 有序性校验：键比较必须用与归并同一套 compareKeys
// （数值键按数值序，不能比字典序 —— "9" < "10" 按字典序是反的）。
func (it *keyIter) checkOrdered(row *Row) {
	cur := row.get(it.keyField)
	if cur == nil {
		return
	}
	if it.lastKey != nil && compareKeys(it.keyType, it.lastKey, cur) > 0 {
		it.err = &orderedError{file: it.r.path, line: it.r.line, keyField: it.keyField,
			prev: renderValue(it.keyType, it.lastKey), cur: renderValue(it.keyType, cur)}
	}
	it.lastKey = cur
}

func (it *keyIter) renderedKey(row *Row) string {
	v := renderValue(it.keyType, row.get(it.keyField))
	if v == nil {
		return ""
	}
	return *v
}

// shouldSkip resume 判断：本键组是否落在已提交边界之内（<= skipKey）。
// skipKey 与当前键都是渲染值（数值去尾零），比较口径一致才不会错位。
func (it *keyIter) shouldSkip(row *Row) bool {
	if it.skipKey == nil {
		return false
	}
	k := it.renderedKey(row)
	return compareKeys(it.keyType, strPtr(k), it.skipKey) <= 0
}

func strPtr(s string) *string { return &s }

// drainSameKey 取出同键组的全部行（对齐 MergeMatcher.drainSameKey）。
func (it *keyIter) drainSameKey(first *Row) []*Row {
	group := []*Row{first}
	it.next()
	for {
		next := it.peek()
		if next == nil || compareKeys(it.keyType, next.get(it.keyField), first.get(it.keyField)) != 0 {
			return group
		}
		group = append(group, next)
		it.next()
	}
}

// orderedPrecheck 校验单个文件按业务键升序（独立 reader，用完即弃）。
// 归并进行中才发现乱序时，基于乱序数据算出的差异可能已经提交 ——
// 已提交前缀里不许有未校验的数据，宁可多花一遍顺序读。
func orderedPrecheck(side SideSpec, r *csvReader) error {
	var prev *string
	line := int64(0)
	for {
		row, err := r.Next()
		if err != nil {
			return err
		}
		if row == nil {
			return nil
		}
		line++
		cur := row.get(side.KeyField)
		if cur == nil {
			prev = nil
			continue
		}
		if prev != nil && compareKeys(side.KeyType, prev, cur) > 0 {
			return &orderedError{file: r.path, line: line, keyField: side.KeyField,
				prev: renderValue(side.KeyType, prev), cur: renderValue(side.KeyType, cur)}
		}
		prev = cur
	}
}

type orderedError struct {
	file     string
	line     int64
	keyField string
	prev     *string
	cur      *string
}

func (e *orderedError) Error() string {
	return "文件 " + e.file + " 第 " + intToStr(e.line) + " 行业务键 \"" + renderOrEmpty(e.cur) +
		"\" 小于前一行的 \"" + renderOrEmpty(e.prev) + "\"：归并比对要求两侧按业务键升序" + "（导出时 ORDER BY " + e.keyField + "），乱序数据会错位出假差异，请重新导出"
}

func renderOrEmpty(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func intToStr(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// runProducer 归并两侧游标，按 shardSize 切工作包投递到 out。
// resume 时（skipKey 非空）：两侧都还没越过已提交边界的键组整组跳过。
// ctx 取消（提交失败后停止生产）时返回 errCancelled —— 发送必须 select，
// 否则下游停止消费时归并协程会永久阻塞在 channel 上。
func runProducer(ctx context.Context, spec *Spec, left, right *keyIter, shardSize int, out chan<- *shardWork) error {
	if shardSize <= 0 {
		shardSize = 50000
	}
	shard := &shardWork{id: 0}
	send := func(lastKey string) error {
		shard.lastKey = lastKey
		select {
		case out <- shard:
		case <-ctx.Done():
			return errCancelled
		}
		shard = &shardWork{id: shard.id + 1}
		return nil
	}

	bothSkipping := func(lRow, rRow *Row) bool {
		return left.shouldSkip(lRow) && right.shouldSkip(rRow)
	}

	var pendingLastKey string

	lRow := left.peek()
	rRow := right.peek()
	for lRow != nil && rRow != nil {
		if bothSkipping(lRow, rRow) {
			// resume：整组跳过。drainSameKey 内部的 next() 会推进扫描并做有序性校验
			lGroup := left.drainSameKey(lRow)
			rGroup := right.drainSameKey(rRow)
			left.skipped += int64(len(lGroup))
			right.skipped += int64(len(rGroup))
			lRow, rRow = left.peek(), right.peek()
			continue
		}
		cmp := compareKeys(spec.Left.KeyType, lRow.get(spec.Left.KeyField), rRow.get(spec.Right.KeyField))
		var lastKey string
		switch {
		case cmp < 0:
			shard.items = append(shard.items, workItem{kind: kindMissingRight, left: lRow})
			lastKey = left.renderedKey(lRow)
			shard.scannedL++
			left.next()
		case cmp > 0:
			shard.items = append(shard.items, workItem{kind: kindMissingLeft, right: rRow})
			lastKey = right.renderedKey(rRow)
			shard.scannedR++
			right.next()
		default:
			lGroup := left.drainSameKey(lRow)
			rGroup := right.drainSameKey(rRow)
			shard.scannedL += int64(len(lGroup))
			shard.scannedR += int64(len(rGroup))
			bizKey := firstNonEmpty(renderedOf(spec.Left, lGroup[0]), renderedOf(spec.Right, rGroup[0]))
			if len(lGroup) > 1 || len(rGroup) > 1 {
				shard.items = append(shard.items, workItem{kind: kindDuplicate,
					leftGroup: lGroup, rightGroup: rGroup})
			} else {
				shard.matched++
				shard.items = append(shard.items, workItem{kind: kindPair, left: lGroup[0], right: rGroup[0]})
			}
			lastKey = bizKey
		}
		pendingLastKey = lastKey
		if len(shard.items) >= shardSize {
			if err := send(lastKey); err != nil {
				return err
			}
			pendingLastKey = ""
		}
		lRow, rRow = left.peek(), right.peek()
		if left.err != nil {
			return left.err
		}
		if right.err != nil {
			return right.err
		}
	}
	// 一侧已经走完，另一侧剩下的行全是单边缺失
	for lRow != nil {
		if !left.shouldSkip(lRow) {
			shard.items = append(shard.items, workItem{kind: kindMissingRight, left: lRow})
			shard.scannedL++
			if len(shard.items) >= shardSize {
				if err := send(left.renderedKey(lRow)); err != nil {
					return err
				}
				pendingLastKey = ""
			} else {
				pendingLastKey = left.renderedKey(lRow)
			}
		} else {
			left.skipped++
		}
		left.next()
		lRow = left.peek()
	}
	for rRow != nil {
		if !right.shouldSkip(rRow) {
			shard.items = append(shard.items, workItem{kind: kindMissingLeft, right: rRow})
			shard.scannedR++
			if len(shard.items) >= shardSize {
				if err := send(right.renderedKey(rRow)); err != nil {
					return err
				}
				pendingLastKey = ""
			} else {
				pendingLastKey = right.renderedKey(rRow)
			}
		} else {
			right.skipped++
		}
		right.next()
		rRow = right.peek()
	}
	if len(shard.items) > 0 {
		if err := send(pendingLastKey); err != nil {
			return err
		}
	}
	if left.err != nil {
		return left.err
	}
	if right.err != nil {
		return right.err
	}
	return nil
}

func renderedOf(side SideSpec, row *Row) string {
	v := renderValue(side.KeyType, row.get(side.KeyField))
	if v == nil {
		return ""
	}
	return *v
}

// compareShard 把一个工作包变成差异列表（纯函数，并发安全）。
func compareShard(spec *Spec, jobID string, shard *shardWork, rd readiness) ([]Diff, error) {
	diffs := make([]Diff, 0, len(shard.items))
	for _, item := range shard.items {
		switch item.kind {
		case kindPair:
			comparePair(spec, jobID, shard.id, item.left, item.right, &diffs)
		case kindMissingRight:
			emitMissing(spec, jobID, shard.id, rd, &diffs, true, item.left)
		case kindMissingLeft:
			emitMissing(spec, jobID, shard.id, rd, &diffs, false, item.right)
		case kindDuplicate:
			emitDuplicate(spec, jobID, shard.id, item.leftGroup, item.rightGroup, &diffs)
		}
	}
	return diffs, nil
}

// comparePair 对齐 MergeMatcher.compareFields。
func comparePair(spec *Spec, jobID string, shardID int, lRow, rRow *Row, diffs *[]Diff) {
	bizKey := firstNonEmpty(renderedOf(spec.Left, lRow), renderedOf(spec.Right, rRow))
	eventAt := eventAtOf(spec, lRow, rRow)
	for i := range spec.Compare {
		rule := &spec.Compare[i]
		lv := lRow.get(rule.Field)
		rv := rRow.get(rule.Field)
		result := compareField(rule, lv, rv)
		if !result.different {
			continue
		}
		diff := Diff{
			JobID:    jobID,
			ShardID:  shardID,
			Type:     rule.DiffType,
			Severity: severityOf(rule.Severity),
			BizKey:   &bizKey,
			Field:    strPtr(rule.Field),
		}
		diff.LeftValue = renderValue(rule.Type, lv)
		diff.RightValue = renderValue(rule.Type, rv)
		if result.delta != nil {
			s := result.delta.Render()
			diff.Delta = &s
		}
		if result.ratio != nil {
			s := result.ratio.Render()
			diff.Ratio = &s
		}
		diff.EventAt = eventAt
		diff.EvidenceLeft = strPtr(lRow.rowJSON(evidenceMaxChars))
		diff.EvidenceRight = strPtr(rRow.rowJSON(evidenceMaxChars))
		*diffs = append(*diffs, diff)
	}
}

// emitMissing 对齐 MergeMatcher.emitMissingRight/emitMissingLeft：
// 水位未就绪的一侧缺失标 *_PENDING —— 语义是"可能尚未到达"，不是终判。
// isLeft=false 表示"只有左表有"（右侧缺失），行来自左侧。
func emitMissing(spec *Spec, jobID string, shardID int, rd readiness, diffs *[]Diff, missingRight bool, row *Row) {
	var side SideSpec
	typ := spec.Missing.LeftMissingDiffType
	if missingRight {
		side = spec.Left
		typ = spec.Missing.RightMissingDiffType
	} else {
		side = spec.Right
	}
	rendered := renderedOf(side, row)
	diff := Diff{
		JobID:    jobID,
		ShardID:  shardID,
		Type:     typ,
		Severity: severityOf(spec.Missing.Severity),
		BizKey:   &rendered,
		Field:    strPtr(""),
	}
	desc := describe(spec, row)
	if desc != "" {
		d := desc
		if missingRight {
			diff.LeftValue = &d // 只有左表有 → 左侧描述在左值
		} else {
			diff.RightValue = &d // 只有右表有 → 右侧描述在右值
		}
	}
	if missingRight && rd.rightPending {
		diff.Type = typ + pendingSuffix
		note := pendingNote(spec.Right.Label, rd.rightWatermark, rd.deadline)
		diff.Explanation = &note
	} else if !missingRight && rd.leftPending {
		diff.Type = typ + pendingSuffix
		note := pendingNote(spec.Left.Label, rd.leftWatermark, rd.deadline)
		diff.Explanation = &note
	}
	if missingRight {
		diff.EventAt = eventAtOf(spec, row, nil) // 行来自左侧
	} else {
		diff.EventAt = eventAtOf(spec, nil, row) // 行来自右侧
	}
	if missingRight {
		diff.EvidenceLeft = strPtr(row.rowJSON(evidenceMaxChars))
	} else {
		diff.EvidenceRight = strPtr(row.rowJSON(evidenceMaxChars))
	}
	*diffs = append(*diffs, diff)
}

func emitDuplicate(spec *Spec, jobID string, shardID int, lGroup, rGroup []*Row, diffs *[]Diff) {
	bizKey := firstNonEmpty(renderedOf(spec.Left, lGroup[0]), renderedOf(spec.Right, rGroup[0]))
	diff := Diff{
		JobID:    jobID,
		ShardID:  shardID,
		Type:     spec.Duplicate.DiffType,
		Severity: severityOf(spec.Duplicate.Severity),
		BizKey:   &bizKey,
		Field:    strPtr(""),
		LeftValue:  strPtr(groupSummary(spec.Left, lGroup)),
		RightValue: strPtr(groupSummary(spec.Right, rGroup)),
	}
	diff.EventAt = eventAtOf(spec, lGroup[0], rGroup[0])
	diff.EvidenceLeft = strPtr(lGroup[0].rowJSON(evidenceMaxChars))
	diff.EvidenceRight = strPtr(rGroup[0].rowJSON(evidenceMaxChars))
	*diffs = append(*diffs, diff)
}

const (
	maxIdsInSummary  = 5
	evidenceMaxChars = 4000
	pendingSuffix    = "_PENDING"
)

// groupSummary 对齐 MergeMatcher.groupSummary：报条数 + 前几条 id（有 id 列用 id，
// 没有就用业务键）。重复键本身就是要人去修的数据问题。
func groupSummary(side SideSpec, rows []*Row) string {
	ids := make([]string, 0, len(rows)+1)
	for _, row := range rows {
		if len(ids) >= maxIdsInSummary {
			ids = append(ids, "…")
			break
		}
		id := row.get("id")
		if id == nil {
			id = row.get(side.KeyField)
		}
		ids = append(ids, renderOrEmpty(renderValue(side.KeyType, id)))
	}
	return side.Label + " " + intToStr(int64(len(rows))) + " 条(id=" + strings.Join(ids, "/") + ")"
}

// describe 对齐 MergeMatcher.describe：键列 + 比对列拼 "col=val, ..."，
// 行里没有的列跳过（右侧行天然没有左键列时就不显示）。
func describe(spec *Spec, row *Row) string {
	var sb strings.Builder
	appendCol := func(col string) {
		v := row.get(col)
		if v == nil {
			return
		}
		if sb.Len() > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(col)
		sb.WriteByte('=')
		sb.WriteString(*v)
	}
	appendCol(spec.Left.KeyField)
	if spec.Right.KeyField != spec.Left.KeyField {
		appendCol(spec.Right.KeyField)
	}
	for i := range spec.Compare {
		appendCol(spec.Compare[i].Field)
	}
	return sb.String()
}

// eventAtOf 对齐 MergeMatcher.eventAtOf：左侧配置了 eventTime 用左行，其次右侧，
// 都没有给 null。值原样渲染（不猜类型/时区），解析交给计价侧。
func eventAtOf(spec *Spec, lRow, rRow *Row) *string {
	if lRow != nil && spec.Left.EventTime != "" {
		if v := lRow.get(spec.Left.EventTime); v != nil {
			s := *v
			return &s
		}
	}
	if rRow != nil && spec.Right.EventTime != "" {
		if v := rRow.get(spec.Right.EventTime); v != nil {
			s := *v
			return &s
		}
	}
	return nil
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

func pendingNote(sideLabel, watermark, deadline string) string {
	wm := watermark
	if wm == "" {
		wm = "无"
	}
	note := sideLabel + "数据水位未就绪（最新水位 " + wm
	if deadline != "" {
		note += "，就绪线 " + deadline
	}
	note += "），该缺失可能是'尚未到达'而非'真缺失'，属于待定判定 —— 等水位就绪后重跑即可出终判。"
	return note
}

// readiness 对齐 ReconEngine.Readiness 的 CLI 形态：
// 水位与就绪线都给了且水位早于就绪线 → 该侧未就绪；
// 只传水位没传就绪线 → 无从判定，视为就绪（诚实处理，不猜）。
type readiness struct {
	leftPending    bool
	rightPending   bool
	leftWatermark  string
	rightWatermark string
	deadline       string
}
