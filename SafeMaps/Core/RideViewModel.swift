import Combine
import CoreLocation
import Foundation

enum RideStep { case search, routes, riding, arrived }

/// Port of the Android `RideViewModel`. `realMotion` is the Home tab: the ride follows the phone's
/// GPS. Without it (the Demo tab) the rider's dot walks the route on a timer.
@MainActor
final class RideViewModel: ObservableObject {
    let realMotion: Bool
    let live = LiveShare()

    @Published var step: RideStep = .search
    @Published var query = "" { didSet { if query != oldValue { searchChanged() } } }
    @Published private(set) var searchResults: [Place]?
    @Published private(set) var nearby: [Place]?
    @Published private(set) var searching = false
    @Published private(set) var usingDeviceLocation = false

    @Published private(set) var assessing = false
    @Published private(set) var destination: Place?
    @Published private(set) var routes: RoutePair?
    @Published private(set) var safeSelected = true
    @Published private(set) var riskPatches: [Corridor: [RiskPatch]?] = [:]
    @Published private(set) var patchesLoading = false

    @Published private(set) var progress = 0.0
    @Published private(set) var etaMinutesLeft = 0
    @Published private(set) var liveFix: Coord?
    @Published private(set) var offRouteMeters: Double?
    @Published private(set) var rerouting = false
    @Published private(set) var sosActive = false
    @Published var sosMessage: String?
    @Published private(set) var contactAmma: ContactStatus?
    @Published private(set) var contactRohan: ContactStatus?
    @Published private(set) var alertNames: [String] = []

    private let narrator = Narrator()
    private let location = LocationService.shared
    private(set) var origin: Coord = MockPaths.defaultOrigin
    private var fixSubscription: AnyCancellable?
    private var rideFixSubscription: AnyCancellable?
    private var searchTask: Task<Void, Never>?
    private var assessTask: Task<Void, Never>?
    private var rideTask: Task<Void, Never>?
    private var patchesTask: Task<Void, Never>?

    private static let rideDurationSeconds = 120.0
    private static let arrivalRadius = 35.0
    private static let arrivalMaxAccuracy = 50.0
    private static let maxFixAccuracy = 100.0
    static let offRouteMeters = 60.0

    init(realMotion: Bool) {
        self.realMotion = realMotion
        // The first real fix becomes where search and routing start from.
        fixSubscription = location.$lastFix.compactMap { $0 }.first().receive(on: RunLoop.main).sink { [weak self] fix in
            self?.useOrigin(fix.coordinate)
        }
        location.start()
    }

    // MARK: - Search

    var originName: String { realMotion ? "Your location" : Place.demoOrigin.name }
    var originPlace: Place { realMotion ? Place(name: "Your location", area: "") : Place.demoOrigin }

    /// Real places near the rider, or search results. The real app never offers coordinate-less
    /// places — they can't be routed or walked for real.
    var suggestions: [Place] {
        if query.trimmingCharacters(in: .whitespaces).isEmpty { return nearby ?? (realMotion ? [] : Place.demoPlaces) }
        if searching { return [] }
        return searchResults ?? (realMotion ? [] : Place.demoPlaces.filter { $0.name.localizedCaseInsensitiveContains(query) })
    }

    private func useOrigin(_ coord: Coord) {
        origin = coord
        usingDeviceLocation = true
        Task {
            if let places = await PlaceSearch.nearby(coord) { nearby = places }
        }
        // Get the area's risk data in before she picks a destination — the public servers can be slow.
        Task.detached { await RiskPatchService.shared.prefetch(around: coord) }
    }

