#!/usr/bin/env bash
# Runs KEmulator without a window (see HeadlessMode.md):
#
#   headless/kemulator-headless.sh -jar game.jar [-vtime] [-script run.txt] [-out dir] [options]
#
# KEM_DIR is the KEmulator directory (default: out/headless, made by
# headless/build.sh); JAVA the java to run it with (default: java on the PATH);
# JAVA_OPTS extra JVM options. The exit code is the run's result: 0 ok,
# 1 error, 2 failed expectation, 3 hang or timeout, 4 bad usage, 5 the MIDlet
# exited early.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEM_DIR="${KEM_DIR:-$ROOT/out/headless}"
JAVA="${JAVA:-java}"

# Java 9 and later: the openings KEmulator asks for when it restarts itself
VERSION="$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}')"
MAJOR="${VERSION%%.*}"
if [ "$MAJOR" = "1" ]; then
	MAJOR=8
fi
OPTS=(-Djava.awt.headless=true -Dfile.encoding=UTF-8)
if [ "$MAJOR" -ge 9 ]; then
	for p in java.base/java.lang java.base/java.lang.reflect java.base/java.lang.ref java.base/java.io \
		java.base/java.nio java.base/java.util jdk.unsupported/sun.misc java.desktop/com.sun.media.sound \
		java.desktop/javax.sound.midi; do
		OPTS+=(--add-opens "$p=ALL-UNNAMED")
	done
	OPTS+=(--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED)
fi
if [ "$MAJOR" -ge 17 ]; then
	OPTS+=(--enable-native-access=ALL-UNNAMED)
fi

# shellcheck disable=SC2086
exec "$JAVA" "${OPTS[@]}" ${JAVA_OPTS:-} -jar "$KEM_DIR/KEmulator.jar" -headless "$@"
