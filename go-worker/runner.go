package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"runtime"
	"sync"
	"time"
)

// Options 是一次 worker 运行的全部输入。
type Options struct {
	SpecPath       string
	LeftPath       string
	RightPath      string
	OutPath        string
	StatsPath      string
	CheckpointPath string
	Format         string // jsonl | csv
	ShardSize      int
	Concurrency    int
	JobID          string
	LeftWatermark  string
	RightWatermark string
	Deadline       string
	Resume         bool
}

// 退出码：COMPLETED=0；PARTIAL（最大可提交前缀，未完成）=3；
// 启动/配置错误=1；数据错误（CSV 编码/格式，checkpoint 已保留可 resume）=2。
const (
	exitOK      = 0
	exitArgs    = 1
	exitData    = 2
	exitPartial = 3
)

// errCancelled 是"提交流程主动取消"（提交失败后停发），不是数据错误。
var errCancelled = errors.New("已取消")

type shardOut struct {
	id      int
	diffs   []Diff
	matched int64
	lastKey string // 该 shard 最后一个键组的主键渲染值（checkpoint 用）
	err     error
}

// Run 编排整次对账。管道结构：
//
//	produce（单线程归并分组） → jobs → N 个 compare worker（纯函数并发）
//	  → results → committer（主线程，按 shard 顺序提交"最大可提交前缀"）
//
// 提交语义（学 Lago events-processor 的手动提交）：
//   - shard 按序提交：写差异 → flush → 记 checkpoint（outOffset + nextKey）
//   - 任何失败停在第一个未成功 shard：之前的提交全部保留，checkpoint 可 --resume
func Run(opts Options) (int, *runStats) {
	started := time.Now().Format(time.RFC3339)

	spec, err := loadSpec(opts.SpecPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "规格错误:", err)
		return exitArgs, nil
	}
	rd, err := buildReadiness(opts.LeftWatermark, opts.RightWatermark, opts.Deadline)
	if err != nil {
		fmt.Fprintln(os.Stderr, "水位参数错误:", err)
		return exitArgs, nil
	}

	jobID := opts.JobID
	if jobID == "" && spec.JobID != "" {
		jobID = spec.JobID
	}
	if jobID == "" {
		jobID = "go-worker"
	}

	// checkpoint：resume 时恢复提交边界
	var cp checkpoint
	resumeKey := (*string)(nil)
	if opts.Resume {
		loaded, err := loadCheckpoint(opts.CheckpointPath)
		if err != nil {
			fmt.Fprintln(os.Stderr, "读不了 checkpoint（--resume 需要一次成功的前缀提交）:", err)
			return exitArgs, nil
		}
		cp = *loaded
		if cp.NextKey != "" {
			resumeKey = strPtr(cp.NextKey)
		}
	}

	// 输出文件：resume 时截断到已提交字节边界 —— 丢弃未提交 shard 可能残留的
	// 半截差异（进程崩溃时 out 里可能比 checkpoint 多写了几行），保证
	// "out 文件 = 已提交前缀" 这个不变式。
	outFile, err := os.OpenFile(opts.OutPath, os.O_RDWR|os.O_CREATE, 0o644)
	if err != nil {
		fmt.Fprintln(os.Stderr, "打不开输出文件:", err)
		return exitArgs, nil
	}
	if opts.Resume {
		if err := outFile.Truncate(cp.OutOffset); err != nil {
			fmt.Fprintln(os.Stderr, "无法把输出截断到 checkpoint 边界:", err)
			outFile.Close()
			return exitArgs, nil
		}
		if _, err := outFile.Seek(cp.OutOffset, 0); err != nil {
			fmt.Fprintln(os.Stderr, "无法定位到 checkpoint 边界:", err)
			outFile.Close()
			return exitArgs, nil
		}
	} else if err := outFile.Truncate(0); err != nil {
		fmt.Fprintln(os.Stderr, "清空输出文件失败:", err)
		outFile.Close()
		return exitArgs, nil
	}
	var writer diffWriter
	if opts.Format == "csv" {
		writer, err = newCSVWriter(outFile)
	} else {
		writer = newJSONLWriter(outFile)
	}
	if err != nil {
		outFile.Close()
		fmt.Fprintln(os.Stderr, "初始化输出失败:", err)
		return exitArgs, nil
	}

	leftCsv, err := openCsv(opts.LeftPath)
	if err != nil {
		outFile.Close()
		fmt.Fprintln(os.Stderr, "左侧数据错误:", err)
		return exitData, nil
	}
	rightCsv, err := openCsv(opts.RightPath)
	if err != nil {
		leftCsv.Close()
		outFile.Close()
		fmt.Fprintln(os.Stderr, "右侧数据错误:", err)
		return exitData, nil
	}
	leftIter := newKeyIter(leftCsv, spec.Left, resumeKey)
	rightIter := newKeyIter(rightCsv, spec.Right, resumeKey)

	// 有序性预检（归并正确性的前提）：用独立 reader 完整读一遍键列 ——
	// 宁可多读一遍文件，也不把基于乱序数据算出的差异提交进前缀
	for _, p := range []struct {
		path string
		side SideSpec
	}{{opts.LeftPath, spec.Left}, {opts.RightPath, spec.Right}} {
		pre, err := openCsv(p.path)
		if err != nil {
			leftCsv.Close()
			rightCsv.Close()
			outFile.Close()
			fmt.Fprintln(os.Stderr, "数据错误:", err)
			return exitData, nil
		}
		err = orderedPrecheck(p.side, pre)
		pre.Close()
		if err != nil {
			leftCsv.Close()
			rightCsv.Close()
			outFile.Close()
			fmt.Fprintln(os.Stderr, "数据错误:", err)
			return exitData, nil
		}
	}

	// 字段存在性校验（对齐 ShardRunner.validateFields）：把配置错误变成
	// 启动时一条可读的失败原因，而不是跑到一半炸掉
	if err := validateColumns(spec, leftCsv.Headers(), rightCsv.Headers()); err != nil {
		leftCsv.Close()
		rightCsv.Close()
		outFile.Close()
		fmt.Fprintln(os.Stderr, "规格与数据不匹配:", err)
		return exitArgs, nil
	}

	concurrency := opts.Concurrency
	if concurrency <= 0 {
		concurrency = runtime.NumCPU()
		if concurrency > 8 {
			concurrency = 8
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	jobs := make(chan *shardWork, concurrency*2)
	results := make(chan shardOut, concurrency*2)
	producerErrCh := make(chan error, 1)

	// produce：单线程归并，ctx 取消时立刻停发（否则提交失败后没人读 jobs 会死锁）
	go func() {
		defer close(jobs)
		producerErrCh <- runProducer(ctx, spec, leftIter, rightIter, opts.ShardSize, jobs)
	}()

	// compare：纯函数并发，保留 maxAttempts 重试语义
	var wg sync.WaitGroup
	for w := 0; w < concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case shard, alive := <-jobs:
					if !alive {
						return
					}
					var diffs []Diff
					var err error
					for attempt := 1; attempt <= spec.MaxAttempts; attempt++ {
						diffs, err = compareShard(spec, jobID, shard, rd)
						if err == nil {
							break
						}
					}
					out := shardOut{id: shard.id, diffs: diffs, matched: shard.matched,
						lastKey: shard.lastKey, err: err}
					select {
					case results <- out:
					case <-ctx.Done():
						return
					}
				}
			}
		}()
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	stats := &runStats{
		Status:    "COMPLETED",
		Scanned:   map[string]int64{"left": 0, "right": 0},
		Skipped:   map[string]int64{"left": 0, "right": 0},
		ByType:    map[string]int64{},
		ByField:   map[string]int64{},
		Shards:    shardStats{LastCommittedShard: -1},
		Out:       opts.OutPath,
		StartedAt: started,
	}
	nextShard := 0
	pending := map[int]shardOut{}
	failed := false

	// commit：按序提交一个 shard。返回 false = 停止整个提交流程
	commit := func(so shardOut) bool {
		for i := range so.diffs {
			d := &so.diffs[i]
			stats.ByType[d.Type]++
			if d.Field != nil && *d.Field != "" {
				stats.ByField[*d.Field]++
			}
		}
		for i := range so.diffs {
			if err := writer.write(&so.diffs[i]); err != nil {
				stats.Error = "写差异失败: " + err.Error()
				return false
			}
		}
		if err := writer.flush(); err != nil {
			stats.Error = "刷盘失败: " + err.Error()
			return false
		}
		cp.LastCommittedShard = so.id
		cp.OutOffset = writer.offset()
		cp.Diffs += int64(len(so.diffs))
		if so.lastKey != "" {
			cp.NextKey = so.lastKey
			cp.NextKeyType = string(spec.Left.KeyType)
		}
		stats.Shards.Committed++
		stats.Shards.LastCommittedShard = so.id
		stats.Diffs += int64(len(so.diffs))
		stats.Matched += so.matched
		if err := saveCheckpoint(opts.CheckpointPath, &cp); err != nil {
			stats.Error = "写 checkpoint 失败: " + err.Error()
			return false
		}
		return true
	}

	stop := func(errText, hint string) {
		stats.Error = errText
		stats.Status = "PARTIAL"
		stats.ResumeHint = hint
		failed = true
		cancel()
	}

	consume := func(so shardOut) {
		if so.id != nextShard {
			pending[so.id] = so
			return
		}
		for {
			if so.err != nil {
				fmt.Fprintf(os.Stderr, "分片 %d 比对失败: %v\n", so.id, so.err)
				stats.Shards.FirstFailedShard = so.id
				stop(fmt.Sprintf("分片 %d 比对失败: %v（重试 %d 次后放弃）", so.id, so.err, spec.MaxAttempts),
					"已提交分片 0-"+intToStr(int64(so.id-1))+" 的差异已保留；修复问题后加 --resume 从断点续跑")
				return
			}
			if !commit(so) {
				fmt.Fprintln(os.Stderr, "提交失败:", stats.Error)
				stop(stats.Error, "已提交前缀保留在输出文件与 checkpoint 中；修复问题后加 --resume 从断点续跑")
				return
			}
			nextShard++
			nxt, alive := pending[nextShard]
			if !alive {
				return
			}
			delete(pending, nextShard)
			so = nxt
		}
	}

	for so := range results {
		consume(so)
	}
	// producer 的错误在所有已产出 shard 处理完后才有意义：
	// CSV 中途坏了 → 已成功前缀照常提交保留（数据错误 exit=2，可修复后 --resume）
	producerErr := <-producerErrCh
	if producerErr != nil && producerErr != errCancelled && !failed {
		stats.Status = "PARTIAL"
		stats.Error = producerErr.Error()
		stats.ResumeHint = "已提交前缀保留在输出文件与 checkpoint 中；修复数据后加 --resume 从断点续跑"
		cancel()
	}
	cancel()
	wg.Wait()

	if !failed {
		// 扫描统计在 producer 结束后读取（单写者已停），口径 = 本次实际读过的行；
		// matched/diffRows 只统计已提交 shard —— 部分提交时未提交的活在 resumeHint 里
		stats.Scanned["left"] = leftIter.scanned
		stats.Scanned["right"] = rightIter.scanned
		stats.Skipped["left"] = leftIter.skipped
		stats.Skipped["right"] = rightIter.skipped
		stats.Shards.Total = nextShard
	}

	if err := writeStats(opts.StatsPath, stats); err != nil {
		fmt.Fprintln(os.Stderr, "写统计失败:", err)
	}
	leftCsv.Close()
	rightCsv.Close()
	outFile.Close()

	switch {
	case failed:
		return exitPartial, stats
	case producerErr != nil:
		return exitData, stats
	default:
		return exitOK, stats
	}
}

