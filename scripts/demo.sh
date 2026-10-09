#!/usr/bin/env bash
# 全流程演示脚本:提交 -> 成功 / 瞬时失败重试 / 永久失败死信 / 死信重投 / 幂等
# 用法:先启动应用(mvn spring-boot:run -Dspring-boot.run.profiles=demo),再运行本脚本
set -e
BASE=http://localhost:8080
API=$BASE/api/v1/notifications

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1',''))" 2>/dev/null || echo ""; }

echo "================================================"
echo "0. 重置 mock 供应商"
curl -s -X POST $BASE/_mock/reset > /dev/null

echo "================================================"
echo "1. 提交三类业务事件(模拟三个不同业务系统)"
R1=$(curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"ad-register","bizId":"AD-1001",
  "payload":{"orderId":"AD-1001","userId":"u9","advertiserId":"adv-77","source":"tiktok"}}')
echo "  ad-register  -> $(echo "$R1" | j status)"
R2=$(curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"crm-payment","bizId":"PAY-2001",
  "payload":{"planId":"pro-max","amount":299,"customer":{"email":"a@b.com"}}}')
echo "  crm-payment  -> $(echo "$R2" | j status)"
R3=$(curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"inventory-order","bizId":"ORD-3001",
  "payload":{"orderId":"ORD-3001","items":[{"sku":"SKU-A","quantity":2}]}}')
UID3=$(echo "$R3" | j recordUid)
echo "  inventory-order recordUid=$UID3"

sleep 2
echo "--- mock 供应商收到的请求(验证 Header/Body 按供应商格式渲染) ---"
curl -s $BASE/_mock/received | python3 -m json.tool

echo "================================================"
echo "2. 幂等:重复提交相同 (eventType,bizId)"
curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"ad-register","bizId":"AD-1001","payload":{"orderId":"AD-1001"}}' | python3 -m json.tool

echo "================================================"
echo "3. 供应商故障(503) -> 指数退避重试"
curl -s -X POST "$BASE/_mock/fail-mode?on=true" > /dev/null
R4=$(curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"ad-register","bizId":"AD-5555",
  "payload":{"orderId":"AD-5555","userId":"u5","advertiserId":"adv","source":"x"}}')
UID4=$(echo "$R4" | j recordUid)
for i in 1 2 3; do
  sleep 6
  echo "  第${i}次快照: status=$(curl -s $API/$UID4 | j status)  retryCount=$(curl -s $API/$UID4 | python3 -c "import sys,json;print(json.load(sys.stdin).get('retryCount',''))")"
done
echo "  供应商恢复:"
curl -s -X POST "$BASE/_mock/fail-mode?on=false" > /dev/null
# 等待下一次退避到期(1s,2s,4s,8s,16s 序列,最后一段可能长达 16s)
sleep 18
echo "  最终状态: $(curl -s $API/$UID4 | j status)(重试后自动送达)"

echo "================================================"
echo "4. 永久失败(400) -> 直接进死信,不浪费重试"
curl -s -X POST "$BASE/_mock/reject-mode?on=true" > /dev/null
R5=$(curl -s -X POST $API -H 'Content-Type: application/json' -d '{
  "eventType":"ad-register","bizId":"AD-6666",
  "payload":{"orderId":"AD-6666","userId":"u6","advertiserId":"adv","source":"x"}}')
UID5=$(echo "$R5" | j recordUid)
sleep 2
echo "  状态: $(curl -s $API/$UID5 | j status)"
echo "  lastError: $(curl -s $API/$UID5 | python3 -c "import sys,json;print(json.load(sys.stdin).get('lastError',''))")"

echo "  人工修复供应商后,重投死信:"
curl -s -X POST "$BASE/_mock/reject-mode?on=false" > /dev/null
curl -s -X POST $API/$UID5/replay -w "  replay http=%{http_code}\n" > /dev/null
sleep 2
echo "  重投后状态: $(curl -s $API/$UID5 | j status)"

echo "================================================"
echo "5. 全局统计(死信数是运维要盯的信号)"
curl -s $API/stats | python3 -m json.tool
echo "================================================"
echo "演示完成。另可体验崩溃恢复:kill -9 应用进程再重启,未完成投递会自动续投。"
