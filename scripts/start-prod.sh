#!/usr/bin/env bash
# scripts/start-prod.sh —— IPD 生产部署一键启动（2026-09-11 PLAN-ROOT-3）
# 用法：bash scripts/start-prod.sh
# 依赖：docker + docker-compose；先 cp .env.example .env 并填写真实值
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT=$(pwd)

# 1. .env 校验
if [ ! -f .env ]; then
  echo "[ERROR] .env 不存在，请 cp .env.example .env 并填写真实值" >&2
  exit 1
fi
# 必填项非空校验
required_vars=("IPD_DB_URL" "IPD_DB_USER" "IPD_DB_PASSWORD" "IPD_JWT_SECRET_KEY")
for v in "${required_vars[@]}"; do
  if grep -q "^${v}=$" .env; then
    echo "[ERROR] .env 中 ${v} 为空，请填写" >&2
    exit 1
  fi
done

# 2. 检测端口占用（防双 PID 陷阱，R30 教训）
if lsof -ti:16039 >/dev/null 2>&1; then
  echo "[WARN] 16039 已被占用：$(lsof -ti:16039 | tr '\n' ' ')"
  echo "[WARN] 重启前先排查，建议：kill -9 \$(lsof -ti:16039) 后再跑本脚本"
  read -p "继续吗？(y/N) " -n 1 -r
  echo
  [[ $REPLY =~ ^[Yy]$ ]] || exit 1
fi

# 3. 拉镜像 + 起服务
echo "[INFO] 拉取/构建镜像..."
docker-compose pull --ignore-pull-failures
docker-compose build --pull

echo "[INFO] 启动服务..."
docker-compose up -d

# 4. 等待健康
echo "[INFO] 等待 backend 健康..."
for i in $(seq 1 30); do
  if curl -fs http://127.0.0.1:16039/actuator/health >/dev/null 2>&1; then
    echo "[OK] backend 已就绪 (第 ${i} 次尝试)"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "[ERROR] backend 启动超时 90s，查看 docker-compose logs backend" >&2
    exit 1
  fi
  sleep 3
done

# 5. 触发系统级验收
echo "[INFO] 触发系统级验收..."
bash scripts/verify-prod.sh || echo "[WARN] verify-prod.sh 退出非零，详见日志"

echo "[DONE] 启动完成。"
echo "[HINT] 访问 http://127.0.0.1:16039 (API) / http://127.0.0.1 (前端 nginx 80→5666)"
