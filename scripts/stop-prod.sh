#!/usr/bin/env bash
# scripts/stop-prod.sh —— IPD 生产一键停止（2026-09-11 PLAN-ROOT-3）
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[INFO] 停止服务..."
docker-compose down

# 询问是否清理 volumes（默认保留）
read -p "清理 volumes（redis-data + backend-logs）？(y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
  docker-compose down -v
  echo "[OK] volumes 已清理"
fi

echo "[DONE] 停止完成。"
