# Rakshika — demo build

A demo-ready Android app (Kotlin + Jetpack Compose) covering the core Rakshika
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

## Wiring this up for a real build

Each mock maps to a real integration you've already got experience with:

| Demo piece | Replace with |
|---|---|
| `RakshikaViewModel` in-memory state | Firestore-backed repository + `WorkManager` for offline sync |
| Online/offline toggle | Real `ConnectivityManager` callback |
| SOS trigger | FCM push when online; `SmsManager` fallback when offline |
| Mock map canvas | Google Maps Compose or OSMDroid, `FusedLocationProviderClient` |
| Check-in timer | `AlarmManager` or a foreground service so it survives app kill |
| Fake call | Trigger via volume-button long-press listener for one-tap access from a locked screen |
| Safe-route scoring (Demo tab) | A directions API (Google Directions/Routes, Mapbox) plus a safety-signal layer — lighting, foot-traffic, and incident data — to actually rank candidate routes |
| Destination search (Demo ride) | Places Autocomplete / geocoding API in place of the fixed 10-place dummy directory |

## Notes

- No network calls, permissions, or third-party SDKs are wired in — this is
  intentionally a self-contained UI/UX demo, not a functional safety app yet.
- Package name: `com.rakshika.app`. Rename via Android Studio's refactor tool
  if you want a different namespace before publishing.
# Rakshika
