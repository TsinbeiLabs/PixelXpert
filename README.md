## Tsinbei Pixel Xpert

This is a **personal fork** of [siavash79/PixelXpert](https://github.com/siavash79/PixelXpert), maintained for our own Pixel devices. It is not distributed through any module repository and no support is offered — build and flash it at your own risk.

For the original project introduction, feature list, compatibility chart, installation steps, and release channels, see the [upstream repository](https://github.com/siavash79/PixelXpert).

### Differences from upstream

- Application ID and module renamed to `com.tsinbei.pixelxpert` / `TsinbeiPixelXpert`
- Xposed hooks migrated to the libxposed API (102)
- The app installs as a regular **user app** (like PixelXpert-Next) instead of being mounted as a system priv-app, so it survives OTAs and temporary root loss; existing system-app installs migrate automatically (device-protected preferences are backed up during flashing and restored on the next boot)
- Various fixes and enhancements of our own; see `CanaryChangelog.md` and the git history

### Backported from other Pixel modules

Assorted Pixel tweaks ported from the community (all GPL/MIT compatible):

- Skip face-unlock confirmation, unlimited concurrent QS tiles, background clipboard allow-list, screenshot app-name pill (PillShot), hide launcher search bar, and a custom empty-shade message — from [hxreborn/PixelInjector](https://github.com/hxreborn/PixelInjector), [biometric-bypass](https://github.com/hxreborn/biometric-bypass), and [qs-boundless-tiles](https://github.com/hxreborn/qs-boundless-tiles)
- Three-finger swipe-down screenshot — from [RovianDev/PixelSuite](https://github.com/RovianDev/PixelSuite)

### Backported from Pixel Taskbar Enabler

Taskbar and bubble enhancements ported from [beymans-code/pixel-taskbar-enabler](https://github.com/beymans-code/pixel-taskbar-enabler):

- Whole-taskbar visual scaling (touch targets and app insets follow) with Android 17 support
- Taskbar dock icon count control
- Mobile-style recents while the taskbar is enabled, bottom-aligned grid rows, and recents header icon scaling
- Bottom bubble layout (one-handed bubbles) with a configurable dismiss target position

### Backported from PixelXpert-Next

The following features and fixes were ported from [anirudhgupta109/PixelXpert-Next](https://github.com/anirudhgupta109/PixelXpert-Next/):

- Caffeine QS tile (keep the screen awake, timed or infinite)
- High Brightness Flashlight QS tile and gesture integration, using the Pixel experimental torch API
- Brightness slider below QS tiles / always-visible brightness slider in collapsed (Quick) QS
- Pin wireless ADB to a static port via an internal TCP forwarder
- Easy Unlock through the native `LockPatternUtils` auto-confirm mechanism, with a direct-boot (FBE) safeguard
- Shuffle PIN fix for the Compose-based lock screen bouncer
- Android 17 Compose status bar clock support: clock repositioning (left/center/right), custom clock formats and custom text variables, show-seconds and 24-hour leading-zero handling, plus a new "move clock to bottom row" option for multi-row notification icons
- Carrier text hook fixes (duplicate hooks, renamed SystemUI fields, keyguard root view fallback)
- Android 17 fixes: background camera/torch access from the framework, network traffic freeze and fake spikes, `calculateChargingSpeed` signature change, one-finger QS pulldown on CP3A/CP41, status bar icon hiding, default notification expansion, immediate lock on double-tap-to-sleep, long-press power remapping, and A17 build-ID detection
- Hardening: concurrent-safe modpack list, null-safe class reflection, dialer call-recording/notes TTS suppression

### Build

```
./gradlew build          # or: gradlew.bat build on Windows
./makeMagisk.bat         # packages PixelXpert.zip from MagiskModBase + built APK
```

Requires the Android SDK and a JDK 21+. Release builds additionally need `ReleaseKey.properties` or `PIXELXPERT_*` signing environment variables.

### Credits / Thanks

- [PixelXpert](https://github.com/siavash79/PixelXpert) — brought to you by @siavash79 & @ElTifo
- [anirudhgupta109/PixelXpert-Next](https://github.com/anirudhgupta109/PixelXpert-Next/) by Anirudh Gupta and contributors, for the features and fixes backported into this fork (GPLv3)
- [beymans-code/pixel-taskbar-enabler](https://github.com/beymans-code/pixel-taskbar-enabler) by beyman-dev, for the taskbar scaling, dock icon count, mobile recents, and bubble layout enhancements (GPLv3)
- [hxreborn/PixelInjector](https://github.com/hxreborn/PixelInjector), [biometric-bypass](https://github.com/hxreborn/biometric-bypass) and [qs-boundless-tiles](https://github.com/hxreborn/qs-boundless-tiles) by hxreborn, for the biometric bypass, unlimited QS tiles, background clipboard, PillShot, hide search bar, and empty-shade message tweaks (GPLv3 / MIT)
- [RovianDev/PixelSuite](https://github.com/RovianDev/PixelSuite) by RovianDev, for the three-finger screenshot gesture (GPLv3)
- eXtreames for the original PillShot screenshot app-name stamp
- [chenxiaolong/PixelLight](https://github.com/chenxiaolong/PixelLight) by Andrew Gunnerson, for the High Brightness Flashlight implementation (GPLv3)
- [LineageOS](https://review.lineageos.org/c/LineageOS/android_frameworks_base/+/491217) contributors (Keith Mok, chillger, Michael Bestas, Pranav Vashi; icon by Mayur Varde) for the original Caffeine tile implementation
- DrDisagree (@Mahmud0808) for the original QS brightness slider implementation in Iconify
- Android Team
- @topjohnwu for Magisk
- @rovo89 for Xposed
- Team LSPosed
- [LSPosed/CorePatch](https://github.com/LSPosed/CorePatch) for package-manager signature bypass compatibility research (GPLv2)
- apsun@github for remote-preferences
- @nijel8 for double-tap to wake
- [Juby210/AppInfoFix](https://github.com/Juby210/AppInfoFix) by Juby210 for the app info version-code and user-app enable/disable enhancements (MIT License)
- [theDjay2529/TouchMeNot](https://github.com/theDjay2529/TouchMeNot) by Dhananjaya K for the lockscreen power-menu, sensitive Quick Settings, and ringer-mode protection design (Apache License 2.0)
- [gboard-material-expressive-black](https://github.com/hxreborn/gboard-material-expressive-black) by rafareborn for the AMOLED Material Expressive color-matching design (MIT License)
- [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook) by chenyue404 for the Gboard clipboard history query and compatibility research (GPLv3)
- [NawafCode/KeyFlux](https://github.com/NawafCode/KeyFlux) contributors for Gboard feature-flag, privacy, clipboard, and input enhancement research (MIT License)

**UI design:**

- @Mahmud0808

**Graphic design:**

- JstormZx@Telegram (Icon and Banner)
- RKBDI@Telegram (Icon)
