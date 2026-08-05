#!/bin/bash
# FreeHand E2E health check + report. Exit 0 = all pass, 1 = issues found.
set -euo pipefail
BASE="${BASE_URL:-http://127.0.0.1:3030}"
GROUP_URL="${TEST_GROUP_URL:-https://www.facebook.com/groups/860378780019248}"
REPORT_DIR="${REPORT_DIR:-/Users/tuan/Desktop/FreeHandFb/scripts/reports}"
mkdir -p "$REPORT_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
REPORT="$REPORT_DIR/report-$STAMP.json"
ISSUES=()
FIXES=()

log_issue() { ISSUES+=("$1"); echo "ISSUE: $1"; }
log_fix()   { FIXES+=("$1"); echo "FIX: $1"; }
pass()      { echo "PASS: $1"; }

# --- 1. Server ---
if ! curl -sf --connect-timeout 3 "$BASE/api/splash" >/dev/null; then
  log_issue "Server không phản hồi tại $BASE"
else
  pass "Server splash OK"
fi

# --- 2. ADB ---
DEVICE=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')
if [ -z "$DEVICE" ]; then
  log_issue "Không có thiết bị Android (adb)"
else
  pass "ADB device: $DEVICE"
  PHONE_CODE=$(adb -s "$DEVICE" shell "curl -s -o /dev/null -w '%{http_code}' --connect-timeout 3 http://127.0.0.1:3030/api/splash" 2>/dev/null | tr -d '\r')
  if [ "$PHONE_CODE" != "200" ]; then
    log_issue "Điện thoại không reach server (HTTP $PHONE_CODE) — cần adb reverse"
  else
    pass "Phone → server OK"
  fi
  A11Y=$(adb -s "$DEVICE" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  if [[ "$A11Y" != *"commenthelper"* ]]; then
    log_issue "Accessibility chưa bật cho app"
  else
    pass "Accessibility enabled"
  fi
fi

# --- 3. Auth ---
ADMIN_JSON=$(curl -sf -X POST "$BASE/api/login" -H "Content-Type: application/json" \
  -d '{"username":"admin@xommuaban.com","password":"16691","deviceId":"loop-test","isWeb":true}' 2>/dev/null || echo '{}')
ADMIN_TOKEN=$(echo "$ADMIN_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -z "$ADMIN_TOKEN" ]; then
  log_issue "Admin login thất bại"
else
  pass "Admin login OK"
fi

ANDROID_ID=""
if [ -n "${DEVICE:-}" ]; then
  ANDROID_ID=$(adb -s "$DEVICE" shell settings get secure android_id 2>/dev/null | tr -d '\r')
fi
WORKER_JSON=$(curl -s -X POST "$BASE/api/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"worker01\",\"password\":\"123456\",\"deviceId\":\"${ANDROID_ID:-test-device}\"}" 2>/dev/null || echo '{}')
WORKER_TOKEN=$(echo "$WORKER_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
WORKER_ERR=$(echo "$WORKER_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))" 2>/dev/null)
if [ -z "$WORKER_TOKEN" ]; then
  log_issue "Worker login thất bại: ${WORKER_ERR:-unknown}"
else
  pass "Worker login OK"
fi

# --- 4. Suggested group ---
if [ -n "$ADMIN_TOKEN" ]; then
  SG=$(curl -sf "$BASE/api/suggested-groups" -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null || echo '{}')
  HAS_GROUP=$(echo "$SG" | python3 -c "
import sys,json
d=json.load(sys.stdin)
gid='860378780019248'
ok=any(gid in g.get('url','') for g in d.get('approved',[]))
print('yes' if ok else 'no')
" 2>/dev/null)
  if [ "$HAS_GROUP" != "yes" ]; then
    log_issue "Group 860378780019248 chưa có trong suggested-groups (approved)"
  else
    pass "Suggested group approved"
  fi
fi

# --- 5. Executor queues ---
if [ -n "$ADMIN_TOKEN" ]; then
  Q=$(curl -sf "$BASE/api/executor/queues" -H "Authorization: Bearer $ADMIN_TOKEN" 2>/dev/null || echo '{}')
  STUCK=$(echo "$Q" | python3 -c "
import sys,json,time
d=json.load(sys.stdin)
now=time.time()*1000
stuck=[]
for t in ['interaction','publishing']:
    for j in d.get(t,{}).get('jobs',[]):
        if j.get('status')=='RUNNING' and now-(j.get('heartbeatAt') or j.get('claimedAt') or 0)>300000:
            stuck.append(j['id'])
print(','.join(stuck))
" 2>/dev/null)
  if [ -n "$STUCK" ]; then
    log_issue "Job RUNNING bị kẹt (no heartbeat >5m): $STUCK"
  else
    pass "No stuck RUNNING jobs"
  fi
fi

# --- Write report ---
ISSUES_JSON=$(printf '%s\n' "${ISSUES[@]:-}" | python3 -c 'import sys,json; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')
FIXES_JSON=$(printf '%s\n' "${FIXES[@]:-}" | python3 -c 'import sys,json; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')
export REPORT ISSUES_JSON FIXES_JSON
python3 <<'PY'
import json, datetime, os
issues = json.loads(os.environ.get("ISSUES_JSON", "[]"))
fixes = json.loads(os.environ.get("FIXES_JSON", "[]"))
data = {
    "timestamp": datetime.datetime.now().isoformat(),
    "status": "PASS" if not issues else "FAIL",
    "issue_count": len(issues),
    "issues": issues,
    "fixes_applied": fixes,
}
path = os.environ["REPORT"]
with open(path, "w") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
print(json.dumps(data, ensure_ascii=False, indent=2))
PY

[ ${#ISSUES[@]} -eq 0 ]
