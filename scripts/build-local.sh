#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/.morphe-build}"
DIST_DIR="${DIST_DIR:-$ROOT_DIR/dist}"
MORPHE_REPO="${MORPHE_REPO:-https://github.com/MorpheApp/morphe-patches.git}"
MORPHE_REF="${MORPHE_REF:-}"
BUILD_VERSION="${BUILD_VERSION:-}"

clone_or_update() {
  local ref="$1"
  echo "==> Preparing Morphe build workspace ($MORPHE_REPO @ $ref)"
  if [[ ! -d "$WORK_DIR/.git" ]]; then
    rm -rf "$WORK_DIR"
    git clone --depth 1 --branch "$ref" "$MORPHE_REPO" "$WORK_DIR"
  else
    git -C "$WORK_DIR" fetch --depth 1 origin "$ref"
    git -C "$WORK_DIR" checkout -f FETCH_HEAD
    git -C "$WORK_DIR" clean -fdx -e local.properties -e .gradle
  fi
}

if [[ -n "$MORPHE_REF" ]]; then
  clone_or_update "$MORPHE_REF"
else
  clone_or_update "main"
  SETTINGS_JAVA="$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/settings/Settings.java"
  if [[ ! -f "$SETTINGS_JAVA" ]] || ! grep -q "NAVIGATION_BAR_GLASS" "$SETTINGS_JAVA"; then
    echo "==> Upstream 'main' does not include NAVIGATION_BAR_GLASS yet; switching to 'dev'"
    clone_or_update "dev"
  fi
fi

echo "==> Overlaying M3-Redesign sources and standalone build configuration"
rm -rf \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer" \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation"

mkdir -p \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer" \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation"

cp -r "$ROOT_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer/." \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer/"
cp -r "$ROOT_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation/." \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation/"

cp "$ROOT_DIR/build-config/extensions-music-build.gradle.kts" "$WORK_DIR/extensions/music/build.gradle.kts"
cp "$ROOT_DIR/build-config/patches-build.gradle.kts" "$WORK_DIR/patches/build.gradle.kts"
cp "$ROOT_DIR/build-config/proguard-rules.pro" "$WORK_DIR/extensions/proguard-rules.pro"
cp "$ROOT_DIR/build-config/gradle.properties" "$WORK_DIR/gradle.properties"

if [[ -n "$BUILD_VERSION" ]]; then
  echo "==> Setting patch bundle version to $BUILD_VERSION"
  sed -i.bak -E "s|^version[[:space:]]*=.*|version = ${BUILD_VERSION}|" "$WORK_DIR/gradle.properties"
  rm -f "$WORK_DIR/gradle.properties.bak"
fi

if [[ -n "${ANDROID_HOME:-}" && ! -f "$WORK_DIR/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WORK_DIR/local.properties"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && ! -f "$WORK_DIR/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$WORK_DIR/local.properties"
fi

GRADLE_ARGS=(":extensions:music:clean" ":patches:buildAndroid" ":patches:generatePatchesList" "-x" "test" "--stacktrace")
if [[ -n "${GITHUB_ACTOR:-}" && -n "${GITHUB_TOKEN:-}" ]]; then
  GRADLE_ARGS+=("-Pgpr.user=${GITHUB_ACTOR}" "-Pgpr.key=${GITHUB_TOKEN}")
fi

echo "==> Building standalone .mpp bundle with Gradle"
(
  cd "$WORK_DIR"
  chmod +x ./gradlew
  ./gradlew "${GRADLE_ARGS[@]}"
)

echo "==> Collecting build outputs into $DIST_DIR"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

MPP_FILE="$(find "$WORK_DIR/patches/build" -type f -name '*.mpp' | head -n 1)"
if [[ -z "$MPP_FILE" ]]; then
  echo "ERROR: No .mpp artifact produced under $WORK_DIR/patches/build" >&2
  exit 1
fi

VERSION_SUFFIX="${BUILD_VERSION:-$(grep -E '^version\s*=' "$WORK_DIR/gradle.properties" | head -n 1 | cut -d'=' -f2 | tr -d '[:space:]')}"
OUT_MPP="$DIST_DIR/M3-Redesign-${VERSION_SUFFIX}.mpp"
cp "$MPP_FILE" "$OUT_MPP"

PATCHES_LIST="$(find "$WORK_DIR" -maxdepth 3 -type f -name 'patches-list.json' | head -n 1 || true)"
if [[ -n "$PATCHES_LIST" ]]; then
  cp "$PATCHES_LIST" "$DIST_DIR/patches-list.json"
fi

if command -v python3 >/dev/null 2>&1; then
  echo "==> Auditing built .mpp for machine/privacy metadata"
  python3 "$ROOT_DIR/scripts/audit-mpp.py" "$OUT_MPP"
fi

echo "==> Build complete:"
ls -lh "$DIST_DIR"
