#!/usr/bin/env bash
echo "=== java/mvn 进程 (TIME=累计CPU, STAT=状态) ==="
ps -eo pid,etime,time,stat,cmd | grep -iE 'java|maven|/mvn' | grep -v grep || echo "(无 java 进程)"
echo
echo "=== 网络下载速率检测 ==="
r1=$(cat /sys/class/net/eth0/statistics/rx_bytes 2>/dev/null || echo 0)
sleep 3
r2=$(cat /sys/class/net/eth0/statistics/rx_bytes 2>/dev/null || echo 0)
echo "rx 增量: $(( (r2 - r1) / 1024 )) KB / 3s"
echo
echo "=== buildkit 缓存 ==="
du -sh /var/lib/docker/buildkit 2>/dev/null
echo
echo "=== app 镜像 / 容器 ==="
docker images | grep -E 'REPOSITORY|pophie' || echo "(app 镜像未生成)"
docker ps -a --format 'table {{.Names}}\t{{.Status}}'
