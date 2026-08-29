# Live location on Firebase (demo)

The **Demo → Try it yourself** ride now publishes the woman's chosen route and
live position to **Firebase Realtime Database** over its REST API, so a second
app can follow along in realtime. No Firebase SDK or `google-services.json` is
needed — just a database URL.

Two readers:

- **`tracker.html`** (in this repo) — a one-file web page, open it in any browser.
- **RakshikaSaathi** — a full companion Android app in its **own project**,
  the sibling folder `../RakshikaSaathi/`. Shows the live map, a ride-updates
  log, and fires **system notifications** for ride-started / SOS / arrived.
  See its own `README.md`; summary in
  [the RakshikaSaathi section](#rakshikasaathi-companion-app) below.

## 1. Create the database (one time, ~2 min)

1. <https://console.firebase.google.com> → **Add project** (disable Analytics to be quick).
2. **Build → Realtime Database → Create Database** → pick a location → start in **test mode**.
   Confirm the rules are:
   ```json
   { "rules": { ".read": true, ".write": true } }
   ```
   (Test mode = anyone with the URL can read/write. Fine for a throwaway demo, not for production.)
3. Copy the database URL shown at the top of the Data tab, e.g.
   `https://rakshika-demo-default-rtdb.firebaseio.com`.

## 2. Paste the URL in two places

| File | Line |
|---|---|
| `app/src/main/java/com/rakshika/app/live/LiveShareConfig.kt` | `const val DATABASE_URL = "…"` |
| `tracker.html` | `var DATABASE_URL = "…";` |

Nothing else to configure. (Optional: change `NORTH_LAT`/`SOUTH_LAT`/`WEST_LNG`/`EAST_LNG`
in `LiveShareConfig.kt` to move the demo off central Bengaluru.)

## 3. Run the demo

1. Build & run the app (Android Studio, as before).
2. Open **`tracker.html`** in any browser (double-click the file — it needs internet
   for the map tiles and Firebase, but no server).
3. In the app: **Demo** tab → **Try it yourself** → search a place → pick the
   **Safest** or **Faster** route → **Start**.
4. The tracker shows the route line, a moving dot, ETA counting down, and a red
   **SOS** banner if you hold the SOS button mid-ride. Arriving flips it to
   "Arrived safely".

You can also watch the raw data update live in the Firebase console's
**Realtime Database → Data** tab.

## What gets written

Path: `<DATABASE_URL>/liveTrips/demo`

```jsonc
{
  "status": "riding",            // riding | arrived | ended
  "startedAt": 1724930000000,
  "updatedAt": 1724930012000,
  "sos": false,                  // true after the hold-to-trigger SOS
  "sosAt": 0,
  "origin":      { "name": "Hostel", "area": "Sector 5", "lat": 12.9712, "lng": 77.5901 },
  "destination": { "name": "City Central Mall", "area": "Sector 18", "lat": 12.9802, "lng": 77.6067 },
  "route": {
    "label": "Main road", "kind": "safe",   // safe = RAG-recommended, fast = the other one
    "minutes": 11, "safetyScore": 82, "recommended": true,
    "reasons": ["✓ well-lit main road", "✓ steady foot traffic"]
  },
  "polyline": [ { "lat": …, "lng": … }, … ],  // the whole chosen corridor
  "location": {                               // updated ~4×/second during the ride
    "lat": 12.9750, "lng": 77.5980,
    "progress": 0.42,          // 0..1 along the route
    "etaMinutesLeft": 6,
    "updatedAt": 1724930012000
  }
}
```

## How it works without `google-services.json`

`google-services.json` + the Firebase SDK are only needed for Auth, FCM, Analytics,
and the SDK's socket listeners. Reading/writing data just needs URLs:

- **App → Firebase:** `HttpURLConnection` does a `PUT` of the whole trip on start,
  then `PATCH`es (`POST` + `X-HTTP-Method-Override: PATCH`, which Firebase honours)
  for each location fix / SOS / arrival. See `LiveShareRepository.kt`.
- **tracker.html → Firebase:** the Firebase JS SDK in `databaseURL`-only mode with
  a `ref("liveTrips/demo").on("value", …)` listener. (A plain page can't hold a
  Firebase socket from inside Claude's artifact sandbox, so this is a standalone
  file you open directly.)

All writes are best-effort — if the URL is unset or the network fails, the ride
still plays locally and the "Sharing live" chip turns grey / red.

---

## RakshikaSaathi companion app

**RakshikaSaathi** is its own standalone Android Studio project in the sibling
folder `../RakshikaSaathi/` (`com.rakshika.saathi`, minSdk 26) — the guardian's
phone. Open that folder directly in Android Studio. It **only reads** the trip;
it never writes.

### How it tracks in realtime

`FirebaseTripStream` opens the RTDB REST **streaming** endpoint
(`GET <DATABASE_URL>/liveTrips/demo.json` with `Accept: text/event-stream`).
Firebase then pushes `put` / `patch` server-sent events; the class keeps a local
mirror of the node and emits it on every change. A foreground service
(`TrackingService`) holds that stream open so alerts arrive even when the app is
backgrounded, and reconnects automatically on drops.

### What it shows

- **Live map** — the route polyline, start/destination pins and a moving dot,
  drawn on a Canvas (no Google Maps key), auto-fitted to the route bounds.
- **Trip card** — destination, chosen route (safest / faster) + safety score,
  ETA counting down, progress bar, the RAG reasons.
- **Updates log** — newest-first list of derived moments.
- **SOS banner** — full-width red banner while `sos == true`.

### Notifications (`TripRepository` derives, `TrackingService` posts)

| Moment | Channel / priority |
|---|---|
| Ride started → destination, route, ETA | Ride updates · default |
| Halfway there | log only (no notification) |
| **SOS triggered** | SOS alerts · **high**, vibration, `CATEGORY_CALL` |
| Arrived safely | Ride updates · default |
| Ride ended / stream lost | log only |
| Ongoing "tracking…" notice | Live tracking · low, persistent |

### SMS fallback — when Rakshika's phone has no data

If the Rakshika phone has no usable data connection, it can't reach Firebase, so
it sends the alert as a background **SMS** (see Rakshika's
`alerts/AlertMessages.kt`). Each message is:

```
[RKSH] <human text> rakshika://track?k=<TYPE>&lat=<lat>&lng=<lng>&d=<dest>
```

The `rakshika://track` **deep link is RakshikaSaathi's** — no Google Maps link.
RakshikaSaathi handles it two ways:

- **Automatically:** `sms/SmsReceiver` (manifest `SMS_RECEIVED` receiver,
  `RECEIVE_SMS` permission) fires on every inbound text; `sms/SmsWire.parse`
  pulls the deep link out of the body and reads **type** (SOS / RIDE / ARRIVED /
  CHECKIN), **approximate lat/lng** (4-decimal, last fix the phone had), and
  destination. Persisted (`SmsAlertStore`), pushed to `TripRepository.smsFix`,
  logged, and raised as a notification (`SmsNotifier`).
- **On tap:** the same link, opened from the Messages app, launches
  `MainActivity` (`android.intent.action.VIEW`, `scheme=rakshika host=track`) →
  `SmsWire.fromLink` → straight onto the location. (De-duped if it also arrived
  automatically.)

Either way the tracker shows an **"Alert from <name>"** card with the message
and the approximate point as a coarse-accuracy halo (roughly street-block
level), source marked *by SMS* or *from the link*. Needs no data on the Saathi
phone — only cell signal. Verified: parser extracts type + coords + destination
for every `AlertMessages` template.

### Run it

1. Same `DATABASE_URL` is already set in
   `../RakshikaSaathi/app/src/main/java/com/rakshika/saathi/data/Config.kt`
   (keep it in sync with `LiveShareConfig.kt`). `COMPANION_NAME` there is the
   name shown in the UI/alerts ("Priya" by default).
2. Open `../RakshikaSaathi/` as its own project in Android Studio and Run it
   (or `cd ../RakshikaSaathi && ./gradlew :app:installDebug`) — ideally on a
   *second* device/emulator. Grant the notifications **and SMS** permissions
   when asked.
3. **Online path:** start a ride in the Rakshika app. RakshikaSaathi lights up
   within ~1 s: map moves, log fills, notifications fire.
4. **Offline path:** flip Rakshika's Home online/offline toggle to *offline*
   (or turn off its data), then trigger SOS / start a ride. Rakshika sends an
   SMS; RakshikaSaathi shows the "Alert received by SMS" card with the
   approximate location. (On emulators, deliver a test SMS with
   `adb emu sms send <from> "<body>"` or between two running emulators.)

Verified: Firebase path end-to-end against the live database (start → halfway →
SOS → arrived → ended in order); SMS wire-format parser against every message
template. `./gradlew :app:assembleDebug` builds clean in both projects.
