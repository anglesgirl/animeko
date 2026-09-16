#!/bin/bash
# ECH Check 结果直推 Telegram：成功先发文字再发包，失败发真因。无钥匙时静默跳过。
set -u
[ -z "${TELEGRAM_BOT_TOKEN:-}" ] && exit 0
API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"
SHA="${HEAD_SHA:-unknown}"
SHORT=${SHA:0:8}
STATUS="${BUILD_STATUS:-unknown}"
RUN_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID:-0}"
MAX_DOC_BYTES=52428800

send_text() {
  curl -s -X POST "$API/sendMessage" -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=$1" > /dev/null
}

if [ "$STATUS" = "success" ]; then
  APK=""
  for pat in "*arm64-v8a*.apk" "*universal*.apk" "*.apk"; do
    # shellcheck disable=SC2086
    for f in app/android/build/outputs/apk/default/debug/$pat; do
      [ -s "$f" ] && { APK="$f"; break 2; }
    done
  done
  if [ -z "$APK" ]; then
    send_text "加密版构建成功 $SHORT，但没找到包。SHA=$SHA RUN=${RUN_ID:-0} $RUN_URL"
    exit 0
  fi
  BYTES=$(stat -c%s "$APK")
  HUMAN=$(du -h "$APK" | cut -f1)
  send_text "加密版已绿 $SHORT $(basename "$APK") $HUMAN($BYTES字节) SHA=$SHA RUN=${RUN_ID:-0} $RUN_URL"
  if [ "$BYTES" -gt "$MAX_DOC_BYTES" ]; then
    send_text "包超50MB通道发不出，请去 Actions 页手动下载。$RUN_URL"
    exit 0
  fi
  curl -s -X POST "$API/sendDocument" \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "document=@${APK}" \
    -F "caption=$SHORT $(basename "$APK") $HUMAN $RUN_URL" > /dev/null
else
  JOBS_JSON=$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/jobs" 2>/dev/null)
  JOB_NAME=$(echo "$JOBS_JSON" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
  JID=$(echo "$JOBS_JSON" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  LOGS=$(gh api "repos/${GITHUB_REPOSITORY}/actions/jobs/${JID}/logs" 2>/dev/null)
  TASK=$(echo "$LOGS" | grep -m1 -o "Task :[a-zA-Z0-9:._-]* FAILED\|Execution failed for task '[^']*'")
  ERRS=$(echo "$LOGS" | grep -m5 "e: file")
  send_text "加密版构建失败 $SHORT SHA=$SHA RUN=${RUN_ID:-0} 任务=${JOB_NAME:-未知} ${TASK:-} ${ERRS:-无详细错误} $RUN_URL"
fi
