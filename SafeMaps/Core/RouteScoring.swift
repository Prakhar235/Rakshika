import Foundation

/// Port of the Android `RouteScoring`: plain-language facts from the real routes, compared against
/// each other, with the safety score itself coming from the on-device safety equation.
enum RouteScoring {
    private static let mainRoadSpeedKmh = 25.0
    private static let fallbackWalkKmh = 4.5

    /// Scores both corridors and returns them as safe (higher score) vs fast (the other).
    static func score(_ routes: RoutingResult?, origin: Coord) -> RoutePair {
        var (main, back) = rawScores(routes)

        // The equation scores every corridor that has a real route; a corridor without one keeps
        // the neutral heuristic score, since there is nothing real to measure.
        for corridor in [Corridor.main, .back] {
            guard let route = routes?.route(for: corridor) else { continue }
            let features = RouteFeatures.from(route)
            let score = SafetyEquation.seed.evaluate(features).score
            let reason = "Scored on-device by the built-in seed equation."
            func apply(_ option: inout RouteOption) {
                option.safetyScore = score
                option.scoreReason = reason
                option.features = features
            }
            if corridor == .main { apply(&main) } else { apply(&back) }
        }

        // Ties go to the main road — busier and easier to get help on.
        var safe = back.safetyScore > main.safetyScore ? back : main
        var fast = safe.corridor == .main ? back : main
        safe.recommended = true
        fast.recommended = false

        for corridor in [Corridor.main, .back] {
            let real = routes?.route(for: corridor)
            let minutes = real.map { max(1, Int(($0.durationSeconds / 60).rounded())) }
                ?? fallbackMinutes(corridor, origin: origin)
            if safe.corridor == corridor {
                safe.minutes = minutes; safe.geoPath = real?.points; safe.via = real?.summary
            } else {
                fast.minutes = minutes; fast.geoPath = real?.points; fast.via = real?.summary
            }
        }
        return RoutePair(fast: fast, safe: safe, summary: summary(safe: safe, fast: fast))
    }

    private static func rawScores(_ routes: RoutingResult?) -> (RouteOption, RouteOption) {
        switch (routes?.main, routes?.back) {
        case let (m?, b?): return compareLive(m, b)
        case let (m?, nil): return (solo(.main, m), missing(.back))
        case let (nil, b?): return (missing(.main), solo(.back, b))
        default: return (missing(.main), missing(.back))
        }
    }

    private static func option(_ corridor: Corridor, score: Double, facts: [RouteFact]) -> RouteOption {
        RouteOption(label: corridor.label, minutes: 0, recommended: false,
                    safetyScore: Int(min(max(score, 1), 99).rounded()), corridor: corridor, facts: facts,
                    geoPath: nil, scoreReason: nil, via: nil, features: nil)
    }

