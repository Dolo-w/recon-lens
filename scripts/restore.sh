#!/usr/bin/env bash
#
# recon-lens 数据库恢复（清单 P0-7）
#
# 三条硬规矩：
#   1. 目标库默认是「新建的验证库」，不是生产库 —— 恢复演练最常见的错误是手滑覆盖生产；
#   2. 覆盖已有库必须同时给 --yes 和 --database，缺一个就拒绝；
#   3. 恢复完立刻做行数抽查（表数量 + 关键表行数），"导进去了但数据不对"比导入失败更危险。
#
# 用法：
#   MYSQL_PASSWORD=xxx ./scripts/restore.sh backups/recon_lens-20261122-193000.sql.gz \
#       --database recon_lens_verify --yes
#   ./scripts/restore.sh <file> --dry-run     # 只打印计划
#
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-recon}"

DUMP_FILE=""
TARGET_DB=""
CONFIRM=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --yes) CONFIRM=1; shift ;;
    --database) TARGET_DB="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *)
      if [[ -z "$DUMP_FILE" ]]; then DUMP_FILE="$1"; else echo "多余参数: $1" >&2; exit 2; fi
      shift ;;
  esac
done

if [[ -z "$DUMP_FILE" ]]; then
  echo "需要备份文件路径。用法：restore.sh <backup.sql.gz> --database <db> --yes" >&2
  exit 2
fi
if [[ ! -f "$DUMP_FILE" ]]; then
  echo "备份文件不存在: $DUMP_FILE" >&2
  exit 2
fi

# 安全默认：不给 --database 就恢复到一个全新的验证库，绝不碰同名库
if [[ -z "$TARGET_DB" ]]; then
  TARGET_DB="recon_restore_verify_$(date +%Y%m%d_%H%M%S)"
  echo "[提示] 未指定 --database，将恢复到新验证库：${TARGET_DB}"
elif [[ "$CONFIRM" -ne 1 ]]; then
  echo "目标库 ${TARGET_DB} 已指定，但缺少 --yes。" >&2
  echo "如果你确实要覆盖它，请确认已备份当前数据，然后加 --yes 重跑。" >&2
  exit 5
fi

echo "=========================================="
echo "recon-lens 恢复"
echo "  来源   : ${DUMP_FILE}"
echo "  目标库 : ${TARGET_DB}@${MYSQL_HOST}:${MYSQL_PORT}"
echo "=========================================="

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "[dry-run] 将执行："
  echo "  gzip -t ${DUMP_FILE}"
  echo "  mysql -e 'CREATE DATABASE IF NOT EXISTS ${TARGET_DB}'"
  echo "  gzip -dc ${DUMP_FILE} | mysql ${TARGET_DB}"
  echo "  校验：表数量 + 关键表行数抽查"
  echo "[dry-run] 未连数据库，未写任何数据。"
  exit 0
fi

command -v gzip >/dev/null 2>&1 || { echo "缺少命令: gzip" >&2; exit 3; }
command -v mysql  >/dev/null 2>&1 || { echo "缺少命令: mysql" >&2; exit 3; }
if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  echo "需要环境变量 MYSQL_PASSWORD" >&2
  exit 3
fi

echo "[1/5] 校验压缩完整性..."
gzip -t "$DUMP_FILE"

umask 077
CNF="$(mktemp "${TMPDIR:-/tmp}/recon-restore-XXXXXX.cnf")"
cleanup() { rm -f "$CNF"; }
trap cleanup EXIT INT TERM
cat > "$CNF" <<EOF
[client]
user=${MYSQL_USER}
password=${MYSQL_PASSWORD}
host=${MYSQL_HOST}
port=${MYSQL_PORT}
EOF
MYSQL="mysql --defaults-extra-file=${CNF} --default-character-set=utf8mb4"

echo "[2/5] 建库 ${TARGET_DB}（若不存在）..."
echo "CREATE DATABASE IF NOT EXISTS \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" | $MYSQL

echo "[3/5] 导入中..."
START=$SECONDS
gzip -dc "$DUMP_FILE" | $MYSQL "$TARGET_DB"
echo "      完成，耗时 $((SECONDS - START))s"

echo "[4/5] 校验表数量..."
TABLES=$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}';" | $MYSQL -N -B)
echo "      共 ${TABLES} 张表"
if [[ "${TABLES:-0}" -lt 5 ]]; then
  echo "校验失败：恢复出的库只有 ${TABLES} 张表（预期至少 5 张）" >&2
  exit 4
fi

echo "[5/5] 关键表行数抽查..."
for t in recon_rule recon_diff recon_task; do
  N=$(echo "SELECT COUNT(*) FROM \`${TARGET_DB}\`.\`${t}\`;" 2>/dev/null | $MYSQL -N -B 2>/dev/null || echo "n/a")
  echo "      ${t}: ${N}"
done

echo
echo "恢复成功：${TARGET_DB}"
echo "业务校验建议（自动核对替代不了这一步）："
echo "  1. 用该库起一个实例：SPRING_DATASOURCE_URL=...jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${TARGET_DB}..."
echo "  2. 打开 /api/reports/finance 看数字是否与备份前一致"
echo "  3. 跑一次对账任务，确认差异数与预期相符"
