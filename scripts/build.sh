#!/usr/bin/env bash
# Build the launcher on macOS or Linux. Java is compiled once. Each application
# id is a separate aapt2 link of that same dex, because the id is stored in the
# manifest and resource table and cannot be rewritten inside a finished APK.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PERFORMANCE=0
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JDK="${JAVA_HOME:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --performance-package) PERFORMANCE=1; shift ;;
    --sdk) SDK=$2; shift 2 ;;
    --jdk) JDK=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

APP_PACKAGE=dev.plutocrown.factorio
JAVA_PACKAGE=org.factorio.local.launcher
PERF_PACKAGE=com.miHoYo.Yuanshen
XZ_SOURCE_SHA=c35c682fa8b617c1f6f21270df14bad4e11df2fe46962dfe45e765b7aec0181b
XZ_BINARY_SHA=3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b

if [[ -z "$SDK" && -d "$HOME/Library/Android/sdk" ]]; then
  SDK="$HOME/Library/Android/sdk"
fi
if [[ -z "$JDK" && -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac" ]]; then
  JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo "Android SDK not found. Set ANDROID_HOME or pass --sdk." >&2
  exit 1
fi
if [[ -z "$JDK" || ! -x "$JDK/bin/javac" ]]; then
  echo "JDK not found. Set JAVA_HOME or pass --jdk." >&2
  exit 1
fi

# Build Tools 34 D8 crashes on JDK 21 anonymous-class bytecode. 35.0.0 is the
# first installed release that accepts it, and CI installs that same version.
if [[ -d "$SDK/build-tools/35.0.0" ]]; then
  BUILD_TOOLS="$SDK/build-tools/35.0.0"
else
  BUILD_TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)
fi
if [[ -f "$SDK/platforms/android-34/android.jar" ]]; then
  ANDROID_JAR="$SDK/platforms/android-34/android.jar"
elif [[ -f "$SDK/platforms/android-33/android.jar" ]]; then
  ANDROID_JAR="$SDK/platforms/android-33/android.jar"
else
  ANDROID_JAR=$(find "$SDK/platforms" -name android.jar | sort | tail -n 1)
fi
if [[ -z "${BUILD_TOOLS:-}" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "aapt2 not found under $SDK/build-tools" >&2
  exit 1
fi
if [[ -z "${ANDROID_JAR:-}" || ! -f "$ANDROID_JAR" ]]; then
  echo "android.jar not found under $SDK/platforms" >&2
  exit 1
fi

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
BUILD="$ROOT/build"
CACHE="$ROOT/.cache"
LOCAL="$ROOT/.local"
ARTIFACTS="$ROOT/artifacts"
MARKER="$ROOT/.factorio-migration-workspace.json"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

if [[ ! -f "$MARKER" ]]; then
  printf '%s\n' '{}' > "$MARKER"
  echo "Created workspace marker $MARKER"
fi
if ! grep -q 'package="dev.plutocrown.factorio"' "$MANIFEST"; then
  echo "Manifest package must be dev.plutocrown.factorio" >&2
  exit 1
fi
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -n 1)
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$MANIFEST" | head -n 1)
VERSION_FILE=$(printf '%s\n' "$VERSION_NAME" | sed -n 's/^\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p')
if [[ -z "$VERSION_FILE" ]]; then
  echo "Version name must start with a numeric version" >&2
  exit 1
fi

rm -rf "$BUILD/classes" "$BUILD/generated" "$BUILD/generated-performance" "$BUILD/dex" "$BUILD/tests" "$BUILD/compile-stubs" "$BUILD/import-tests"
mkdir -p "$BUILD" "$CACHE" "$LOCAL" "$ARTIFACTS" "$CACHE/tmp" \
  "$BUILD/classes" "$BUILD/generated" "$BUILD/dex" "$BUILD/tests" "$BUILD/compile-stubs"
export TMPDIR="$CACHE/tmp"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

tool() {
  echo "TOOL $(basename "$1")" >&2
  "$@"
}

write_argfile() {
  local list=$1 out=$2 file
  : > "$out"
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    printf '"%s"\n' "$file" >> "$out"
  done < "$list"
}

XZ_JAR="$CACHE/xz-1.12-sources.jar"
if [[ ! -f "$XZ_JAR" ]]; then
  echo "Downloading only XZ for Java 1.12 into project cache"
  curl -fsSL --retry 3 --max-time 60 \
    -o "$XZ_JAR.part" \
    "https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12-sources.jar"
  mv "$XZ_JAR.part" "$XZ_JAR"
fi
if [[ $(sha256_file "$XZ_JAR") != "$XZ_SOURCE_SHA" ]]; then
  echo "XZ source dependency checksum mismatch" >&2
  exit 1
fi

python3 - "$ARTIFACTS/dependency.json" "$XZ_SOURCE_SHA" "$XZ_BINARY_SHA" <<'PY'
import json, sys
path, source_sha, binary_sha = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "artifact": "org.tukaani:xz:1.12:sources",
        "sha256": source_sha,
        "referenceBinarySha256": binary_sha,
        "license": "0BSD",
        "source": "https://repo.maven.apache.org/maven2/org/tukaani/xz/1.12/xz-1.12-sources.jar",
        "builtWith": "JDK --release 8",
    }, handle, separators=(",", ":"))
    handle.write("\n")
