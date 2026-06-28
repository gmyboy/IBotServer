#!/usr/bin/env bash
cd /opt/pophie
echo "=== 等待 app 健康（最多 ~2 分钟）==="
for i in $(seq 1 40); do
  s=$(docker inspect --format '{{.State.Health.Status}}' pophie-app 2>/dev/null || echo starting)
  echo "[$i/40] app: $s"
  [ "$s" = "healthy" ] && break
  sleep 3
done
echo
echo "=== GET /api/health ==="
curl -s http://localhost:8000/api/health; echo
echo "=== GET /api/schema (前 240 字符) ==="
curl -s http://localhost:8000/api/schema | head -c 240; echo
echo
echo "=== docker compose ps ==="
docker compose ps
echo
echo "=== app 近 25 行日志 ==="
docker compose logs --tail 25 app
