#!/usr/bin/env bash
# Builds KEmulator nnmod (the x64 flavour) without an IDE, into out/headless/:
#
#   out/headless/KEmulator.jar   the emulator with its libraries inside, as the
#                                KEmulator_x64 artifact of the IDEA project
#   out/headless/...             the runtime files of home/ (languages, SWT,
#                                LWJGL and native libraries)
#
# so out/headless is a complete KEmulator directory: for the window
# (java -jar out/headless/KEmulator.jar) and for headless runs
# (headless/kemulator-headless.sh, see HeadlessMode.md).
#
# Needs a JDK (javac and jar on the PATH, or JAVAC and JAR). KEmulator targets
# Java 8 and uses one class of the JDK's internal sound API, so it compiles
# against Java 8's class library: set JAVA8_HOME (a Java 8 JDK or JRE) or
# RT_JAR (its rt.jar). Without one the build targets Java 11 instead, and the
# result needs Java 11 or later.
#
# Environment: OUT (default out/headless), JAVAC, JAR, JAVA8_HOME, RT_JAR.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="${OUT:-out/headless}"
BUILD="out/build"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

if [ -z "${RT_JAR:-}" ] && [ -n "${JAVA8_HOME:-}" ]; then
	for f in "$JAVA8_HOME/jre/lib/rt.jar" "$JAVA8_HOME/lib/rt.jar"; do
		if [ -f "$f" ]; then
			RT_JAR="$f"
			break
		fi
	done
fi

case "$(uname -s)" in
	MINGW* | MSYS* | CYGWIN*) SEP=';' ;;
	*) SEP=':' ;;
esac

# the libraries the KEmulator_x64 artifact unpacks into KEmulator.jar
LIBS="bridj-0.7.0 jinput jutils webcam-capture-0.3.12 asm-all-5.2 zip lwjgl-opengl lwjgl
lwjgl3-swt-common lwjgl-glfw vlcj-4.7.3 vlcj-natives-4.7.0 jna-5.7.0 jna-platform-5.7.0"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/jar" "$OUT"

# KEmulator_base and micro3d_gl need each other to compile: compile them
# together, then move micro3d_gl's classes into micro3d_gl.jar as its artifact does
find src/main src/media src/midp src/nnapi src/nokia src/oem src/3d x64/src m3g_lwjgl/src micro3d_gl/src \
	-name '*.java' | sort > "$BUILD/sources.txt"

# the project compiles against the Windows SWT (some code, used on Windows
# only, reaches into its FontData); every platform's SWT loads at run time
SWT_JAR="home/swt-win32-x86.jar"
CP=""
for f in lib/*.jar; do
	CP="$CP$f$SEP"
done
CP="$CP$SWT_JAR"

if [ -n "${RT_JAR:-}" ]; then
	echo "compiling for Java 8 against $RT_JAR"
	TARGET=(-source 8 -target 8 -bootclasspath "$RT_JAR")
else
	echo "no Java 8 class library (set JAVA8_HOME or RT_JAR): compiling for Java 11"
	TARGET=(-source 11 -target 11 --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED)
fi
"$JAVAC" -nowarn -Xlint:-options -encoding UTF-8 -proc:none "${TARGET[@]}" \
	-cp "$CP" -d "$BUILD/classes" "@$BUILD/sources.txt"

# the MascotCapsule engines: micro3d_gl (compiled above) and micro3d_sw
mkdir -p "$BUILD/micro3d_gl" "$BUILD/micro3d_sw"
(cd micro3d_gl/src && find . -name '*.java') | while read -r f; do
	base="${f#./}"
	base="${base%.java}"
	for c in "$BUILD/classes/$base.class" "$BUILD/classes/$base"\$*.class; do
		if [ -e "$c" ]; then
			rel="${c#"$BUILD/classes/"}"
			mkdir -p "$BUILD/micro3d_gl/$(dirname "$rel")"
			mv "$c" "$BUILD/micro3d_gl/$rel"
		fi
	done
done
find micro3d_sw/src -name '*.java' | sort > "$BUILD/micro3d_sw.txt"
"$JAVAC" -nowarn -Xlint:-options -encoding UTF-8 -proc:none "${TARGET[@]}" \
	-cp "$BUILD/classes$SEP$CP" -d "$BUILD/micro3d_sw" "@$BUILD/micro3d_sw.txt"

# the libraries first, so that KEmulator's own classes and resources win
for lib in $LIBS; do
	(cd "$BUILD/jar" && "$JAR" xf "../../../lib/$lib.jar")
done
rm -f "$BUILD/jar/META-INF/"*.SF "$BUILD/jar/META-INF/"*.RSA "$BUILD/jar/META-INF/"*.DSA \
	"$BUILD/jar/META-INF/MANIFEST.MF" "$BUILD/jar/META-INF/INDEX.LIST"
find "$BUILD/jar" -name module-info.class -delete
cp -R "$BUILD/classes/." "$BUILD/jar/"
cp -R src/res/. "$BUILD/jar/"
REVISION="$(git describe --tags --always HEAD 2>/dev/null || echo unknown)"
printf 'Manifest-Version: 1.0\nGit-Revision: %s\n' "$REVISION" > "$BUILD/jar/META-INF/version.mf"
rm -f "$OUT/KEmulator.jar" "$OUT/micro3d_gl.jar" "$OUT/micro3d_sw.jar"
"$JAR" cfm "$OUT/KEmulator.jar" src/main/META-INF/MANIFEST.MF -C "$BUILD/jar" .
"$JAR" cf "$OUT/micro3d_gl.jar" -C "$BUILD/micro3d_gl" .
"$JAR" cf "$OUT/micro3d_sw.jar" -C "$BUILD/micro3d_sw" .

# the runtime files of home/, without its starter scripts
(cd home && tar cf - --exclude='*.sh' --exclude='*.bat' --exclude='KEmulator*.jar' --exclude='version.mf' .) \
	| (cd "$OUT" && tar xf -)

echo "built $OUT/KEmulator.jar ($REVISION)"