    private func searchChanged() {
        searchTask?.cancel()
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            searchResults = nil
            searching = false
            return
        }
        searching = true
        searchResults = nil
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            let results = await PlaceSearch.search(text, near: origin)
            guard !Task.isCancelled else { return }
            searchResults = results
            searching = false
        }
    }

    func select(_ place: Place) {
        destination = place
        assessTask?.cancel()
        assessing = true
        assessTask = Task {
            // The real app routes from wherever the phone is now.
            if realMotion, let fix = location.lastFix { origin = fix.coordinate }
            let found: RoutingResult?
            if let target = place.coordinate {
                found = await Routing.findRoutes(from: origin, to: target)
            } else {
                found = nil
            }
            guard !Task.isCancelled else { return }
            let pair = RouteScoring.score(found, origin: origin)
            routes = pair
            // Recommended route by default — unless, in the real app, it has no real road to follow.
            safeSelected = !realMotion || pair.safe.geoPath != nil || pair.fast.geoPath == nil
            assessing = false
            step = .routes
            narrator.say(pair.summary)
            loadPatches(pair)
        }
    }

    #if DEBUG
    /// Debug-only screenshot/test hook: searches `query`, picks the first result and, optionally,
    /// starts the ride — the simulator can't be tapped from scripts. Compiled out of release builds.
    func debugAutoRide(query: String, start: Bool, app: AppState) {
        Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let place = await PlaceSearch.search(query, near: origin)?.first else { return }
            select(place)
            guard start else { return }
            while step != .routes { try? await Task.sleep(nanoseconds: 300_000_000) }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            startRide(app: app)
        }
    }
    #endif

    // MARK: - Route choice

    /// Whether `route` can actually be ridden: the demo may walk the stand-in path, but in the real
    /// app a route without real road geometry doesn't lead anywhere.
    func isWalkable(_ route: RouteOption) -> Bool { !realMotion || route.geoPath != nil }

    func path(for route: RouteOption) -> [Coord] { route.resolvedPath(origin: origin) }

    var chosen: RouteOption? { routes?.option(safe: safeSelected) }
    var alternative: RouteOption? { routes?.option(safe: !safeSelected) }

    func patches(for route: RouteOption) -> [RiskPatch]? { riskPatches[route.corridor] ?? nil }

    /// True once every lookup came back unreachable — the UI offers a retry.
    var patchesFailed: Bool { !patchesLoading && !riskPatches.isEmpty && riskPatches.values.allSatisfy { $0 == nil } }

    func selectRoute(safe: Bool) {
        guard let target = routes?.option(safe: safe), isWalkable(target) else { return }
        safeSelected = safe
        narrator.say("Switched to the \(target.label.lowercased()) route, safety score \(target.safetyScore) out of 100.")
    }

    func retryPatches() {
        guard let routes, !patchesLoading else { return }
        loadPatches(routes)
    }

    private func loadPatches(_ pair: RoutePair) {
        patchesTask?.cancel()
        patchesLoading = true
        riskPatches = [:]
        var wanted: [Corridor: [Coord]] = [:]
        for option in [pair.safe, pair.fast] where isWalkable(option) {
            wanted[option.corridor] = path(for: option)
        }
        patchesTask = Task {
            let found = await RiskPatchService.shared.patches(for: wanted)
            guard !Task.isCancelled else { return }
            riskPatches = found
            patchesLoading = false
        }
    }

    // MARK: - Riding

    func startRide(app: AppState) {
        guard let routes, let destination else { return }
        let route = routes.option(safe: safeSelected)
        guard isWalkable(route) else { return }
        let path = path(for: route)
        if let end = path.last { print("Ride: \(route.label) to \(destination.name), route ends at \(end.latitude),\(end.longitude)") }

        alertNames = realMotion ? app.alertNames : []
        step = .riding
        progress = 0
        etaMinutesLeft = route.minutes
        sosActive = false
        sosMessage = nil
        offRouteMeters = nil
        liveFix = realMotion ? location.lastFix?.coordinate : nil
        // The demo scripts two contacts' read receipts; the real app only knows who it alerted.
        contactAmma = realMotion ? nil : .notified
        contactRohan = realMotion ? nil : .notified

        live.startTrip(origin: originPlace, destination: destination, route: route,
                       safeSelected: safeSelected, path: path, etaMinutes: route.minutes)
        app.log(.rideStarted, "Ride to \(destination.name) on the \(route.label.lowercased()) route")
        narrator.say("Starting your ride on the \(route.label.lowercased()) route to \(destination.name), " +
                     "about \(route.minutes) minutes away. \(notifiedSentence)")
        startMotion(route, path: path, app: app)
    }

    private func startMotion(_ route: RouteOption, path: [Coord], app: AppState) {
        rideTask?.cancel()
        rideFixSubscription = nil
        if realMotion {
            location.setRiding(true)
            // Real ride: nothing moves on screen unless the phone does.
            rideFixSubscription = location.$lastFix.compactMap { $0 }.receive(on: RunLoop.main).sink { [weak self] fix in
                self?.handle(fix, route: route, path: path, app: app)
            }
        } else {
            rideTask = Task {
                let steps = 90
                for i in 0...steps {
                    guard !Task.isCancelled else { return }
                    let t = Double(i) / Double(steps)
                    progress = t
                    etaMinutesLeft = max(0, Int(Double(route.minutes) * (1 - t)))
                    if t >= 0.4, contactAmma == .notified { contactAmma = .seen; contactRohan = .seen }
                    if i % 2 == 0 { live.updateLocation(Geo.point(on: path, at: t), progress: t, etaMinutesLeft: etaMinutesLeft) }
                    try? await Task.sleep(nanoseconds: UInt64(Self.rideDurationSeconds / Double(steps) * 1_000_000_000))
                }
                finish(path: path, at: nil, app: app)
            }
        }
    }

    /// Projects a real GPS fix onto the route for progress, ETA and off-route distance, shares it,
    /// and ends the ride once she is really at the destination.
    private func handle(_ fix: CLLocation, route: RouteOption, path: [Coord], app: AppState) {
        // A coarse fix would yank the marker around — once there's a usable fix, only decent ones move it.
        if fix.horizontalAccuracy > Self.maxFixAccuracy, liveFix != nil { return }
        let here = fix.coordinate
        let (fraction, offBy) = Geo.project(here, onto: path)
        liveFix = here
        progress = fraction
        offRouteMeters = offBy
        etaMinutesLeft = max(0, Int((Double(route.minutes) * (1 - fraction)).rounded(.up)))
        live.updateLocation(here, progress: fraction, etaMinutesLeft: etaMinutesLeft)

        if let end = path.last, Geo.distance(here, end) <= Self.arrivalRadius, fix.horizontalAccuracy <= Self.arrivalMaxAccuracy {
            finish(path: path, at: here, app: app)
        }
    }

    private func finish(path: [Coord], at: Coord?, app: AppState) {
        rideFixSubscription = nil
        rideTask?.cancel()
        location.setRiding(false)
        if let end = at ?? path.last { live.updateLocation(end, progress: 1, etaMinutesLeft: 0) }
        live.arrive()
        if let destination {
            app.log(.arrived, "Arrived at \(destination.name)")
            narrator.say("You have arrived safely at \(destination.name). \(notifiedSentence)")
        }
        progress = 1
        etaMinutesLeft = 0
        step = .arrived
    }

    /// Recalculates from where she is now and resumes on the new path, keeping the scores.
    func reroute(app: AppState) {
        guard let routes, let destination, !rerouting else { return }
        let fromPath = path(for: routes.option(safe: safeSelected))
        let at = progress
        let here = liveFix ?? Geo.point(on: fromPath, at: at)
        rerouting = true
        narrator.say("Recalculating your route from your current location.")
        let start = origin
        Task {
            let found = await destination.coordinate.asyncMap { await Routing.findRoutes(from: here, to: $0) } ?? nil
            func rebased(_ option: RouteOption) -> RouteOption {
                var o = option
                if let real = found?.route(for: option.corridor) {
                    o.minutes = max(1, Int((real.durationSeconds / 60).rounded()))
                    o.geoPath = real.points
                    o.via = real.summary
                } else {
                    o.geoPath = Geo.remaining(option.resolvedPath(origin: start), from: at)
                }
                return o
            }
            let pair = RoutePair(fast: rebased(routes.fast), safe: rebased(routes.safe), summary: routes.summary)
            self.routes = pair
            let route = pair.option(safe: safeSelected)
            let newPath = path(for: route)
            progress = 0
            etaMinutesLeft = route.minutes
            rerouting = false
            live.startTrip(origin: originPlace, destination: destination, route: route,
                           safeSelected: safeSelected, path: newPath, etaMinutes: route.minutes)
            // Patch positions are measured along a route, so the re-based routes need their own.
            loadPatches(pair)
            narrator.say("Rerouted via the \(route.label.lowercased()), about \(route.minutes) minutes to go.")
            startMotion(route, path: newPath, app: app)
        }
    }

    /// SOS mid-ride: flags the live trip and opens a pre-filled text with where she is.
    func triggerSos(app: AppState) {
        guard let route = chosen else { return }
        let here = liveFix ?? Geo.point(on: path(for: route), at: progress)
        live.setSos(true)
        sosActive = true
        if !realMotion { contactRohan = .responding }
        narrator.say("S.O.S. — sending your location to your contacts.")
        app.sendSos(body: AlertMessages.sosRide(destination: destination?.name ?? "my destination", at: here)) { [weak self] outcome in
            self?.sosMessage = outcome
        }
    }

    func backToSearch() { reset(keepQuery: true) }
    func newRide() { reset(keepQuery: false) }

    private func reset(keepQuery: Bool) {
        rideTask?.cancel()
        assessTask?.cancel()
        patchesTask?.cancel()
        rideFixSubscription = nil
        if step == .riding { location.setRiding(false) }
        live.endTrip()
        narrator.stop()
        if !keepQuery { query = "" }
        step = .search
        destination = nil
        routes = nil
        riskPatches = [:]
        patchesLoading = false
        progress = 0
        liveFix = nil
        offRouteMeters = nil
        sosActive = false
        sosMessage = nil
        rerouting = false
        assessing = false
    }

    /// Who is told about this ride: the demo's scripted pair, or the real saved emergency contacts.
    var notifiedNames: String {
        guard realMotion else { return "Amma and Rohan" }
        switch alertNames.count {
        case 0: return "no contacts"
        case 1: return alertNames[0]
        default: return alertNames.dropLast().joined(separator: ", ") + " and " + alertNames.last!
        }
    }

    private var notifiedSentence: String {
        if realMotion && alertNames.isEmpty { return "No emergency contacts are set up." }
        return "\(notifiedNames) can follow your ride live."
    }
}

extension Optional {
    func asyncMap<T>(_ transform: (Wrapped) async -> T) async -> T? {
        guard let value = self else { return nil }
        return await transform(value)
    }
}
