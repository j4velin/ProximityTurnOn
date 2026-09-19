# ProximityTurnOn

Wakes the screen of a wall-mounted Android tablet when someone steps in front of it.

Built for a single device: a Lenovo Tab M11 (TB330FU) mounted at head height, permanently on
power, running the Home Assistant Companion app as its launcher. The tablet has no proximity
sensor, so presence is detected via the ambient light sensor, optionally confirmed by the front
camera.

## How it works

```
light sensor ──> shadow detected ──> [camera sees a face?] ──> screen on for 30 s
   ~5 Hz          lux drops below      optional, ≤ 3 s
                  moving baseline
```

1. **Light sensor** – `LightSensorService` is a foreground service that listens to
   `Sensor.TYPE_LIGHT` while holding a partial wake lock. It keeps an exponential moving
   average of the lux value (30 s time constant) as the *baseline* and a running estimate of
   the sensor *noise*. A reading counts as a shadow when it drops below

   ```
   baseline − max(baseline × dropPercent, 4 × noise)
   ```

   The baseline is frozen while a shadow is detected, so a person standing still keeps
   counting as present instead of slowly becoming the new normal. Below 5 lx the room is
   considered too dark for shadow detection.

2. **Camera confirmation** (optional) – when enabled, a shadow doesn't wake the screen
   directly. `FaceCheck` opens the front camera via CameraX `ImageAnalysis` (640×480) and runs
   ML Kit face detection on the frames. The screen is woken as soon as a face is found; after
   3 s without one the camera is closed again. Faces must be at least 15 % of the frame width,
   which corresponds to someone ~50 cm away – people passing further back are ignored.

   This adds roughly 1–1.5 s of latency (camera start + first detection) but filters out
   false triggers from clouds or lights being switched, which matter for a tablet mounted
   near windows.

3. **Screen on** – a `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` wake lock is acquired
   for 30 s. Triggers are throttled with a 10 s cooldown.

## Dashboard

The app's only screen is a control/diagnostics dashboard:

- **Service** – start/stop the foreground service.
- **Light Sensor** – current lux, baseline and the resulting trigger level; toggles for
  *wake on shadow* and *beep on shadow* (a short tone at the moment the light sensor alone
  would have woken the screen – useful to feel the lag the camera step adds); a slider for
  the drop percentage (2–50 %).
- **Camera Confirmation** – toggle (asks for the camera permission), face found / no face
  counters, and a *test now* button to check detection range without waiting for a shadow.
- **Screen-off Test** – counts sensor events while the screen was on vs. off. Confirms the
  device does not power the light sensor down with the display (the M11 doesn't).
- **Log** – the last 200 sensor/service events, also written to Logcat under the tag
  `ProximityTurnOn`.

All toggles and the threshold are persisted in DataStore and survive restarts.

## Pausing from Home Assistant

Detection is pointless at night (too dark for the light sensor anyway) and when nobody is
home, and pausing it releases the wake lock and keeps the camera off. The service listens for
two broadcast intents while it is running:

- `de.j4velin.smarthome.proximityturnon.DISABLE`
- `de.j4velin.smarthome.proximityturnon.ENABLE`

The Home Assistant Companion app can send them with a `command_broadcast_intent`
notification, so no network code is needed in this app. The automation below enables
detection only while someone is home and it is "day" — sun above the horizon and after
07:00 on weekdays / 07:30 at the weekend — and re-evaluates on every presence change, at
sunrise/sunset, at the morning times and after a Home Assistant restart (in case a broadcast
was missed). Native conditions only, no templates:

```yaml
alias: Tablet - presence detection
description: >-
  Enable the tablet's presence detection only during the day and while somebody is home.
mode: single
triggers:
  - trigger: state
    entity_id: [person.a, person.b]
    to: home
  - trigger: state
    entity_id: [person.a, person.b]
    from: home
  - trigger: sun
    event: sunset
  - trigger: state
    entity_id: sun.sun
    to: above_horizon
  - trigger: time
    at: "07:00:01"
    weekday: [mon, tue, wed, thu, fri]
  - trigger: time
    at: "07:30:01"
    weekday: [sat, sun]
  - trigger: homeassistant
    event: start
actions:
  - if:
      - condition: or
        conditions:
          - condition: state
            entity_id: person.a
            state: home
          - condition: state
            entity_id: person.b
            state: home
      - condition: state
        entity_id: sun.sun
        state: above_horizon
      - condition: or
        conditions:
          - condition: time
            after: "07:00:00"
            weekday: [mon, tue, wed, thu, fri]
          - condition: time
            after: "07:30:00"
            weekday: [sat, sun]
    then:
      - action: notify.mobile_app_m11
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: de.j4velin.smarthome.proximityturnon
            intent_action: de.j4velin.smarthome.proximityturnon.ENABLE
    else:
      - action: notify.mobile_app_m11
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: de.j4velin.smarthome.proximityturnon
            intent_action: de.j4velin.smarthome.proximityturnon.DISABLE
```

The same switch is on the dashboard's status card. The state is persisted, so after a
service restart it comes back in the state HA last set. Testing from a PC:

```
adb shell am broadcast -a de.j4velin.smarthome.proximityturnon.DISABLE -p de.j4velin.smarthome.proximityturnon
```

## Power and heat

The light sensor path is essentially free: the sensor runs on the sensor hub in the µA range
and the service does a few floating point operations per event. The camera is only powered
during a check (a second or two per trigger), so it doesn't add meaningful heat either.

For comparison, leaving the 11" screen on 24/7 costs about 2–5 W depending on brightness,
i.e. 18–44 kWh per year. The bigger long-term concern for a permanently plugged-in tablet is
the battery sitting at 100 %; the M11 is therefore run with Lenovo's battery protection
feature enabled, which keeps the charge at around 60 %.

## Building

Standard Android Studio project (Kotlin, Jetpack Compose, Material 3, CameraX, ML Kit face
detection bundled). `minSdk` 35.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Reboots and app updates

`BootReceiver` restarts the service after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` if it was
running before (the dashboard switch persists that as `autoStart`).

Android only lets a foreground service use the camera if it was started while the app was
visible, and since Android 15 the "display over other apps" permission counts as visible only
while an overlay window is actually shown. The app therefore shows a 1×1 px transparent,
non-touchable overlay (`Overlay`) before starting the service and keeps it while the service
runs. Grant the permission once via the "Grant" button on the camera card (or
`adb shell appops set de.j4velin.smarthome.proximityturnon SYSTEM_ALERT_WINDOW allow`).
Without it the service still comes back after a reboot, but only the light-sensor path works
until it is started from the dashboard again.

## Known limitations

- Shadow detection needs ambient light. In a dark room nothing triggers; the camera step
  additionally needs enough light to see a face.
- The wake lock flags used to turn the screen on are deprecated. They work on the M11; other
  devices may need an activity with `setTurnScreenOn(true)` instead.
