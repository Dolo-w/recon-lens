"""从 run.ps1 写出的日志里抽状态计数器，并只保留"可归因"的增量。

一次可归因 = 本状态表与上一张状态表之间，日志里**只有一条** EXPLAIN ANALYZE 的根计划行
（`run.ps1 -File` 的脚本形态：块标题 → FLUSH STATUS → EXPLAIN ANALYZE → SHOW GLOBAL STATUS）。
`SHOW GLOBAL STATUS` 是累计值（`FLUSH STATUS` 只清会话计数器），所以两张表之差就是那条语句自己的开销。

用法：
    python tools/mysql-bench/extract_counters.py [日志目录]      # 默认取本目录下的 out\

两个必须先知道的读数约定（对应 docs/benchmark.md 3.8）：
  * 每个块都会固定 `Created_tmp_tables +1`（EXPLAIN/SHOW 自身的基线噪声），本脚本已经把它减掉；
  * 场景文件的**首块**不可归因：文件头部的 ALTER TABLE ADD INDEX / ANALYZE TABLE /
    CREATE TABLE ... SELECT 的开销都落在第一张状态表里。首块用 24_counter_probe.sql 复测。
"""
import re
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"
if len(sys.argv) > 1:
    OUT = Path(sys.argv[1])
if not OUT.is_dir():
    raise SystemExit(f"日志目录不存在: {OUT}")

WANT = [
    "Created_tmp_tables", "Created_tmp_disk_tables", "Created_tmp_files",
    "Sort_rows", "Sort_scan", "Sort_range", "Sort_merge_passes",
    "Handler_read_key", "Handler_read_next", "Handler_read_rnd", "Handler_read_rnd_next",
    "Innodb_rows_read", "Select_scan", "Select_full_join",
]


def is_row(s):
    return s.startswith("|") and s.endswith("|") and not re.match(r"^\|\s*-+", s)


def events(path: Path):
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    out = []
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if re.match(r"^\|\s*Variable_name\s*\|", s):
            status = {}
            i += 1
            while i < len(lines):
                t = lines[i].strip()
                if re.match(r"^[+|]\s*-+", t):
                    i += 1
                    continue
                if not is_row(t):
                    break
                parts = [p.strip() for p in t.strip("|").split("|")]
                if len(parts) >= 2 and re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", parts[0]):
                    try:
                        status[parts[0]] = int(parts[1])
                        i += 1
                        continue
                    except ValueError:
                        break
                break
            out.append(("status", dict(status)))
            continue
        if s.startswith("|") and "===" in s:
            out.append(("block", s.strip("|").strip()))
            i += 1
            continue
        if s.startswith("| ->"):
            out.append(("plan", re.sub(r"\s+", " ", s[2:]).strip(" |")))
            i += 1
            continue
        i += 1
    return out


def fmt(n):
    return f"{n:,}"


def main():
    files = sorted(OUT.glob("*.log"), key=lambda p: p.stat().st_mtime)
    rows, anchors = [], []
    for f in files:
        # 只在同一个日志文件内串接状态表：跨文件会把这一个文件头部的
        # ALTER TABLE ADD INDEX / ANALYZE / CREATE TABLE 的开销算进首块。
        prev_status, plans_since, block = None, 0, "(无块标题)"
        for kind, payload in events(f):
            if kind == "block":
                block = payload
            elif kind == "plan":
                plans_since += 1
            elif kind == "status":
                if prev_status is not None:
                    delta = {k: payload[k] - prev_status[k] for k in payload if k in prev_status}
                    delta["Created_tmp_tables"] = max(0, delta.get("Created_tmp_tables", 0) - 1)  # 基线噪声
                    rows.append((f.name, block, delta, plans_since == 1))
                else:
                    anchors.append((f.name, block))
                prev_status, plans_since = payload, 0

    ok_n = sum(1 for r in rows if r[3])
    print(f"可归因块数: {ok_n} / 总状态表数: {len(rows)}（另有文件首块锚点 {len(anchors)} 个，见文末）\n")
    for fname, block, delta, ok in rows:
        if not ok:
            print(f"[不可归因·两块之间不止一条语句] {fname} :: {block[:70]}")
            continue
        keys = [k for k in WANT if delta.get(k)]
        print(f"\n{fname} :: {block}")
        print("    " + (" | ".join(f"{k}={fmt(delta[k])}" for k in keys) if keys else "（关心的计数器本次增量全为 0）"))

    print("\n\n===== 文件首块（同文件内没有前一张状态表，全局差值会含文件头部 DDL 的开销，故不归因）=====")
    for fname, block in anchors:
        print(f"  [锚点] {fname} :: {block[:70]}")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
