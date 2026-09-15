#!/bin/bash
# ECH Check 结果直推 Telegram：成功发包，失败发错。无钥匙时静默跳过。
set -u
[ -z "${TELEGRAM_BOT_TOKEN:-}" ] && exit 0
API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"
SHA="${HEAD_SHA:-unknown}"
SHORT=${SHA:0:8}
STATUS="${BUILD_STATUS:-unknown}"
RUN_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID:-0}"

if [ "$STATUS" = "success" ]; then
  APK=$(ls app/android/build/outputs/apk/default/debug/*.apk 2>/dev/null | head -1)
  if [ -z "$APK" ]; then
    curl -s -X POST "$API/sendMessage" -d "chat_id=${TELEGRAM_CHAT_ID}" \
      --data-urlencode "text=加密版构建成功 $SHORT，但没找到包。$RUN_URL" > /dev/null
    exit 0
  fi
  SIZE=$(du -h "$APK" | cut -f1)
  curl -s -X POST "$API/sendDocument" \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "document=@${APK}" \
    -F "caption=加密版已绿 $SHORT $SIZE $RUN_URL" > /dev/null
else
  JID=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/jobs" --jq '.jobs[].id' 2>/dev/null | head -1)
  ERR=$(gh api "repos/${GITHUB_REPOSITORY}/actions/jobs/${JID}/logs" 2>/dev/null | grep -m3 "e: file")
  curl -s -X POST "$API/sendMessage" -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=加密版构建失败 $SHORT $RUN_URL $ERR" > /dev/null
fi