PY

rm -rf "$BUILD/xz-sources" "$BUILD/xz-classes"
mkdir -p "$BUILD/xz-sources" "$BUILD/xz-classes"
python3 - "$XZ_JAR" "$BUILD/xz-sources" <<'PY'
import os, re, sys, zipfile
jar, dest = sys.argv[1:]
pattern = re.compile(r"^org/tukaani/xz/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java$")
with zipfile.ZipFile(jar) as archive:
    for info in archive.infolist():
        if not pattern.match(info.filename):
            continue
        target = os.path.join(dest, info.filename)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with archive.open(info) as source, open(target, "wb") as output:
            output.write(source.read())
PY
find "$BUILD/xz-sources" -name '*.java' -type f | sort > "$BUILD/xz-sources.list"
write_argfile "$BUILD/xz-sources.list" "$BUILD/xz-sources.args"
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -g -d "$BUILD/xz-classes" "@$BUILD/xz-sources.args"
tool "$JDK/bin/jar" cf "$BUILD/xz-android-base.jar" -C "$BUILD/xz-classes" .

find "$ROOT/compile-stubs" -name '*.java' -type f | sort > "$BUILD/stub-sources.list"
write_argfile "$BUILD/stub-sources.list" "$BUILD/stub-sources.args"
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/compile-stubs" "@$BUILD/stub-sources.args"

tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/org/factorio/local/launcher/SafeTarReader.java" \
  "$ROOT/tests/ArchiveTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" ArchiveTests
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/org/factorio/local/launcher/GameProfileConfig.java" \
  "$ROOT/tests/GameProfileConfigTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" org.factorio.local.launcher.GameProfileConfigTests
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/org/factorio/local/launcher/LiveGraphics.java" \
  "$ROOT/tests/LiveGraphicsTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" org.factorio.local.launcher.LiveGraphicsTests
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/com/termux/x11/TouchMouseState.java" \
  "$ROOT/app/src/main/java/com/termux/x11/RenderSize.java" \
  "$ROOT/tests/TouchMouseStateTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" com.termux.x11.TouchMouseStateTests
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/org/factorio/local/launcher/GameFiles.java" \
  "$ROOT/tests/GameFilesTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" org.factorio.local.launcher.GameFilesTests
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -d "$BUILD/tests" \
  "$ROOT/app/src/main/java/org/factorio/local/launcher/LanguagePolicy.java" \
  "$ROOT/tests/LanguagePolicyTests.java"
tool "$JDK/bin/java" -cp "$BUILD/tests" org.factorio.local.launcher.LanguagePolicyTests

tool "$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/resources.zip"
tool "$BUILD_TOOLS/aapt2" link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" --custom-package "$JAVA_PACKAGE" \
  --java "$BUILD/generated" -A "$ROOT/app/src/main/assets" "$BUILD/resources.zip"

find "$ROOT/app/src/main/java" -name '*.java' -type f | sort > "$BUILD/app-sources.list"
find "$BUILD/generated" -name '*.java' -type f | sort >> "$BUILD/app-sources.list"
write_argfile "$BUILD/app-sources.list" "$BUILD/app-sources.args"
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 \
  -classpath "$ANDROID_JAR:$BUILD/xz-android-base.jar:$BUILD/compile-stubs" \
  -d "$BUILD/classes" "@$BUILD/app-sources.args"

