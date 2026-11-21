package main

import (
	"flag"
	"fmt"
	"os"
	"strings"
)

// recon-worker：recon-lens 的大批量比对执行器（Go 单二进制）。
//
// 分工与 Java 引擎的边界：
//   - 规则引擎、取数（表/SQL/连接器）、任务编排、差异生命周期留在 Java；
//   - worker 只做一件事：把"两侧已按业务键升序导出的 CSV"按规则快速比完，
//     产出差异文件 + 统计，提交语义是"最大可提交前缀"（学 Lago events-processor）。
//
// 用法示例见 go-worker/README.md。
func main() {
	var (
		left          = flag.String("left", "", "左侧数据 CSV（按业务键升序，必填）")
		right         = flag.String("right", "", "右侧数据 CSV（按业务键升序，必填）")
		specPath      = flag.String("spec", "", "任务规格 JSON（必填）")
		out           = flag.String("out", "", "差异输出文件（默认 diffs.jsonl；format=csv 时 diffs.csv）")
		format        = flag.String("format", "jsonl", "差异输出格式：jsonl | csv")
		stats         = flag.String("stats", "stats.json", "统计输出文件")
		checkpoint    = flag.String("checkpoint", "checkpoint.json", "提交点文件（断点续跑依据）")
		shardSize     = flag.Int("shard-size", 50000, "每个分片的键组事件数（提交粒度）")
		concurrency   = flag.Int("concurrency", 0, "比对并发数（默认 CPU 核数，上限 8）")
		jobID         = flag.String("job-id", "", "任务标识（写入每条差异；默认取 spec.jobId 或 go-worker）")
		leftWatermark = flag.String("left-watermark", "", "左侧数据水位（RFC3339 或 yyyy-MM-dd[ HH:mm[:ss]]）")
		rightWatermark = flag.String("right-watermark", "", "右侧数据水位")
		deadline      = flag.String("deadline", "", "就绪线：早于它的水位视为未就绪，缺失差异标 *_PENDING")
		resume        = flag.Bool("resume", false, "从 checkpoint 断点续跑（输出截断到已提交边界后继续）")
	)
	flag.Parse()

	if *left == "" || *right == "" || *specPath == "" {
		fmt.Fprintln(os.Stderr, "缺少必填参数：--left、--right、--spec。")
		fmt.Fprintln(os.Stderr, "示例：recon-worker --left bill.csv --right settle.csv --spec rule.json --out diffs.jsonl")
		os.Exit(exitArgs)
	}
	*format = strings.ToLower(strings.TrimSpace(*format))
	if *format != "jsonl" && *format != "csv" {
		fmt.Fprintf(os.Stderr, "format 只支持 jsonl | csv，当前 %q\n", *format)
		os.Exit(exitArgs)
	}
	if *out == "" {
		if *format == "csv" {
			*out = "diffs.csv"
		} else {
			*out = "diffs.jsonl"
		}
	}

	code, statsOut := Run(Options{
		SpecPath:       *specPath,
		LeftPath:       *left,
		RightPath:      *right,
		OutPath:        *out,
		StatsPath:      *stats,
		CheckpointPath: *checkpoint,
		Format:         *format,
		ShardSize:      *shardSize,
		Concurrency:    *concurrency,
		JobID:          *jobID,
		LeftWatermark:  *leftWatermark,
		RightWatermark: *rightWatermark,
		Deadline:       *deadline,
		Resume:         *resume,
	})

	if statsOut != nil {
		fmt.Printf("对账完成: status=%s matched=%d diffRows=%d shards=%d/%d diffs=%d\n",
			statsOut.Status, statsOut.Matched, statsOut.Diffs,
			statsOut.Shards.Committed, statsOut.Shards.Total, statsOut.Diffs)
		if statsOut.Error != "" {
			fmt.Printf("未完成: %s\n%s\n", statsOut.Error, statsOut.ResumeHint)
		}
	}
	os.Exit(code)
}
