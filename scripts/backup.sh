#!/usr/bin/env bash
#
# recon-lens 数据库备份（清单 P0-7）
#
# 设计要点：
#   1. 凭证走临时 defaults-extra-file，不出现在进程参数里（ps 能看到命令行，看不到这个文件）；
#   2. --single-transaction：InnoDB 一致性快照，不锁表 —— 备份时业务照常跑；
#   3. 备份完立刻校验（gzip 完整性 + 必须含 CREATE TABLE），坏文件当场发现，
#      而不是等真要恢复的那天才发现；
#   4. 保留策略按天数清理，避免备份目录把磁盘吃满（这在生产是真实故障源）。
#
# 用法：
#   MYSQL_PASSWORD=xxx ./scripts/backup.sh
#   MYSQL_PASSWORD=xxx ./scripts/backup.sh --keep 14 --out /data/backups
#   ./scripts/backup.sh --dry-run          # 只打印计划，不连库
#
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-recon}"
MYSQL_DATABASE="${MYSQL_DATABASE:-recon_lens}"
OUT_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --keep) KEEP_DAYS="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "未知参数: $1（--help 看用法）" >&2; exit 2 ;;
  esac
done

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="${OUT_DIR}/${MYSQL_DATABASE}-${STAMP}.sql.gz"

echo "=========================================="
echo "recon-lens 备份"
echo "  数据库 : ${MYSQL_DATABASE}@${MYSQL_HOST}:${MYSQL_PORT} (user=${MYSQL_USER})"
echo "  输出   : ${TARGET}"
echo "  保留   : ${KEEP_DAYS} 天"
echo "=========================================="

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "[dry-run] 将执行："
  echo "  mysqldump --single-transaction --routines --triggers --events \\"
  echo "            --defaults-extra-file=<临时文件> ${MYSQL_DATABASE} | gzip -9 > ${TARGET}"
  echo "  校验：gzip -t + 至少一条 CREATE TABLE + 至少 ${2:-5} 张表"
  echo "  清理：删除 ${OUT_DIR} 下超过 ${KEEP_DAYS} 天的 *.sql.gz"
  echo "[dry-run] 未连数据库，未写任何文件。"
  exit 0
fi

for bin in mysqldump gzip; do
  command -v "$bin" >/dev/null 2>&1 || { echo "缺少命令: $bin" >&2; exit 3; }
done

if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  echo "需要环境变量 MYSQL_PASSWORD（不放在命令行里，避免被 ps 看到）" >&2
  exit 3
fi

mkdir -p "$OUT_DIR"

# 凭证文件：umask 077 + trap 清理，任何退出路径都不留残file
umask 077
CNF="$(mktemp "${TMPDIR:-/tmp}/recon-backup-XXXXXX.cnf")"
cleanup() { rm -f "$CNF"; }
trap cleanup EXIT INT TERM
cat > "$CNF" <<EOF
[client]
user=${MYSQL_USER}
password=${MYSQL_PASSWORD}
host=${MYSQL_HOST}
port=${MYSQL_PORT}
EOF

echo "[1/4] 导出中（--single-transaction，不锁表）..."
START=$SECONDS
# --events/--routines/--triggers：漏了它们，恢复出来的库"表对了但行为不对"
mysqldump --defaults-extra-file="$CNF" \
  --single-transaction --quick --events --routines --triggers \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 \
  "$MYSQL_DATABASE" | gzip -9 > "$TARGET"
echo "      完成，耗时 $((SECONDS - START))s，大小 $(du -h "$TARGET" | cut -f1)"

echo "[2/4] 校验压缩完整性..."
gzip -t "$TARGET"

echo "[3/4] 校验内容..."
TABLES=$(gzip -dc "$TARGET" | grep -c "^CREATE TABLE" || true)
if [[ "${TABLES}" -lt 5 ]]; then
  echo "校验失败：备份里只有 ${TABLES} 张表（预期至少 5 张）。文件保留在 ${TARGET} 供排查。" >&2
  exit 4
fi
echo "      含 ${TABLES} 张表 ✓"

echo "[4/4] 清理超过 ${KEEP_DAYS} 天的旧备份..."
DELETED=$(find "$OUT_DIR" -maxdepth 1 -name "${MYSQL_DATABASE}-*.sql.gz" -type f -mtime "+${KEEP_DAYS}" -print -delete | wc -l)
echo "      删除 ${DELETED} 个"

echo
echo "备份成功：${TARGET}"
echo "恢复到新库验证：MYSQL_PASSWORD=xxx ./scripts/restore.sh ${TARGET} --database recon_lens_verify --yes"
