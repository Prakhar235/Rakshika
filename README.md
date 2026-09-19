# Safe Maps — demo build

A demo-ready Android app (Kotlin + Jetpack Compose) covering the core Safe Maps
flows: hold-to-trigger SOS, a check-in timer, a mock live-location map,
an activity timeline, emergency contacts, and a fake-call decoy screen.

Everything runs on in-memory state (`RakshikaViewModel`) seeded with sample
data — no backend, API keys, or permissions are required to demo it.

## Run it

1. Open the `Rakshika` folder as a project in Android Studio (Koala or newer).
2. Let Gradle sync — it needs internet access the first time to pull
   dependencies (AndroidX, Compose BOM, Navigation Compose).
3. Run on an emulator or device (minSdk 24).

## What's in the demo

- **Home** — online/offline toggle (simulated), hold-2-seconds SOS button
  with an animated progress ring, check-in timer (10/20/30/60 min presets
  that auto-escalate to a "missed check-in" event if not cancelled), and
  quick actions for fake call / share location / contacts.
- **Map** — a drawn (non-Google-Maps) mock of a live-tracking screen with a
  current-location pin, a route line, and a "safe zone" badge — good enough
  to demo the concept without a Maps API key.
- **Activity** — a timeline of SOS triggers, check-ins, and location shares,
  newest first.
- **Contacts** — add/remove emergency contacts (in-memory).
- **Fake call** — a full-screen decoy incoming-call UI to exit uncomfortable
  situations; tapping either button dismisses it.
- **Demo** — two modes, toggled at the top of the tab:
  - **Watch demo** — a narrated walkthrough of SOS Tracking and Safe Route
    recommendation: a scripted beat sequence drives the same map/contact/route
    UI live on-device, narrated by Android's on-device `TextToSpeech` (falls
    back to timed captions if no TTS engine is available). Play, mute, jump to
    any beat, or switch scenes via the tabs at the top.
  - **Try it yourself** — a hands-on "demo ride": search a destination against
    a dummy directory of ~10 places, get two routes back (a faster one flagged
    for poor lighting/reported incidents, and a recommended well-lit one — the
    numbers are generated deterministically per place name, no backend), pick
    one and start. The ride plays out live — a dot walks the route, ETA counts
    down, contacts move from Notified → Seen, and the same hold-to-trigger SOS
    button from Home works mid-ride. Ends on an "arrived safely" screen with a
    "Plan another ride" reset. All state is in-memory and scoped to the tab.

## Emergency alert SMS (real, background)

SMS is the **offline fallback**. When the phone has usable data the alert goes
over Firebase (live tracking); **only when there's no data connection** does
Safe Maps send a background SMS instead — checked per alert via `Connectivity`
(the Home online/offline toggle also forces this path for demoing).

The **Contacts** tab configures the numbers to alert. Toggle **Background SMS
alerts** to grant the `SEND_SMS` permission once; after that Safe Maps sends a
plain-text SMS **in the background** — no app opens, no tap — via `SmsManager`
to every number that has alerts on, when:

- SOS is triggered (Home button or mid-ride) — ride SOS includes an approximate
  location,
- a check-in timer runs out,
- a demo ride starts (destination + route + ETA) and when it ends ("all clear").

Each message carries a **`rakshika://track?k=…&lat=…&lng=…&d=…` deep link** (no
Google Maps). Tapping it opens **RakshikaSaathi** straight onto that location;
RakshikaSaathi also parses the same link out of the received SMS automatically.
See `LIVE_TRACKING.md`.
Numbers persist across restarts (`ContactsStore`, SharedPreferences). If the
permission is off, alerts are skipped and the timeline says so. Code:
`app/src/main/java/com/rakshika/app/alerts/` (`SmsAlerts`, `AlertMessages`,
`Connectivity`, `ContactsStore`). WhatsApp is intentionally not wired —
third-party apps cannot send WhatsApp in the background without the WhatsApp
Cloud API (a backend).

## Wiring this up for a real build

Each mock maps to a real integration you've already got experience with:

| Demo piece | Replace with |
|---|---|
| `RakshikaViewModel` in-memory state | Firestore-backed repository + `WorkManager` for offline sync |
| Online/offline toggle | Real `ConnectivityManager` callback |
| SOS trigger | FCM push when online; background `SmsManager` alert to contacts is already wired (see above) |
| Mock map canvas | Google Maps Compose or OSMDroid, `FusedLocationProviderClient` |
| Check-in timer | `AlarmManager` or a foreground service so it survives app kill |
| Fake call | Trigger via volume-button long-press listener for one-tap access from a locked screen |
| Safe-route scoring (Demo tab) | A directions API (Google Directions/Routes, Mapbox) plus a safety-signal layer — lighting, foot-traffic, and incident data — to actually rank candidate routes |
| Destination search (Demo ride) | Places Autocomplete / geocoding API in place of the fixed 10-place dummy directory |

## Notes

- Mostly a self-contained UI/UX demo, with two real integrations layered on:
  background alert SMS (`SEND_SMS`, see above) and live-location publishing to
  Firebase Realtime Database (`LIVE_TRACKING.md`). No third-party SDKs.
- Package name: `com.rakshika.app`. Rename via Android Studio's refactor tool
  if you want a different namespace before publishing.
# Rakshika
