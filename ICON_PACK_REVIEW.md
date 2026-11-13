# AOSP Perfect Icons integration and device investigation

Reviewed on 2026-11-13 using read-only ADB observations on Pixel 9 Pro.

## Device observations

- Android 17, SDK 37, build `ZP11.261016.008`, security patch 2026-11-05.
- Pixel Launcher: `CANARY`, version code 907.
- Global Icon Pack: `com.richardluo.globalIconPack`, version 0.6.2 (602).
- AOSP Perfect Icons: `com.tsinbei.aospperfecticons`, version 1.0.1 (2), installed.
- LSPosed: v2.2.1 (7941).
- The current LSPosed module log confirms Global Icon Pack loaded in Launcher,
  Settings and SystemUI. Launcher logs report:
  - `No constructor is found on class com.android.launcher3.util.PackageUserKey`
  - `No class com.android.launcher3.icons.IconProvider$IconChangeReceiver is found`
  - `CalendarAndClockHook failed!`
- SystemUI reports `drawWholeIconForTransparentBackgroundInSplashScreen failed!`.
- Launcher, Settings and SystemUI all report `No icon pack is set`.
- Historical LSPosed remote preferences remain in three UUID directories under
  `/data/misc/apexdata`. The most recently modified Global Icon Pack preferences
  (2026-05-31) contain `iconPack=com.tsinbei.aospperfecticons`, `mode=local`,
  and `iconPackScale=1.2`. Older copies reference `com.android.perfect.icon`.

These observations establish hook incompatibilities and an empty effective pack
selection at process initialization. They do not establish which preference
directory the running LSPosed service uses or why its effective selection is
empty. Stored selection is not proof that injected processes receive it.
The user subsequently selected the pack and restarted Launcher, and reported
that icon replacement works. The initial missing selection explains the reported
full loss of icon replacement. The hook errors above remain observations about
specific auxiliary integrations, not evidence that basic replacement is broken.

REA identities: device `ev_40f051c8b05feb871bfbdc3a7b2b4c12ea4f55f6a77f37f94befc9c8bd5cb765`,
Global Icon Pack `ev_ff4d5ad77d1f84dc52187c437e788cc1da4c0ce736af4d956c19f242866fe7b2`,
AOSP Perfect Icons `ev_d3c318ec8952accfe1bebfa89f1c47feee6490fa1ea7e93d0ac6a44bc0084a31`,
Launcher `ev_a15846ffaf4803215684d559178a26bcbe5de8ccb4008f90eb1a1bf0a6425d34`.
Module logs and remote preference facts above were read directly via ADB shell.

## Integration recommendation

Use the installed AOSP Perfect Icons APK as an additional icon source for
PixelXpert's Launcher override feature. Keep the icon pack independently
updatable, and preserve its adaptive drawable background and inset foreground.

Source: https://github.com/TsinbeiLabs/AOSP-Perfect-Icons (GPL-3.0), derived from
https://github.com/elliana-wt/Pixel-Launcher-Icons.
Its Android build generates `assets/icon_pack_index.json` with `components`,
`drawable` and `launcherDrawable`, plus `assets/appfilter.xml`. The adaptive
`launcherDrawable` uses per-icon background colors and a 12dp foreground inset.
PixelXpert's current importer accepts ZIP/MTZ package-named PNG layer pairs;
that importer cannot consume this APK/index directly.

An integration should select an installed icon pack, load its component mappings
and adaptive drawables, match exact activity components before explicit package
fallbacks, and retain per-app originals and cache invalidation. Existing
package-only imported overrides need component-aware lookup for this source.
Reading an installed APK's resources avoids copying icon assets into PixelXpert.
Document both AOSP Perfect Icons and its original artwork source in README when
the integration is implemented.

This would replace Global Icon Pack for Launcher icon overrides only. Settings,
SystemUI, splash screens, shortcuts and dynamic clock/calendar behavior require
their own implementation and validation.

## Concrete implementation plan after selection was restored

Reviewed source commit: `9cdcd24b82485015c05869de9bb5920c2c869dea`.

1. Add one icon-source selector to `LauncherIconsFragment`: existing imported
   layers or the installed AOSP Perfect Icons package. Use package installation
   information to show availability; retain the existing enable switch and
   per-app original-icon exclusions. Selecting a pack is explicit, because
   installation alone does not mean the user wants to apply it.
2. Read the generated `icon_pack_index.json` from the selected APK's assets.
   Each entry's `launcherDrawable` is the adaptive icon, whereas `drawable` is
   the unpadded PNG used for previews. Resolve the former from the pack's
   resources. Keep resource-backed drawable constant states and create an
   independent drawable for each request. Do not rasterize the complete icon
   into the existing PNG-layer importer, which would lose its native layout.
