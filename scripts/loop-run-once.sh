#!/bin/bash
# One loop iteration: fix → test → report. Used by /loop dynamic wake.
set -uo pipefail
ROOT="/Users/tuan/Desktop/FreeHandFb"
cd "$ROOT"
LOG="$ROOT/scripts/reports/loop-latest.log"
mkdir -p "$ROOT/scripts/reports"

{
  echo "========== $(date -Iseconds) LOOP ITERATION =========="
  echo "--- auto-fix ---"
  "$ROOT/scripts/loop-auto-fix.sh" || true
  echo "--- test ---"
  if "$ROOT/scripts/loop-test-report.sh"; then
    echo "RESULT: PASS"
    EXIT=0
  else
    echo "--- retry fix after fail ---"
    "$ROOT/scripts/loop-auto-fix.sh" || true
    if "$ROOT/scripts/loop-test-report.sh"; then
      echo "RESULT: PASS (after retry)"
      EXIT=0
    else
      echo "RESULT: FAIL — cần can thiệp thủ công"
      EXIT=1
    fi
  fi
  LATEST=$(ls -t "$ROOT/scripts/reports"/report-*.json 2>/dev/null | head -1)
  echo "REPORT: $LATEST"
  [ -n "$LATEST" ] && cat "$LATEST"
} 2>&1 | tee "$LOG"
EXIT=${PIPESTATUS[0]:-$EXIT}
exit "$EXIT"
