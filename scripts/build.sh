#!/usr/bin/env bash
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_JDK="${JAVA_HOME:-}"
TASK_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
TASK_PERFORMANCE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --performance-package) TASK_PERFORMANCE=1; shift ;;
    --sdk) TASK_SDK=$2; shift 2 ;;
    --jdk) TASK_JDK=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "$TASK_JDK" || ! -x "$TASK_JDK/bin/java" ]]; then echo 'Set JAVA_HOME to JDK 17 or newer, or pass --jdk.' >&2; exit 1; fi
if [[ -z "$TASK_SDK" || ! -d "$TASK_SDK" ]]; then echo 'Set ANDROID_HOME, or pass --sdk.' >&2; exit 1; fi
export JAVA_HOME="$TASK_JDK" ANDROID_HOME="$TASK_SDK" ANDROID_SDK_ROOT="$TASK_SDK"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TASK_ROOT/.cache/gradle}"
cd "$TASK_ROOT"
TASK_ARGS=(--root "$TASK_ROOT")
if [[ "$TASK_PERFORMANCE" == 1 ]]; then TASK_ARGS+=(--performance-package); fi
exec "$TASK_JDK/bin/java" "$TASK_ROOT/scripts/BuildLauncher.java" "${TASK_ARGS[@]}"