3. Index `ComponentInfo{package/activity}` separately from explicit
   `PackageInfo{package}` rules. Normalize relative activity names with Android's
   `ComponentName` API. Match a component first, then a declared package rule;
   do not broaden every activity mapping to the entire package.
4. Extend `ImportedLauncherIcons`' input handling for `LauncherActivityInfo` as
   well as `ActivityInfo` and `ApplicationInfo`. The current hook only considers
   `PackageItemInfo`/`ApplicationInfo`; `LauncherActivityInfo` is not a subclass
   of either. It also discards activity identity in its package-only lookup.
   Use the requested user and component to preserve profile behavior. Confirm
   the exact live Launcher overloads during implementation.
5. Publish imported and APK-derived overrides through one immutable active
   catalog. Refresh affected packages when the source, exclusion list or pack
   installation changes. Persist the affected package set before applying
   overrides so disabling the feature, switching sources or uninstalling the
   pack can restore Launcher database entries after a process restart.
6. The reviewed AOSP Perfect Icons adaptive XML defines background/foreground,
   but no monochrome layer. Preserve its native drawable for ordinary icons;
   themed-icon rendering needs an explicit decision and visual verification.
   A monochrome mask of the complete opaque icon would produce a solid shape.
7. During device validation, leave only one module responsible for Launcher
   icon replacement. With Global Icon Pack also overriding the same icon path,
   a correct-looking result cannot establish which module supplied it. Test
   mapped/unmapped apps, exact activity aliases, exclusions, pack update/removal,
   feature disable/restart, badges and themed icons.

The installed 1.0.1 APK was subsequently pulled via ADB. Its SHA-256 is
`63de95a509e8305a6128fdecc4082dd2f7aeb211231308ccd0bf54adba055a30`.
Selected extraction of `assets/icon_pack_index.json` confirmed the expected
`launcherDrawable` and `components` fields, including both component and package
mappings. Full extraction on Windows was refused because resource paths collide
under case folding; extracting only this index succeeded. No update is needed
for the index contract. Resource inflation inside Launcher remains a device
validation step.

### Minimal design gate

- Structural: add an installed adaptive-icon source while preserving component
  identity, rather than converting this one pack into package-only PNG pairs.
- Burden: one source choice is necessary; users already control enabling and
  per-app exclusions.
- Determinable: installation, resource IDs, mapped packages and refresh targets
  are derived from Android and the pack's index.
- Gratuitous: reuse the current hook, preferences and cache refresh; add no
  new service, database, network downloader or framework-wide replacement stack.
- Overfitted: scope the first reader to the requested AOSP Perfect Icons index
  contract. Broader third-party icon-pack support is not required by this task.

The smallest integration is an APK-resource reader feeding the existing
Launcher override path. A new import button alone cannot solve this, because
the existing library stores package-only PNG pairs and the pack supplies
component mappings and resource-backed adaptive drawables.

## Implemented integration

- `AospPerfectIconPack` reads the selected installed APK's index and adaptive
  drawables without loading the pack's executable code. Missing monochrome
  layers are generated from its foreground alpha, retaining native color layers.
- `LauncherIconsFragment` exposes an explicit source choice and validates the
  pack before saving it; the existing enable switch and exclusion list apply.
- `ImportedLauncherIcons` now handles `LauncherActivityInfo` and preserves
  component identity, publishing one component/package catalog for either source.
- Restoration targets are saved before publishing pack overrides, including
  newly mapped packages on pack updates, through the existing UID-restricted
  provider. Source switches and package changes reload the catalog and refresh
  affected Launcher entries. Imports select the imported source; clearing restores
  native icons and disables overrides.
- README credits both the maintained pack and original artwork repository.

Debug compilation is verified. The installed PixelXpert app was not replaced,
so live override/cache/themed-icon behavior remains unverified for this build.

### Device acceptance

1. Install the rebuilt PixelXpert using a matching signature, and ensure its
   Launcher scope is enabled. Remove Launcher from Global Icon Pack's scope and
   restart Launcher so only PixelXpert supplies icons.
2. Choose AOSP Perfect Icons in Launcher icon settings and enable overrides.
   Check an exact component mapping (WeChat), an explicit package mapping (for
   example a mapped banking app), and an unmapped app.
3. Exclude a mapped app and confirm its native icon returns; remove the exclusion
   and confirm the pack icon returns. Test home screen and app drawer.
4. Check themed icons and profile badges. Only current-user icons are overridden;
   other-user requests retain native behavior.
5. Switch between an imported library and the APK, then disable overrides and
   restart Launcher. Check that previous overrides do not remain in the cache.
6. Update/remove/reinstall the icon pack while selected and verify refresh and
   native restoration. Inspect LSPosed logs for load or cache refresh failures.
