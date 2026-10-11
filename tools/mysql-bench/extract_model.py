"""从 run.ps1 写出的日志里抽"自带计时的块"（含 DDL）的墙钟与状态计数器增量。

和 `extract_counters.py` 的分工：
  * `extract_counters.py` 只认「两块之间恰好一条 EXPLAIN ANALYZE 根计划行」的场景文件，
    墙钟从计划里的 `actual time=` 读；
  * 本脚本面向**含 DDL 的文件**（如 `26_prereq_model.sql`：CTAS / ALTER 没有执行计划），
    墙钟从块内 `SET @t0 := NOW(6)` … `SELECT TIMESTAMPDIFF(...) / 1000 AS <name>_ms` 那一行读；
    计数器仍是「相邻两张 `SHOW GLOBAL STATUS` 相减」，并要求两块之间只有一条语句。

用法：
    python tools/mysql-bench/extract_model.py [日志文件或日志目录]    # 默认取本目录下的 out\
"""
import re
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"
if len(sys.argv) > 1:
    OUT = Path(sys.argv[1])
if not OUT.exists():
    raise SystemExit(f"日志路径不存在: {OUT}")

COUNTERS = [
    "Created_tmp_tables", "Created_tmp_disk_tables", "Created_tmp_files",
    "Sort_rows", "Sort_scan", "Sort_merge_passes",
    "Handler_read_key", "Handler_read_next", "Handler_read_rnd", "Handler_read_rnd_next",
    "Innodb_rows_read",
]


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def blocks(path: Path):
    """按 `| === X === |` 切块；块标题里出现 `=`（如 shard_bucket=3）也要认。"""
    out, cur = [], None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        s = raw.strip()
        # 注意两件事：① 超长的计划行不会被 mysql --table 补上右边的 `|`，所以只要求以 `|` 开头；
        # ② 计划行的形状是 `| -> ...`，别把它当成 `| --- |` 这种分隔线给过滤掉（分隔线要求整格都是横线）。
        if not s.startswith("|") or re.match(r"^\|\s*[-+]+\s*\|\s*$", s):
            continue
        parts = cells(s)
        if len(parts) == 1:
            m = re.search(r"===\s*(.+?)\s*===", parts[0])
            if m:
                cur = {"name": m.group(1), "ms": {}, "counters": {}}
                out.append(cur)
            elif cur is not None:
                if parts[0].startswith("-> ") and "actual time=" in parts[0] and not cur.get("plan"):
                    cur["plan"] = parts[0]  # 块内第一条 -> 就是根算子（EXPLAIN ANALYZE 的输出）
                try:  # 单列结果表：`SELECT ... AS m1_ms` 的取值行
                    cur["ms"]["elapsed"] = float(parts[0])
                except ValueError:
                    pass
            continue
        if cur is None or len(parts) < 2:
            continue
        key, val = parts[0], parts[1]
        if key.endswith("_ms"):
            try:
                cur["ms"][key] = float(val)
            except ValueError:
                pass
        elif key in COUNTERS:
            try:
                cur["counters"][key] = int(val)
            except ValueError:
                pass
    return out


def fmt(n):
    return f"{n:,}"


def main():
    files = [OUT] if OUT.is_file() else sorted(OUT.glob("*.log"), key=lambda p: p.stat().st_mtime)
    for f in files:
        print(f"\n===== {f.name} =====")
        prev = None
        for b in blocks(f):
            ms = "  ".join(f"{k}={v:,.1f} ms" for k, v in b["ms"].items())
            if not ms and b.get("plan"):
                m = re.search(r"actual time=[\d.]+\.\.([\d.]+) rows=(\d+)", b["plan"])
                if m:
                    ms = f"plan={float(m.group(1)):,.1f} ms rows={int(m.group(2)):,}（{b['plan'].split(' (cost')[0].rstrip()}）"
            print(f"\n[{b['name']}] {ms}")
            if prev is None:
                print("  （本文件第一张状态表：锚点，不归因）")
            else:
                delta = {k: b["counters"][k] - prev[k] for k in b["counters"] if k in prev}
                keys = [k for k in COUNTERS if delta.get(k)]
                noise = delta.get("Created_tmp_tables", 0) - 1  # EXPLAIN/SHOW 自身的基线噪声
                print("  Δ " + ("  ".join(f"{k}={fmt(delta[k])}" for k in keys) or "（关心的计数器本次增量全为 0）"))
                if noise > 0:
                    print(f"  （提示：Created_tmp_tables 里含 {noise} 次基线噪声）")
            if b["counters"]:
                prev = b["counters"]


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
