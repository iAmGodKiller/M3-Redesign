#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/.morphe-build}"
DIST_DIR="${DIST_DIR:-$ROOT_DIR/dist}"
MORPHE_REPO="${MORPHE_REPO:-https://github.com/MorpheApp/morphe-patches.git}"
MORPHE_REF="${MORPHE_REF:-main}"
BUILD_VERSION="${BUILD_VERSION:-}"
ARTIFACT_VERSION="${ARTIFACT_VERSION:-}"

echo "==> Preparing Morphe build workspace ($MORPHE_REPO @ $MORPHE_REF)"
if [[ ! -d "$WORK_DIR/.git" ]]; then
  rm -rf "$WORK_DIR"
  git clone --depth 1 --branch "$MORPHE_REF" "$MORPHE_REPO" "$WORK_DIR"
else
  git -C "$WORK_DIR" fetch --depth 1 origin "$MORPHE_REF"
  git -C "$WORK_DIR" checkout -f FETCH_HEAD
  git -C "$WORK_DIR" clean -fdx -e local.properties -e .gradle
fi

if [[ -f "$WORK_DIR/gradle/libs.versions.toml" ]]; then
  echo "==> Upstream Morphe versions:"
  grep -Ei 'morphe|patcher|patches' "$WORK_DIR/gradle/libs.versions.toml" | head -n 20 || true
fi

echo "==> Overlaying M3-Redesign sources and standalone build configuration"
rm -rf \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer" \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation" \
  "$WORK_DIR/patches/src/main/resources"

mkdir -p \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer" \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation" \
  "$WORK_DIR/patches/src/main/resources"

cp -r "$ROOT_DIR/extension/." \
  "$WORK_DIR/extensions/music/src/main/java/app/morphe/extension/music/patches/miniplayer/"
cp -r "$ROOT_DIR/patch/." \
  "$WORK_DIR/patches/src/main/kotlin/app/morphe/patches/music/layout/miniplayer/m3navigation/"

cp "$ROOT_DIR/build-config/extensions-music-build.gradle.kts" "$WORK_DIR/extensions/music/build.gradle.kts"
cp "$ROOT_DIR/build-config/patches-build.gradle.kts" "$WORK_DIR/patches/build.gradle.kts"
cp "$ROOT_DIR/build-config/proguard-rules.pro" "$WORK_DIR/extensions/proguard-rules.pro"
cp "$ROOT_DIR/build-config/gradle.properties" "$WORK_DIR/gradle.properties"

if [[ -n "$BUILD_VERSION" ]]; then
  CLEAN_BUILD_VERSION="${BUILD_VERSION#v}"
  echo "==> Setting patch bundle manifest version to $CLEAN_BUILD_VERSION"
  sed -i.bak -E "s|^version[[:space:]]*=.*|version = ${CLEAN_BUILD_VERSION}|" "$WORK_DIR/gradle.properties"
  rm -f "$WORK_DIR/gradle.properties.bak"
fi

if [[ -n "${ANDROID_HOME:-}" && ! -f "$WORK_DIR/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$WORK_DIR/local.properties"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && ! -f "$WORK_DIR/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$WORK_DIR/local.properties"
fi

GRADLE_COMMON_ARGS=("-x" "test" "--stacktrace")
if [[ -n "${GITHUB_ACTOR:-}" && -n "${GITHUB_TOKEN:-}" ]]; then
  GRADLE_COMMON_ARGS+=("-Pgpr.user=${GITHUB_ACTOR}" "-Pgpr.key=${GITHUB_TOKEN}")
fi

echo "==> Generating patches list and building Android DEX .mpp bundle (buildAndroid runs last)"
(
  cd "$WORK_DIR"
  chmod +x ./gradlew
  ./gradlew :extensions:music:clean :patches:generatePatchesList "${GRADLE_COMMON_ARGS[@]}"
  ./gradlew :patches:buildAndroid "${GRADLE_COMMON_ARGS[@]}"
)

echo "==> Selecting and verifying Android DEX .mpp bundle"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

MPP_FILE="$(python3 - <<'PY' "$WORK_DIR/patches/build/libs"
import pathlib, sys, zipfile

root = pathlib.Path(sys.argv[1])
candidates = sorted(root.rglob("*.mpp"))
if not candidates:
    raise SystemExit(f"ERROR: No .mpp files found under {root}")

chosen = None
for cand in candidates:
    if cand.name.endswith(("-javadoc.mpp", "-sources.mpp")):
        print(f"Skipping non-runtime archive: {cand.name}", file=sys.stderr)
        continue
    with zipfile.ZipFile(cand) as zf:
        names = set(zf.namelist())
    has_root_dex = "classes.dex" in names
    has_ext = "extensions/m3navigation.mpe" in names
    has_patch_cls = any("M3NavigationPatch" in n and n.endswith(".class") for n in names)
    has_tsb_cls = any("TransparentStatusBarPatch" in n and n.endswith(".class") for n in names)
    print(
        f"Candidate: {cand.name} (size={cand.stat().st_size}, "
        f"root_classes_dex={has_root_dex}, m3_ext={has_ext}, "
        f"m3_patch_cls={has_patch_cls}, tsb_patch_cls={has_tsb_cls})",
        file=sys.stderr,
    )
    if has_root_dex and has_ext and has_patch_cls and has_tsb_cls:
        chosen = cand
        break

if chosen is None:
    raise SystemExit(
        "ERROR: Built .mpp missing root classes.dex, M3NavigationPatch, TransparentStatusBarPatch, or extensions/m3navigation.mpe!"
    )

with zipfile.ZipFile(chosen) as zf:
    print("=== Selected .mpp verification ===", file=sys.stderr)
    for info in zf.infolist():
        if (
            info.filename == "classes.dex"
            or info.filename.startswith(("extensions/", "META-INF/MANIFEST.MF", "app/morphe/patches/music/"))
        ):
            print(f"  {info.filename} ({info.file_size} bytes)", file=sys.stderr)

print(chosen)
PY
)"

DEFAULT_VERSION="$(grep -E '^version\s*=' "$WORK_DIR/gradle.properties" | head -n 1 | cut -d'=' -f2 | tr -d '[:space:]')"
VERSION_SUFFIX="${ARTIFACT_VERSION:-${BUILD_VERSION:-$DEFAULT_VERSION}}"
OUT_MPP="$DIST_DIR/M3-Redesign-${VERSION_SUFFIX}.mpp"
cp "$MPP_FILE" "$OUT_MPP"

PATCHES_LIST="$(find "$WORK_DIR" -maxdepth 3 -type f -name 'patches-list.json' | head -n 1 || true)"
if [[ -n "$PATCHES_LIST" ]]; then
  cp "$PATCHES_LIST" "$DIST_DIR/patches-list.json"
  echo "==> patches-list.json contents:"
  cat "$DIST_DIR/patches-list.json"
fi

if command -v python3 >/dev/null 2>&1; then
  echo "==> Auditing built .mpp for machine/privacy metadata"
  python3 "$ROOT_DIR/scripts/audit-mpp.py" "$OUT_MPP"
fi

echo "==> Build complete:"
ls -lh "$DIST_DIR"
