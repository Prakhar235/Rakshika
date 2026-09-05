# Rakshika — demo build

A demo-ready Android app (Kotlin + Jetpack Compose) covering the core Rakshika
flows: hold-to-trigger SOS, a check-in timer, a mock live-location map,
an activity timeline, emergency contacts, and a fake-call decoy screen.

Most of it runs on in-memory state (`RakshikaViewModel`) seeded with sample
data — no backend, API keys, or permissions needed. The exception is the Demo
tab's **"Try it yourself"** ride, which uses a real Google Map with real
place search and real routes (see "Real Google Maps setup" below) — that one
needs a Maps API key and a location permission grant.

## Run it

1. Open the `Rakshika` folder as a project in Android Studio (Koala or newer).
2. Let Gradle sync — it needs internet access the first time to pull
   dependencies (AndroidX, Compose BOM, Navigation Compose, Google Maps).
3. Add a Google Maps Platform API key (see "Real Google Maps setup" below) if
   you want the demo ride's search/routing/map to work.
4. Run on an emulator or device (minSdk 24) with Google Play services.

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
  - **Try it yourself** — a hands-on "demo ride" on a **real Google Map**: your
    current location (device GPS, reverse-geocoded) is the start; typing a
    destination hits **Google Places Autocomplete** for real search results;
    picking one fetches **real walking routes** from the **Directions API**.
    The on-device RAG safety engine (unchanged — see below) scores the two
    real route alternatives and recommends one; real ETAs come straight from
    Directions. Pick a route and start — a dot walks the real polyline on the
    map, ETA counts down, contacts move from Notified → Seen, and the same
    hold-to-trigger SOS button from Home works mid-ride, now sending real
    coordinates. Ends on an "arrived safely" screen with a "Plan another ride"
    reset.

### Real Google Maps setup

The demo ride needs a Google Maps Platform API key with **Maps SDK for
Android**, **Places API**, **Directions API**, and **Geocoding API** enabled.

1. Paste it into `local.properties` (gitignored, never committed):
   `MAPS_API_KEY=your-key-here`
2. `app/build.gradle.kts` reads it into `BuildConfig.MAPS_API_KEY` and a
   manifest placeholder — nothing else to wire up.
3. Grant the location permission prompt on the Search step (or it falls back
   to a fixed real coordinate near Sector 75, Noida). Search, routing, and
   rendering are all real; the safety-scoring RAG pipeline over
   lighting/incident notes is still simulated data (see `app/.../rag/`), by
   design — that's the feature being demoed.

**No key yet, or Places/Directions not enabled on the project?** The ride
still looks real either way. `ride/NearbyPlaces.kt` is a small curated
directory of genuine nearby places (Sector 76 Metro Station, Jaypee Wish
Town, Sector 50 Metro Station, …) that destination search falls back to the
moment the live Places call comes back empty, and `geo/FallbackRoutes.kt`
synthesizes two distinct, road-shaped walking routes (a direct one-turn
shortcut vs. a longer arcing "main road") between the real coordinates
whenever Directions doesn't answer — ETAs are computed from that synthesized
path length, not guessed. Both are drop-in replacements: the moment the real
APIs start answering, `RideViewModel` prefers them automatically.

## Emergency alert SMS (real, background)

SMS is the **offline fallback**. When the phone has usable data the alert goes
over Firebase (live tracking); **only when there's no data connection** does
Rakshika send a background SMS instead — checked per alert via `Connectivity`
(the Home online/offline toggle also forces this path for demoing).

The **Contacts** tab configures the numbers to alert. Toggle **Background SMS
alerts** to grant the `SEND_SMS` permission once; after that Rakshika sends a
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
| Mock map canvas (Home tab, Watch demo) | Google Maps Compose or OSMDroid, `FusedLocationProviderClient` — already done for the "Try it yourself" ride, see above |
| Check-in timer | `AlarmManager` or a foreground service so it survives app kill |
| Fake call | Trigger via volume-button long-press listener for one-tap access from a locked screen |
| Safe-route scoring (Demo tab) | Real routes now come from the Directions API (done, see above); the *safety* layer — lighting, foot-traffic, incident data — driving the RAG score is still simulated `SafetyDatasets`, by design |
| Destination search (Demo ride) | Done — Places Autocomplete + Place Details, see above |

## Notes

- Mostly a self-contained UI/UX demo, with real integrations layered on:
  background alert SMS (`SEND_SMS`, see above), live-location publishing to
  Firebase Realtime Database (`LIVE_TRACKING.md`), and Google Maps/Places/
  Directions for the "Try it yourself" ride (see above, needs `MAPS_API_KEY`).
- Package name: `com.rakshika.app`. Rename via Android Studio's refactor tool
  if you want a different namespace before publishing.
# Rakshika
