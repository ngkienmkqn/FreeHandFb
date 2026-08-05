#!/bin/bash
# Apply auto-fixes for common FreeHand test failures.
set -euo pipefail
BASE="${BASE_URL:-http://127.0.0.1:3030}"
GROUP_URL="https://www.facebook.com/groups/860378780019248"
GROUP_NAME="test-free-hand"
FIXED=()

fix() { FIXED+=("$1"); echo "APPLIED: $1"; }

# adb reverse
DEVICE=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')
if [ -n "$DEVICE" ]; then
  adb -s "$DEVICE" reverse tcp:3030 tcp:3030 2>/dev/null && fix "adb reverse tcp:3030"
  ANDROID_ID=$(adb -s "$DEVICE" shell settings get secure android_id 2>/dev/null | tr -d '\r')
  USERS_FILE="/Users/tuan/Desktop/FreeHandFb/server/data/users.json"
  if [ -n "$ANDROID_ID" ]; then
    ADMIN_TOKEN=$(curl -sf -X POST "$BASE/api/login" -H "Content-Type: application/json" \
      -d '{"username":"admin@xommuaban.com","password":"16691","deviceId":"fix-script","isWeb":true}' \
      | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
    if [ -n "$ADMIN_TOKEN" ]; then
      WORKER_ID=$(curl -sf "$BASE/api/users" -H "Authorization: Bearer $ADMIN_TOKEN" \
        | python3 -c "import sys,json; u=next((x for x in json.load(sys.stdin) if x.get('username')=='worker01'),{}); print(u.get('id',''))" 2>/dev/null)
      if [ -n "$WORKER_ID" ]; then
        # Reset device lock in server memory, then bind on next login
        curl -sf -X PUT "$BASE/api/users/$WORKER_ID" -H "Content-Type: application/json" \
          -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"deviceId":""}' >/dev/null
        curl -sf -X POST "$BASE/api/login" -H "Content-Type: application/json" \
          -d "{\"username\":\"worker01\",\"password\":\"123456\",\"deviceId\":\"$ANDROID_ID\"}" >/dev/null
        fix "worker01 deviceId reset + bind → $ANDROID_ID (via API)"
      fi
    fi
    if [ -f "$USERS_FILE" ]; then
      python3 - "$USERS_FILE" "$ANDROID_ID" <<'PY'
import json, sys
path, aid = sys.argv[1], sys.argv[2]
users = json.load(open(path))
for u in users:
    if u.get("username") == "worker01":
        u["deviceId"] = aid
        break
json.dump(users, open(path, "w"), indent=2, ensure_ascii=False)
PY
    fi
  fi
fi

# Ensure suggested group exists
ADMIN_TOKEN=$(curl -sf -X POST "$BASE/api/login" -H "Content-Type: application/json" \
  -d '{"username":"admin@xommuaban.com","password":"16691","deviceId":"fix-script","isWeb":true}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -n "$ADMIN_TOKEN" ]; then
  HAS=$(curl -sf "$BASE/api/suggested-groups" -H "Authorization: Bearer $ADMIN_TOKEN" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('yes' if any('860378780019248' in g.get('url','') for g in d.get('approved',[])) else 'no')")
  if [ "$HAS" = "no" ]; then
    curl -sf -X POST "$BASE/api/suggested-groups" -H "Content-Type: application/json" \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -d "{\"name\":\"$GROUP_NAME\",\"url\":\"$GROUP_URL\",\"memberCount\":\"\"}" >/dev/null
    fix "Added suggested group $GROUP_NAME (approved)"
  fi
fi

if [ ${#FIXED[@]} -gt 0 ]; then printf '%s\n' "${FIXED[@]}"; fi
