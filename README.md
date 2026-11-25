# M3-Redesign

A standalone **Material 3 Expressive** navigation bar and miniplayer redesign add-on patch for **YouTube Music**, built for the [Morphe](https://github.com/MorpheApp/morphe-patches) patcher ecosystem.

**Author:** `iAmGodKiller`  
**Patch Name:** `M3 Redesign addon` *(off by default after patching — enable in Morphe Settings)*

---

## Highlights

- **In-Place Native Miniplayer Redesign** — Restyles YouTube Music's native `@id/mini_player` directly instead of replacing it, preserving 120Hz `WatchWhileLayout` expand/collapse transitions, native horizontal track swiping, and native Now Playing / Up Next / Lyrics / Comments panels.
- **Material 3 Expressive Floating Navigation Bar** — Connected frosted-glass capsules (`Home` · `Miniplayer` · `Library`) with live hardware-accelerated backdrop blur (Android 12+), auto-hide on scroll, and optional tablet/landscape split layout.
- **Rotating 12-Lobed Wavy Search Button** — Customizable lobe depth, scale, opacity, inverted palette, and smooth continuous rotation.
- **Animated Wave Seekbar** — Custom animated sine-wave seekbar embedded inside the miniplayer, with native miniplayer progress bar residue cleanly suppressed (while leaving the fullscreen Now Playing seekbar untouched).
- **Dynamic Album Colour Palette** — Extracts Material 3 tonal colours from the currently playing song artwork and adapts all capsules and icons smoothly.
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
- **Miniplayer**: Show album art, show animated wave seekbar, automatic album colour palette
- **Library & FABs**: Swap Library icon with `+` / Shuffle while in Library, hide redundant playlist Play FAB
- **Wavy Search button**: Rotate toggle, invert colours, size scale, lobe depth, rotation speed, opacity
- **Layout & Glass**: Navigation bar height, max width, bar opacity, backdrop blur radius (`0` = off), auto-hide on scroll (`Both`, `Search only`, `Nav only`, `Off`), tablet/landscape left-right split

---

## Building From Source

### Option A — GitHub Actions (Recommended)
1. Push a tag (e.g. `v1.0.0`) or go to the **Actions** tab → **Build & Release M3-Redesign** → **Run workflow**.
2. GitHub Actions automatically fetches `MorpheApp/morphe-patches`, overlays this clean-room add-on, compiles the standalone `.mpp` + `patches-list.json`, audits the bundle, and publishes a GitHub Release.

### Option B — Local Build (Linux / macOS)
Requirements: **JDK 17**, **Android SDK** (`ANDROID_HOME` set), **Git**, and **Python 3**.

```bash
chmod +x scripts/build-local.sh
./scripts/build-local.sh
```

The standalone bundle and patch list will be generated in `dist/`:
- `dist/M3-Redesign-v1.0.0.mpp`
- `dist/patches-list.json`

---

## Repository Structure

```text
M3-Redesign/
├── .github/workflows/build-and-release.yml   # Automated CI build & GitHub Release workflow
├── build-config/                             # Standalone Gradle & ProGuard overlays
├── extensions/                               # Java runtime extension (M3NavigationFilter & M3NavigationPreference)
├── patches/                                  # Kotlin Morphe patch & bytecode fingerprints
├── scripts/
│   ├── build-local.sh                        # One-command local build script
│   └── audit-mpp.py                          # Bundle manifest & metadata privacy checker
├── LICENSE
└── README.md
```

---

## License & Credits

- Licensed under **GPL-3.0-only** — see [`LICENSE`](LICENSE).
- Built for the [Morphe](https://github.com/MorpheApp/morphe-patches) ecosystem.
