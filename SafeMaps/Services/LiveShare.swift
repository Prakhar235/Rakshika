import Foundation

enum LiveShareStatus { case off, connecting, live, error }

/// Publishes the ride to the same Firebase Realtime Database path as the Android app (REST, no SDK),
/// so tracker.html and the RakshikaSaathi app follow an iPhone ride exactly the same way.
@MainActor
final class LiveShare: ObservableObject {
    static let databaseURL = "https://kriger-campus-32cf7-default-rtdb.firebaseio.com"
    static let tripID = "demo"

    @Published private(set) var status: LiveShareStatus = .off

    private var base: String { "\(Self.databaseURL)/liveTrips/\(Self.tripID)" }
    private static var now: Double { (Date().timeIntervalSince1970 * 1000).rounded() }

    func startTrip(origin: Place, destination: Place, route: RouteOption, safeSelected: Bool, path: [Coord], etaMinutes: Int) {
        guard let start = path.first, let end = path.last else { return }
        status = .connecting
        let trip: [String: Any] = [
            "status": "riding",
            "startedAt": Self.now,
            "updatedAt": Self.now,
            "sos": false,
            "origin": ["name": origin.name, "area": origin.area, "lat": start.latitude, "lng": start.longitude],
            "destination": ["name": destination.name, "area": destination.area, "lat": end.latitude, "lng": end.longitude],
            "route": [
                "label": route.label,
                "kind": safeSelected ? "safe" : "fast",
                "minutes": route.minutes,
                "safetyScore": route.safetyScore,
                "recommended": route.recommended,
                "reasons": route.reasons
            ],
            "polyline": path.map { ["lat": $0.latitude, "lng": $0.longitude] },
            "location": ["lat": start.latitude, "lng": start.longitude, "progress": 0.0,
                         "etaMinutesLeft": etaMinutes, "updatedAt": Self.now]
        ]
        send("\(base).json", method: "PUT", body: trip, markLive: true)
    }

    func updateLocation(_ at: Coord, progress: Double, etaMinutesLeft: Int) {
        send("\(base)/location.json", method: "PATCH", body: [
            "lat": at.latitude, "lng": at.longitude, "progress": progress,
            "etaMinutesLeft": etaMinutesLeft, "updatedAt": Self.now
        ])
    }

    func setSos(_ active: Bool) {
        var body: [String: Any] = ["sos": active, "updatedAt": Self.now]
        if active { body["sosAt"] = Self.now }
        send("\(base).json", method: "PATCH", body: body)
    }

    func arrive() {
        send("\(base).json", method: "PATCH", body: ["status": "arrived", "arrivedAt": Self.now, "updatedAt": Self.now])
    }

    func endTrip() {
        status = .off
        send("\(base).json", method: "PATCH", body: ["status": "ended", "updatedAt": Self.now])
    }

    private func send(_ url: String, method: String, body: [String: Any], markLive: Bool = false) {
        guard let url = URL(string: url), let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        var request = URLRequest(url: url, timeoutInterval: 5)
        request.httpMethod = method
        request.httpBody = data
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        Task {
            let code = (try? await URLSession.shared.data(for: request)).flatMap { ($0.1 as? HTTPURLResponse)?.statusCode }
            if let code, (200..<300).contains(code) {
                if markLive { status = .live }
            } else {
                status = .error
                print("LiveShare: \(method) \(url) -> \(code.map(String.init) ?? "failed")")
            }
        }
    }
}
