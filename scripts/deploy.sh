#!/usr/bin/env bash
set -euo pipefail

# 部署目录默认取「本脚本所在仓库的根目录」，而不是硬编码路径。
# 这样同一份脚本可以部署任意一份克隆（例如 /opt/xxx 和 /opt/xxx-test），
# 不会因为路径写死而去动到另一个正在跑的环境。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${APP_DIR:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
APP_NAME="${APP_NAME:-$(basename "${APP_DIR}")}"

# 容器名默认跟随目录名，也可以用环境变量覆盖。
# 两个环境必须用不同容器名，否则 docker 会报名字冲突。
CONTAINER_NAME="${CONTAINER_NAME:-${APP_NAME}}"
NETWORK_NAME="${NETWORK_NAME:-feishu-net}"
GIT_BRANCH="${GIT_BRANCH:-main}"
GIT_REPO_URL="${GIT_REPO_URL:-https://github.com/SUNZEQIN/feishu-admin-ai-bot-clean.git}"
GIT_PROXY_PREFIX="${GIT_PROXY_PREFIX:-}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"
GIT_SAFE_DIRECTORY_AUTO="${GIT_SAFE_DIRECTORY_AUTO:-true}"

echo "[1/7] 进入项目目录：${APP_DIR}"
cd "${APP_DIR}"

echo "[2/7] 检查 .env"
if [ ! -f ".env" ]; then
  echo ".env 不存在，正在从 .env.example 复制..."
  cp .env.example .env
  echo "请先编辑 .env，然后重新执行本脚本。"
  exit 1
fi

echo "[3/7] 拉取最新代码"
if [ -d ".git" ]; then
  if [ "${GIT_SAFE_DIRECTORY_AUTO}" = "true" ]; then
    git config --global --add safe.directory "${APP_DIR}" >/dev/null 2>&1 || true
  fi

  if [ -n "${GIT_PROXY_PREFIX}" ]; then
    GIT_PULL_URL="${GIT_PROXY_PREFIX}${GIT_REPO_URL}"
    echo "使用 Git 代理拉取代码：${GIT_PULL_URL}"
    git pull "${GIT_PULL_URL}" "${GIT_BRANCH}"
  else
    echo "使用 origin 拉取代码：分支=${GIT_BRANCH}"
    git pull origin "${GIT_BRANCH}"
  fi
else
  echo "当前目录不是 Git 仓库，跳过 git pull。"
fi

echo "[4/7] 确认外部 Docker 网络"
# docker-compose.yml 把 feishu-net 声明为 external，网络不存在时 compose 会直接失败。
# 这里只在缺失时创建，已存在则复用，不会影响其它服务。
if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
  echo "外部网络 ${NETWORK_NAME} 不存在，正在创建..."
  docker network create "${NETWORK_NAME}"
else
  echo "外部网络 ${NETWORK_NAME} 已存在，直接复用。"
fi

echo "[5/7] 构建并启动 Java 服务"
# 每次都要重新构建：Skill 文件在 resources 下，改了 Skill 必须重新打包进镜像。
docker compose up -d --build

echo "[6/7] 等待健康检查通过"
# 用配置里的端口做健康检查，避免只看容器状态就误判部署成功。
SERVER_PORT_VALUE="$(grep -E '^SERVER_PORT=' .env | tail -1 | cut -d= -f2)"
SERVER_PORT_VALUE="${SERVER_PORT_VALUE:-8082}"
HEALTH_URL="http://127.0.0.1:${SERVER_PORT_VALUE}/api/health"

deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
healthy="no"
while [ "$(date +%s)" -lt "${deadline}" ]; do
  if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
    healthy="yes"
    break
  fi
  sleep 3
done

echo "[7/7] 查看服务状态"
docker ps --filter "name=${CONTAINER_NAME}"

if [ "${healthy}" != "yes" ]; then
  echo "❌ 健康检查未通过：${HEALTH_URL}"
  echo "最近日志："
  docker logs --tail=80 "${CONTAINER_NAME}" || true
  exit 1
fi

echo "✅ 部署完成，健康检查通过：${HEALTH_URL}"
echo "查看实时日志：docker logs -f ${CONTAINER_NAME}"