// validateColumns 对齐 ShardRunner.validateFields：键列与比对列必须真的存在，
// 否则归并全程拿 nil 当值，产出全量假缺失。
func validateColumns(spec *Spec, leftCols, rightCols []string) error {
	lset := colSet(leftCols)
	rset := colSet(rightCols)
	if _, ok := lset[spec.Left.KeyField]; !ok {
		return fmt.Errorf("left.keyField %q 在左侧 CSV 里不存在，实际列: %v", spec.Left.KeyField, leftCols)
	}
	if _, ok := rset[spec.Right.KeyField]; !ok {
		return fmt.Errorf("right.keyField %q 在右侧 CSV 里不存在，实际列: %v", spec.Right.KeyField, rightCols)
	}
	for i := range spec.Compare {
		f := spec.Compare[i].Field
		if _, ok := lset[f]; !ok {
			return fmt.Errorf("compare.%s 在左侧 CSV 里不存在，实际列: %v", f, leftCols)
		}
		if _, ok := rset[f]; !ok {
			return fmt.Errorf("compare.%s 在右侧 CSV 里不存在，实际列: %v", f, rightCols)
		}
	}
	if spec.Left.EventTime != "" {
		if _, ok := lset[spec.Left.EventTime]; !ok {
			return fmt.Errorf("left.eventTime %q 在左侧 CSV 里不存在，实际列: %v", spec.Left.EventTime, leftCols)
		}
	}
	if spec.Right.EventTime != "" {
		if _, ok := rset[spec.Right.EventTime]; !ok {
			return fmt.Errorf("right.eventTime %q 在右侧 CSV 里不存在，实际列: %v", spec.Right.EventTime, rightCols)
		}
	}
	return nil
}

func colSet(cols []string) map[string]string {
	m := make(map[string]string, len(cols))
	for _, c := range cols {
		m[c] = c
	}
	return m
}
