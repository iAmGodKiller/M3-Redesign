# M3-Redesign

A standalone **Material 3 Expressive** navigation bar and miniplayer redesign add-on patch for **YouTube Music**, built for the [Morphe](https://github.com/MorpheApp/morphe-patches) patcher ecosystem.

**Author:** `iAmGodKiller`  
**Included Patches:**
- **`M3 Redesign addon`** *(stable — off by default after patching; enable in Morphe Settings)*
- **`Transparent status bar`** *(pre-release / `dev` channel only — enable **Use pre-releases** in Morphe Manager to test)*

---

## Highlights

- **In-Place Native Miniplayer Redesign** — Restyles YouTube Music's native `@id/mini_player` directly instead of replacing it, preserving 120Hz `WatchWhileLayout` expand/collapse transitions, native horizontal track swiping, and native Now Playing / Up Next / Lyrics / Comments panels.
- **Material 3 Expressive Floating Navigation Bar** — Connected frosted-glass capsules (`Home` · `Miniplayer` · `Library`) with live hardware-accelerated backdrop blur (Android 12+), auto-hide on scroll, and optional tablet/landscape split layout.
- **Rotating 12-Lobed Wavy Search Button** — Customizable lobe depth, scale, opacity, inverted palette, and smooth continuous rotation.
- **Animated Wave Seekbar** — Custom animated sine-wave seekbar embedded inside the miniplayer, with native miniplayer progress bar residue cleanly suppressed (while leaving the fullscreen Now Playing seekbar untouched).
- **Dynamic Album Colour Palette & Now Playing Ambient Mode** — Extracts Material 3 tonal colours and a living, blurred multi-quadrant ambient glow directly from the song artwork behind Now Playing, alongside rounded miniplayer album art that scales automatically with navigation bar height.
- **Full-Width Expanded Lyrics & Up Next Header** — Automatically widens the top miniplayer bar when viewing Lyrics or Up Next inside Now Playing, then smoothly narrows back into the M3 capsule when minimized.
- **Last-Played Track Persistence** — Remembers your last played song & colour when YouTube Music starts idle, falling back to *"Ek Pal Ka Jeena"* by *Lucky Ali* when no prior track is stored (tap to resume playback).
- **Automatic Official Morphe Settings Harmony** — Enabling `M3 Redesign addon` backs up your existing official Morphe toggles, syncs and greys out conflicting official settings automatically, and restores them cleanly if you turn M3 Redesign back off.

---

## Installation (Morphe Manager)

### Method 1 — Add as a Remote Patch Source (Recommended)
1. Open **Morphe Manager** → **Patch Sources** → **Add Source**.
2. Paste this repository URL:
   ```text
   https://github.com/iAmGodKiller/M3-Redesign
   ```
   *(Or open via deeplink: `morphe://sources/add?url=https://github.com/iAmGodKiller/M3-Redesign`)*
3. Keep the official **Morphe** patches source enabled alongside **M3-Redesign**.
4. Select **M3 Redesign addon** with your usual YouTube Music patches and patch YouTube Music.
5. Open the patched YouTube Music app → **Settings → Morphe → M3 Redesign addon** → turn on **Enable M3 Redesign addon** and restart.

### Method 2 — Manual `.mpp` File
1. Download the latest `M3-Redesign-v*.mpp` from the **[Releases](https://github.com/iAmGodKiller/M3-Redesign/releases)** page.
2. In **Morphe Manager**, add the downloaded `.mpp` file alongside the official Morphe patches and patch YouTube Music.

---

## Official Morphe Patch Harmony

| Official Morphe Setting | Behaviour while M3 Redesign is ON |
| --- | --- |
| Miniplayer Previous / Next buttons | Automatically disabled & greyed out (restored if M3 is turned off) |
| Change Miniplayer Color | Synced automatically with M3's *Automatic album colour palette* toggle & greyed out |
| Glass Navigation Bar & Nav Bar Colour / Hide toggles | Automatically disabled & greyed out to prevent visual conflicts |
| Miniplayer Lyrics | **Fully compatible** — real track identity is preserved so lyric updates never reset the wave seekbar |
| Forced Miniplayer & Swipe-to-Dismiss | **Fully compatible** |

---

## Customization

All options live under **Settings → Morphe → M3 Redesign addon**:

- **Master toggle** (off by default) & one-tap **Reset M3 Redesign settings**
- **Miniplayer & Now Playing**: Show album art (rounded & auto-scaled to bar height), show animated wave seekbar, automatic album colour palette, Now Playing ambient mode & intensity slider
- **Library & FABs**: Swap Library icon with `+` / Shuffle while in Library, hide redundant playlist Play FAB
- **Wavy Search button**: Rotate toggle, invert colours, size scale, lobe depth, rotation speed, opacity
- **Layout & Glass**: Navigation bar height, max width, bar opacity, backdrop blur radius (`0` = off), auto-hide on scroll (`Both`, `Search only`, `Nav only`, `Off`), tablet/landscape left-right split

---

## Building From Source

### Option A — GitHub Actions (Recommended)
1. **Stable release** (`M3 Redesign addon` only): push a stable tag (e.g. `v3.1.0`) or go to **Actions** → **Build & Release M3-Redesign** → **Run workflow** with `prerelease` unchecked. Updates `patches-bundle.json` on `main`.
2. **Pre-release** (includes `prerelease/` patches such as `Transparent status bar`): push a pre-release tag (e.g. `v3.1.0-dev.1`) or run the workflow with `prerelease` checked. Publishes a GitHub Pre-release and updates `patches-bundle.json` on the `dev` branch for Morphe Manager's pre-release toggle.

### Option B — Local Build (Linux / macOS)
Requirements: **JDK 17**, **Android SDK** (`ANDROID_HOME` set), **Git**, and **Python 3**.

```bash
chmod +x scripts/build-local.sh
# Stable bundle (M3 Redesign addon only):
./scripts/build-local.sh
# Pre-release bundle (includes prerelease/ patches):
INCLUDE_PRERELEASE=true ./scripts/build-local.sh
```

The standalone bundle and patch list will be generated in `dist/`:
- `dist/M3-Redesign-v*.mpp`
- `dist/patches-list.json`

---

## Repository Structure

```text
M3-Redesign/
├── .github/workflows/build-and-release.yml   # Automated CI build & GitHub Release / Pre-release workflow
├── build-config/                             # Standalone Gradle & ProGuard overlays
├── extension/                                # Stable Java runtime extension (M3NavigationFilter & M3NavigationPreference)
├── patch/                                    # Stable Kotlin Morphe patch & bytecode fingerprints
├── prerelease/                               # Pre-release patches & extensions (e.g. Transparent status bar)
├── scripts/
│   ├── build-local.sh                        # One-command local build script (stable & pre-release)
│   └── audit-mpp.py                          # Bundle manifest & metadata privacy checker
├── patches-bundle.json                       # Remote patch source manifest for Morphe Manager
├── patches-list.json                         # Generated patch catalog
├── LICENSE
└── README.md
```

---

## License & Credits

- Licensed under **GPL-3.0-only** — see [`LICENSE`](LICENSE).
- Built for the [Morphe](https://github.com/MorpheApp/morphe-patches) ecosystem.
