# Home Dragon (Android)

A realistic dragon in the style of your reference photo (teal back, orange scaled sides, purple-orange tail underside, frilled head with swept-back horns and crest, big membrane wings). Icons are its only ground: it never walks. When it sits on an icon it holds the photo's pose: upright chest, S-curved neck, front paws straight down, haunch tucked, tail curled behind and both wings raised and open so they stay visible. It hops to a neighbouring icon, or takes off and flies a random curved path in any direction and lands on another icon. From a perch or in the air it breathes a blue plasma flame at an icon, leaving smoke and a scorch mark with blue embers (visual only, icons cool down after about 30 seconds).

Large icons are bigger ground. When the icon finder sees a widget or a large folder (anything about 1.5 times wider than a normal icon), the dragon can stand up on it, fold its wings and crawl along the top edge, then sit down again. On normal icons it still never walks: it hops or flies between icons.

The dragon is drawn procedurally in code (spine, scales, IK legs, jointed wings) to match the photo's look and pose. It is not the photo itself.

**Status: source code only. It has not been compiled or run on a device.** Expect to fix a small error or two on the first build.

## Build (GitHub, no Android Studio)

1. Put these files at the top level of a GitHub repo (the `.github/workflows/build-apk.yml` file starts the build on every push).
2. Open the repo's Actions tab, open the latest "Build APK" run (wait about 5 minutes for the green tick).
3. Under Artifacts, download HomeDragon-debug-apk, extract `app-debug.apk`, tap it on the phone and allow "Install unknown apps".

Every build is signed with the same debug key, so a new APK installs over the old one without uninstalling.

## First-run setup (Redmi Note 14 Pro+, HyperOS)

| Step | Where | Why |
|---|---|---|
| 1 | Button 1, allow "Display over other apps" | Draws the dragon above the launcher |
| 2 | Button 2, switch on "Home Dragon icon finder" | Reads icon positions. Without it a manual grid is used |
| 3 | Button 3, battery "No restrictions", plus Autostart on | Keeps the service alive after screen-off |
| 4 | Recent apps, long-press the app card, lock it | Stops HyperOS clearing it from RAM |
| 5 | Start dragon | Starts the foreground service |

Rooted shortcuts (optional, in a root shell):

```
appops set com.ramim.homedragon SYSTEM_ALERT_WINDOW allow
dumpsys deviceidle whitelist +com.ramim.homedragon
```

## How it meets your requirements

| Requirement | How |
|---|---|
| 120 Hz while screen is on | Frames come from Choreographer (display vsync). The overlay window requests the highest refresh mode at the current resolution. Settings, Display, Refresh rate must be on 120 Hz. |
| Pause when screen is off | `ACTION_SCREEN_OFF` removes the frame callback. No timers, no drawing. |
| Instant on wake or unlock | The view, bitmaps and dragon state stay in RAM inside the foreground service. Resume only re-posts the frame callback. It resumes on unlock (or on screen-on if there is no lock screen). |
| Live screen, not a screenshot | Transparent `TYPE_APPLICATION_OVERLAY` window over the real launcher. Touches pass through. |

## Known limits

- Crawling on widgets and big folders depends on the launcher exposing them to the icon finder. If HyperOS hides a widget from accessibility, that widget is not walkable. The manual grid fallback has no big icons.

- Fire is drawn on top of icons. It cannot damage or move real icons.
- Icon positions come from accessibility data. If HyperOS hides some icons from it, those icons get no dragon. Use the grid fallback (turn the icon finder off and set columns and rows).
- The dragon also shows over the recents screen, because the launcher owns that screen too.
- The overlay does not draw above the lock screen.
- The dragon runs at full frame rate even when it is only sitting. An idle frame cap would save battery but conflicts with your 120 Hz request, so it is not included.
- Some HyperOS builds ignore the refresh rate request from overlays. If the demo's fps looks capped at 60, check the display refresh setting first.

## Files

- `DragonModel.kt` the realistic dragon drawing (body, scales, legs, wings, head)
- `DragonView.kt` behaviour (hop, fly, land, fire), blue flame, smoke, sparks, scorch, frame loop
- `DragonService.kt` overlay window, 120 Hz request, screen on/off handling
- `IconFinderService.kt` accessibility service that finds icon rectangles
- `MainActivity.kt` setup screen and size sliders
- `BootReceiver.kt` restarts the dragon after reboot if it was on