rm -rf "$BUILD/import-tests"
mkdir -p "$BUILD/import-tests"
IMPORT_CP="$BUILD/import-tests:$BUILD/classes:$ANDROID_JAR:$BUILD/xz-android-base.jar"
tool "$JDK/bin/javac" -encoding UTF-8 --release 8 -classpath "$IMPORT_CP" -d "$BUILD/import-tests" \
  "$ROOT/tests/host-stubs/android/content/Context.java" \
  "$ROOT/tests/ImportLeaseTests.java"
tool "$JDK/bin/java" -cp "$IMPORT_CP" org.factorio.local.launcher.ImportLeaseTests

tool "$JDK/bin/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
tool "$JDK/bin/java" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  "$BUILD/classes.jar" "$BUILD/xz-android-base.jar"

rm -rf "$BUILD/native"
mkdir -p "$BUILD/native/lib"
HAS_NATIVE=0
if [[ -d "$ROOT/app/src/main/jniLibs" ]] && find "$ROOT/app/src/main/jniLibs" -type f | grep -q .; then
  cp -R "$ROOT/app/src/main/jniLibs/." "$BUILD/native/lib/"
  HAS_NATIVE=1
else
  echo "No jniLibs; APK will not contain the pinned native runtime"
fi

if [[ -n "${FACTORIO_KEYSTORE_BASE64:-}" ]]; then
  : "${FACTORIO_KEY_ALIAS:?FACTORIO_KEY_ALIAS is required when a keystore is provided}"
  : "${FACTORIO_KEYSTORE_PASSWORD:?FACTORIO_KEYSTORE_PASSWORD is required when a keystore is provided}"
  KEYSTORE="$LOCAL/release.keystore"
  python3 - "$KEYSTORE" <<'PY'
import base64, os, sys
open(sys.argv[1], "wb").write(base64.b64decode(os.environ["FACTORIO_KEYSTORE_BASE64"]))
PY
  KEY_ALIAS=$FACTORIO_KEY_ALIAS
  STORE_PASS=$FACTORIO_KEYSTORE_PASSWORD
  KEY_PASS=${FACTORIO_KEY_PASSWORD:-$FACTORIO_KEYSTORE_PASSWORD}
else
  KEYSTORE="$LOCAL/release.keystore"
  KEY_ALIAS=factoriorelease
  PASS_FILE="$LOCAL/release-keystore.pass"
  if [[ -f "$PASS_FILE" ]]; then
    STORE_PASS=$(tr -d '\n' < "$PASS_FILE")
  else
    STORE_PASS=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
    printf '%s\n' "$STORE_PASS" > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
  fi
  KEY_PASS=$STORE_PASS
  if [[ ! -f "$KEYSTORE" ]]; then
    tool "$JDK/bin/keytool" -genkeypair -noprompt -keystore "$KEYSTORE" \
      -alias "$KEY_ALIAS" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
      -dname "CN=Factorio Local Release" -keyalg RSA -keysize 2048 -validity 10000
  fi
fi

