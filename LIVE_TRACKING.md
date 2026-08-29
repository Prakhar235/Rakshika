# Live location on Firebase (demo)

The **Demo → Try it yourself** ride now publishes the woman's chosen route and
live position to **Firebase Realtime Database** over its REST API, so a second
app can follow along in realtime. No Firebase SDK or `google-services.json` is
needed — just a database URL.

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
