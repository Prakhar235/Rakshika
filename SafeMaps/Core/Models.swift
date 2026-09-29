import CoreLocation
import Foundation

/// A search result or destination. `coordinate` is nil only for the demo's static fallback list.
struct Place: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let area: String
    var latitude: Double?
    var longitude: Double?

    var coordinate: Coord? {
        guard let latitude, let longitude else { return nil }
        return Coord(latitude: latitude, longitude: longitude)
    }

    static func == (a: Place, b: Place) -> Bool { a.id == b.id }
}

extension Place {
    /// The demo's fixed start.
    static let demoOrigin = Place(name: "Hostel", area: "Sector 5")

    /// Offline fallback shown in the demo before live search answers.
    static let demoPlaces: [Place] = [
        Place(name: "MG Road Metro", area: "Sector 14"),
        Place(name: "City Central Mall", area: "Sector 18"),
        Place(name: "Sunrise Apartments", area: "Sector 21"),
        Place(name: "Tech Park · Gate 2", area: "Sector 62"),
        Place(name: "Central Library", area: "Sector 15")
    ]
}

/// The two corridors the app reasons about: the faster-paced "main road" and the slower "back lane".
enum Corridor: String {
    case main, back
    var label: String { self == .main ? "Main road" : "Back lane" }
}

/// One real, road-following route from the routing service.
struct GeoRoute {
    let points: [Coord]
    let distanceMeters: Double
    let durationSeconds: Double
    /// The road name(s) the routing service gave this route; nil when it named none.
    let summary: String?
    let stepCount: Int

    var avgSpeedKmh: Double { durationSeconds <= 0 ? 0 : (distanceMeters / 1000) / (durationSeconds / 3600) }
}

struct RoutingResult {
    var main: GeoRoute?
    var back: GeoRoute?

    func route(for corridor: Corridor) -> GeoRoute? { corridor == .main ? main : back }
}

/// One concrete, checkable fact behind a route's score — numbers read straight off the real route.
struct RouteFact: Hashable {
    let text: String
    let positive: Bool
}

struct RouteOption {
    var label: String
    var minutes: Int
    var recommended: Bool
    var safetyScore: Int
    var corridor: Corridor
    var facts: [RouteFact]
    /// The routing service's real road geometry; nil when none was found for this corridor.
    var geoPath: [Coord]?
    /// The equation's one-line explanation for `safetyScore`.
    var scoreReason: String?
    var via: String?
    /// The measurements the equation scored, for the score breakdown.
    var features: [String: Double]?

    var reasons: [String] { facts.map(\.text) }

    /// The real path, or the demo's stand-in when routing found none.
    func resolvedPath(origin: Coord) -> [Coord] { geoPath ?? MockPaths.path(for: corridor, from: origin) }
}

/// `safe` is the higher-scoring corridor, `fast` the other; `summary` explains the gap in plain words.
struct RoutePair {
    var fast: RouteOption
    var safe: RouteOption
    var summary: String

    func option(safe isSafe: Bool) -> RouteOption { isSafe ? safe : fast }
}

enum ContactStatus {
    case notified, seen, responding

    var text: String {
        switch self {
        case .notified: return "notified"
        case .seen: return "seen"
        case .responding: return "responding"
        }
    }
}

struct EmergencyContact: Codable, Identifiable, Equatable {
    var id: String = UUID().uuidString
    var name: String
    var phone: String
    var relation: String
    /// Include this number when an alert is sent.
    var alertsEnabled: Bool = true
}

struct AlertEvent: Identifiable {
    enum Kind { case sos, locationShared, rideStarted, arrived }

    let id = UUID()
    let kind: Kind
    let date: Date
    let note: String
}