package_apk() {
  local unsigned=$1 apk_name=$2 package_name=$3 performance=$4
  local payload="$BUILD/$package_name.unsigned.apk"
  local aligned="$BUILD/$package_name.aligned.apk"
  local apk="$ARTIFACTS/$apk_name"
  local dex badge
  cp "$unsigned" "$payload"
  find "$BUILD/dex" -name 'classes*.dex' -type f | sort > "$BUILD/dex-files.list"
  while IFS= read -r dex; do
    [[ -n "$dex" ]] || continue
    tool "$JDK/bin/jar" uf "$payload" -C "$(dirname "$dex")" "$(basename "$dex")"
  done < "$BUILD/dex-files.list"
  if [[ "$HAS_NATIVE" -eq 1 ]]; then
    tool "$JDK/bin/jar" uf "$payload" -C "$BUILD/native" lib
  fi
  tool "$BUILD_TOOLS/zipalign" -f 4 "$payload" "$aligned"
  tool "$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" --out "$apk" "$aligned"
  tool "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$apk"
  tool "$BUILD_TOOLS/zipalign" -c 4 "$apk"
  badge=$( "$BUILD_TOOLS/aapt2" dump badging "$apk" )
  printf '%s\n' "$badge" >&2
  if ! printf '%s\n' "$badge" | grep -F -q "package: name='$package_name'"; then
    echo "Application id mismatch for $package_name" >&2
    exit 1
  fi
  if ! printf '%s\n' "$badge" | grep -F -q "launchable-activity: name='org.factorio.local.launcher.MainActivity'"; then
    echo "Launcher activity was rewritten with the application id" >&2
    exit 1
  fi
  if printf '%s\n' "$badge" | grep -F -q "application-debuggable"; then
    echo "Release APK is still debuggable" >&2
    exit 1
  fi
  python3 - "$BUILD/package-results.jsonl" "$apk" "$package_name" "$performance" "$(sha256_file "$apk")" <<'PY'
import json, os, sys
out, apk, package_name, performance, digest = sys.argv[1:]
with open(out, "a", encoding="utf-8") as handle:
    json.dump({
        "apk": apk,
        "bytes": os.path.getsize(apk),
        "sha256": digest,
        "package": package_name,
        "performancePackage": performance == "1",
    }, handle, separators=(",", ":"))
    handle.write("\n")
PY
}

: > "$BUILD/package-results.jsonl"
package_apk "$BUILD/unsigned.apk" \
  "FactorioLauncher-${VERSION_FILE}.apk" "$APP_PACKAGE" 0
if [[ "$PERFORMANCE" -eq 1 ]]; then
  mkdir -p "$BUILD/generated-performance"
  tool "$BUILD_TOOLS/aapt2" link -o "$BUILD/unsigned-performance.apk" -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" --custom-package "$JAVA_PACKAGE" \
    --rename-manifest-package "$PERF_PACKAGE" --rename-resources-package "$PERF_PACKAGE" \
    --java "$BUILD/generated-performance" -A "$ROOT/app/src/main/assets" "$BUILD/resources.zip"
  DEFAULT_R="$BUILD/generated/org/factorio/local/launcher/R.java"
  PERF_R="$BUILD/generated-performance/org/factorio/local/launcher/R.java"
  if ! cmp -s "$DEFAULT_R" "$PERF_R"; then
    echo "Performance package changed R.java; the compiled dex cannot be reused" >&2
    exit 1
  fi
  package_apk "$BUILD/unsigned-performance.apk" \
    "FactorioLauncher-${VERSION_FILE}-YuanShen-sign.apk" "$PERF_PACKAGE" 1
fi

BASE_JAR_SHA=$(sha256_file "$BUILD/xz-android-base.jar")
export VERSION_NAME VERSION_CODE JAVA_PACKAGE SDK JDK BUILD_TOOLS HAS_NATIVE \
  XZ_SOURCE_SHA BASE_JAR_SHA ANDROID_JAR
python3 - "$ARTIFACTS/build-result.json" "$BUILD/package-results.jsonl" <<'PY'
import json, os, sys
result_path, lines_path = sys.argv[1:]
with open(lines_path, encoding="utf-8") as handle:
    packages = [json.loads(line) for line in handle if line.strip()]
report = {
    "version": os.environ["VERSION_NAME"],
    "versionCode": int(os.environ["VERSION_CODE"]),
    "javaPackage": os.environ["JAVA_PACKAGE"],
    "sdk": os.environ["SDK"],
    "jdk": os.environ["JDK"],
    "androidJar": os.environ["ANDROID_JAR"],
    "buildTools": os.path.basename(os.environ["BUILD_TOOLS"]),
    "buildType": "release",
    "nativeLibraries": os.environ["HAS_NATIVE"] == "1",
    "dependencySourceSha256": os.environ["XZ_SOURCE_SHA"],
    "dependencyBuiltJarSha256": os.environ["BASE_JAR_SHA"],
    "packages": packages,
}
text = json.dumps(report, separators=(",", ":"))
with open(result_path, "w", encoding="utf-8") as handle:
    handle.write(text)
    handle.write("\n")
print("__FACTORIO_REPORT__" + text)
PY
