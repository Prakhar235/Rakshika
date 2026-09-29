# Safe Maps — iOS

SwiftUI port of the Android app (iOS 16+, built with Xcode 14.2). Same tabs — Home (real GPS rides),
Activity, Contacts, Demo (simulated ride) — and the same route-safety design: green/solid safe route,
red/dotted unsafe route, OpenStreetMap risk patches, "Why this score?" breakdown, full-screen ride,
hold-to-send SOS.

## Run it

Open `SafeMaps.xcodeproj` in `~/Downloads/iOS Rakshika` (the project file, not the folder) in Xcode, pick an iPhone simulator or your phone, press Run.
To install on a real iPhone, select your Apple ID team under *Signing & Capabilities* first.

In the simulator, set a location with *Features → Location* (or `xcrun simctl location <device> set lat,lng`).
Debug builds accept launch arguments for scripted checks: `-autoDestination "Shoreline Park"`,
`-autoStart YES` (starts the ride) and `-autoWhy YES` (opens the score breakdown).

## Where it differs from Android

| | Android | iOS |
|---|---|---|
| Map, place search, routing | Google Maps + Places + Directions (API key) | Apple Maps / MapKit (no key) |
| SOS text | Sent silently in the background | Opens a pre-filled Messages sheet — iOS doesn't let apps text silently; she taps Send |
| Ride start / arrival texts | Sent silently when offline | Not sent — contacts follow the ride on the live tracker |
| Background tracking | Screen kept on during a ride | Keeps tracking when locked (blue location pill), screen also kept on |
| Safety score | Seed/learned equation + AI refinement and feedback loop | Seed equation only (no AI refinement, rating or Analysis tab yet) |
| Demo tab | Narrated "Watch demo" + "Try it yourself" + Analysis | "Try it yourself" only |

Live sharing writes to the same Firebase path (`liveTrips/demo`), so `tracker.html` and RakshikaSaathi
follow an iPhone ride exactly like an Android one. SOS texts use the same `[RKSH]` format.
