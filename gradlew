#!/bin/sh

#
# Auto Caller — Gradle wrapper boot script (Unix/macOS).
#
# This script bootstraps the Gradle wrapper if gradle-wrapper.jar
# is missing (Android Studio regenerates it on first sync). For
# command-line builds without Android Studio, run:
#
#     gradle wrapper --gradle-version 8.4
#
# once from a system Gradle install to populate gradle-wrapper.jar.
#

set -e

APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: $WRAPPER_JAR not found."
    echo "Run 'gradle wrapper --gradle-version 8.4' once from a system Gradle install"
    echo "or open the project in Android Studio (which auto-syncs the wrapper)."
    exit 1
fi

exec java $JAVA_OPTS -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
