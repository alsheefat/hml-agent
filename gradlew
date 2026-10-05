#!/bin/sh
#
# Self-bootstrapping Gradle wrapper script.
#
# This project shipped gradle/wrapper/gradle-wrapper.properties but NOT the gradlew script or
# gradle-wrapper.jar, so any CI step that runs `chmod +x gradlew` or `./gradlew assembleDebug`
# failed straight away with exit code 1 before compiling anything.
#
# Behaviour:
#   1. If a real gradle/wrapper/gradle-wrapper.jar exists, behave like the standard wrapper.
#   2. Otherwise download the Gradle version named in gradle-wrapper.properties (8.9), cache it
#      under $GRADLE_USER_HOME, and run it.
#   3. If the download is impossible, fall back to a `gradle` already on PATH.
#
set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$JAR" ]; then
    exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

URL=$(grep '^distributionUrl=' "$PROPS" | cut -d= -f2- | sed 's/\\//g' | tr -d '\r')
ZIP_NAME=$(basename "$URL")
DIST_NAME=${ZIP_NAME%.zip}                      # e.g. gradle-8.9-bin
VERSION_DIR=$(echo "$DIST_NAME" | sed 's/-bin$//; s/-all$//')   # e.g. gradle-8.9
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/hml-bootstrap/$DIST_NAME"
GRADLE_BIN="$CACHE/$VERSION_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$CACHE"
    TMP_ZIP="$CACHE/$ZIP_NAME"
    echo "gradlew: downloading $URL" >&2
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$TMP_ZIP" "$URL" || rm -f "$TMP_ZIP"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TMP_ZIP" "$URL" || rm -f "$TMP_ZIP"
    fi
    if [ -s "$TMP_ZIP" ]; then
        if command -v unzip >/dev/null 2>&1; then
            unzip -q -o "$TMP_ZIP" -d "$CACHE"
        else
            python3 -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TMP_ZIP" "$CACHE"
        fi
        chmod +x "$GRADLE_BIN" 2>/dev/null || true
        rm -f "$TMP_ZIP"
    fi
fi

if [ -x "$GRADLE_BIN" ]; then
    exec "$GRADLE_BIN" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
    echo "gradlew: could not download Gradle, using gradle from PATH" >&2
    exec gradle "$@"
fi

echo "gradlew: no gradle-wrapper.jar, download failed, and no 'gradle' on PATH." >&2
exit 1
