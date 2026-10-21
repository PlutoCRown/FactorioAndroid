#!/usr/bin/env sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${GRADLE_USER_HOME:=$TASK_ROOT/.cache/gradle}"
export GRADLE_USER_HOME
if [ -n "${JAVA_HOME:-}" ]; then TASK_JAVA="$JAVA_HOME/bin/java"; else TASK_JAVA=java; fi
exec "$TASK_JAVA" -classpath "$TASK_ROOT/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
