import Foundation

/// One line of the safety equation: `points = clamp(weight * (feature - reference), -cap, +cap)`.
struct EquationTerm {
    let feature: String
    let weight: Double
    let reference: Double
    let cap: Double
    let note: String

    func points(_ value: Double) -> Double { min(max(weight * (value - reference), -cap), cap) }
}

struct TermContribution {
    let term: EquationTerm
    let value: Double
    let points: Double

    /// This input as a 0-100 bar: 50 is neutral and the term's cap either way maps to 0 or 100 —
    /// the bar shows exactly how far it moved the score, not a made-up rating.
    var subScore: Int { Int((50 + 50 * points / term.cap).rounded()).clamped(0, 100) }
}

/// The safety score as data — `score = clamp(base + Σ term points, 1, 99)`, higher is safer. Same
/// built-in seed equation the Android app starts from.
struct SafetyEquation {
    let base: Double
    let terms: [EquationTerm]

    static let seed = SafetyEquation(base: 50, terms: [
        EquationTerm(feature: "avg_speed_kmh", weight: 0.9, reference: 25, cap: 18,
                     note: "faster average pace suggests a busier through-road"),
        EquationTerm(feature: "has_named_road", weight: 22, reference: 0.5, cap: 11,
                     note: "a named road suggests a lit arterial, not an unmapped lane"),
        EquationTerm(feature: "turns_per_km", weight: -2, reference: 3, cap: 8,
                     note: "fewer turns means a more direct, easier-to-be-seen route")
    ])

    func evaluate(_ features: [String: Double]) -> (score: Int, contributions: [TermContribution]) {
        var total = base
        var contributions: [TermContribution] = []
        for term in terms {
            guard let value = features[term.feature] else { continue }
            let points = term.points(value)
            total += points
            contributions.append(TermContribution(term: term, value: value, points: points))
        }
        return (Int(min(max(total, 1), 99).rounded()), contributions)
    }
}

/// Turns a real route into the named measurements the equation reads.
enum RouteFeatures {
    static func from(_ route: GeoRoute, date: Date = Date()) -> [String: Double] {
        let km = max(route.distanceMeters / 1000, 0.1)
        let hour = Calendar.current.component(.hour, from: date)
        return [
            "avg_speed_kmh": round1(route.avgSpeedKmh),
            "turns_per_km": round1(Double(route.stepCount) / km),
            "has_named_road": route.summary != nil ? 1 : 0,
            "distance_km": round1(route.distanceMeters / 1000),
            "duration_min": (route.durationSeconds / 60).rounded(),
            "is_night": (hour >= 19 || hour < 6) ? 1 : 0
        ]
    }

    private static func round1(_ v: Double) -> Double { (v * 10).rounded() / 10 }
}

/// Plain-language labels and formats for each measurement.
enum FeatureCatalog {
    static func label(_ key: String) -> String {
        switch key {
        case "avg_speed_kmh": return "Road pace"
        case "turns_per_km": return "Route directness"
        case "has_named_road": return "Named through-road"
        case "distance_km": return "Route length"
        case "duration_min": return "Trip time"
        case "is_night": return "After dark"
        default: return key
        }
    }

    static func format(_ key: String, _ value: Double) -> String {
        switch key {
        case "avg_speed_kmh": return String(format: "%.0f km/h", value)
        case "turns_per_km": return String(format: "%.1f turns/km", value)
        case "has_named_road", "is_night": return value >= 0.5 ? "Yes" : "No"
        case "distance_km": return String(format: "%.1f km", value)
        case "duration_min": return String(format: "%.0f min", value)
        default: return String(format: "%.2f", value)
        }
    }
}

extension Comparable {
    func clamped(_ low: Self, _ high: Self) -> Self { min(max(self, low), high) }
}