    private static func compareLive(_ main: GeoRoute, _ back: GeoRoute) -> (RouteOption, RouteOption) {
        var mainScore = 50.0, backScore = 50.0
        var mainFacts: [RouteFact] = [], backFacts: [RouteFact] = []

        let speedDelta = main.avgSpeedKmh - back.avgSpeedKmh
        if abs(speedDelta) >= 0.5 {
            let pts = min(max(speedDelta * 1.8, -18), 18)
            mainScore += pts; backScore -= pts
            let m = String(format: "%.0f", main.avgSpeedKmh), b = String(format: "%.0f", back.avgSpeedKmh)
            mainFacts.append(RouteFact(text: "\(m) km/h average vs \(b) km/h on the back lane, per Apple Maps' own timing.", positive: pts >= 0))
            backFacts.append(RouteFact(text: "\(b) km/h average vs \(m) km/h on the main road, per Apple Maps' own timing.", positive: pts < 0))
        }

        switch (main.summary, back.summary) {
        case let (m?, nil):
            mainScore += 12; backScore -= 12
            mainFacts.append(RouteFact(text: "Runs along \(m) — a named through-road; the back lane has none.", positive: true))
            backFacts.append(RouteFact(text: "No named road here, while the main road runs along \(m).", positive: false))
        case let (nil, b?):
            mainScore -= 12; backScore += 12
            mainFacts.append(RouteFact(text: "No named road here, while the back lane runs along \(b).", positive: false))
            backFacts.append(RouteFact(text: "Runs along \(b) — a named through-road; the main road has none.", positive: true))
        case let (m?, b?):
            mainFacts.append(RouteFact(text: "Runs along \(m).", positive: true))
            backFacts.append(RouteFact(text: "Runs along \(b).", positive: true))
        default:
            let note = "No major road is named on either path here — this looks like unmapped territory rather than a lit-arterial-vs-back-lane split."
            mainFacts.append(RouteFact(text: note, positive: false))
            backFacts.append(RouteFact(text: note, positive: false))
        }

        let mainTurns = Double(main.stepCount) / max(main.distanceMeters / 1000, 0.1)
        let backTurns = Double(back.stepCount) / max(back.distanceMeters / 1000, 0.1)
        let turnDelta = backTurns - mainTurns
        if abs(turnDelta) >= 0.3 {
            let pts = min(max(turnDelta * 4, -8), 8)
            mainScore += pts; backScore -= pts
            let mk = String(format: "%.1f", main.distanceMeters / 1000), bk = String(format: "%.1f", back.distanceMeters / 1000)
            mainFacts.append(RouteFact(text: "\(main.stepCount) turns over \(mk) km vs \(back.stepCount) over \(bk) km on the back lane.", positive: pts >= 0))
            backFacts.append(RouteFact(text: "\(back.stepCount) turns over \(bk) km vs \(main.stepCount) over \(mk) km on the main road.", positive: pts < 0))
        }
        return (option(.main, score: mainScore, facts: mainFacts), option(.back, score: backScore, facts: backFacts))
    }

    private static func solo(_ corridor: Corridor, _ route: GeoRoute) -> RouteOption {
        var score = 50.0
        var facts: [RouteFact] = []
        let speed = Int(route.avgSpeedKmh.rounded())
        if route.avgSpeedKmh >= mainRoadSpeedKmh {
            score += 20
            facts.append(RouteFact(text: "\(speed) km/h average speed on this route — a through-road pace, above the 25 km/h main-road cutoff.", positive: true))
        } else {
            score -= 8
            facts.append(RouteFact(text: "\(speed) km/h average speed on this route — a slower, likely residential pace.", positive: false))
        }
        if let name = route.summary {
            score += 12
            facts.append(RouteFact(text: "Runs along \(name) — a named through-road.", positive: true))
        } else {
            score -= 10
            facts.append(RouteFact(text: "No major road is named here — likely unnamed lanes or alleys.", positive: false))
        }
        facts.append(RouteFact(text: "Only one real route was found — the other option has no real data to compare against.", positive: false))
        return option(corridor, score: score, facts: facts)
    }

    private static func missing(_ corridor: Corridor) -> RouteOption {
        option(corridor, score: 50, facts: [
            RouteFact(text: "No real route was found for this option — showing the offline demo path, with no real data to score it on.", positive: false)
        ])
    }

    private static func fallbackMinutes(_ corridor: Corridor, origin: Coord) -> Int {
        let meters = Geo.length(MockPaths.path(for: corridor, from: origin))
        return max(1, Int((meters / 1000 / fallbackWalkKmh * 60).rounded()))
    }

    private static func summary(safe: RouteOption, fast: RouteOption) -> String {
        let diff = safe.minutes - fast.minutes
        let eta: String
        switch diff {
        case ...(-1): eta = "and it's \(-diff) min faster"
        case 0: eta = "at the same ETA"
        case 1...3: eta = "for only \(diff) min more"
        default: eta = "though it adds \(diff) min"
        }
        let lead = safe.reasons.first.map { $0.prefix(1).lowercased() + $0.dropFirst() } ?? "it scored higher on the route data"
        return "The \(safe.label.lowercased()) scores \(safe.safetyScore)/100 vs \(fast.safetyScore)/100 — \(lead) — \(eta)."
    }
}
