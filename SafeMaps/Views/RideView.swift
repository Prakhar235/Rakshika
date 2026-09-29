import SwiftUI

/// The whole ride flow — search, route choice, score breakdown, the ride itself and arrival. The
/// Home tab uses it with real GPS; the Demo tab with a simulated walk.
struct RideView: View {
    @ObservedObject var model: RideViewModel
    @EnvironmentObject var app: AppState
    /// When set, the search screen shows a hold-to-send SOS under the results.
    var showsSos = false
    /// Told true while a ride is under way, so the tab bar and status bar step aside.
    @Binding var fullScreen: Bool

    @State private var showScoreDetails = UserDefaults.standard.bool(forKey: "autoWhy")
    @State private var confirmEnd = false

    var body: some View {
        Group {
            switch model.step {
            case .search:
                SearchStep(model: model, showsSos: showsSos)
            case .routes:
                if showScoreDetails {
                    RouteSafetyView(model: model, onBack: { showScoreDetails = false }, onStart: { model.startRide(app: app) })
                } else {
                    RoutesStep(model: model, onWhy: { showScoreDetails = true })
                }
            case .riding:
                RidingStep(model: model, onEnd: { confirmEnd = true })
            case .arrived:
                ArrivedStep(model: model)
            }
        }
        .onChange(of: model.step) { step in
            fullScreen = step == .riding
            if step != .routes { showScoreDetails = false }
            // Navigation stays on screen for the whole ride.
            UIApplication.shared.isIdleTimerDisabled = step == .riding
        }
        .onDisappear { fullScreen = false }
        .alert("End this ride?", isPresented: $confirmEnd) {
            Button("Keep going", role: .cancel) {}
            Button("End ride", role: .destructive) { model.newRide() }
        } message: {
            Text("Live sharing stops and your contacts won't see you arrive.")
        }
    }
}

// MARK: - Search

private struct SearchStep: View {
    @ObservedObject var model: RideViewModel
    @EnvironmentObject var app: AppState
    let showsSos: Bool
    @ObservedObject private var location = LocationService.shared
    @State private var sosOutcome: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "location.fill").foregroundColor(Palette.ink)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Current location").font(.caption).foregroundColor(Palette.secondary)
                    Text(originText).font(.subheadline)
                }
                Spacer()
            }
            .padding(12)
            .card(radius: 12)

            HStack {
                Image(systemName: "magnifyingglass").foregroundColor(Palette.secondary)
                TextField("Where are you headed?", text: $model.query)
                    .textInputAutocapitalization(.words)
                    .disableAutocorrection(true)
                if !model.query.isEmpty {
                    Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundColor(Palette.secondary) }
                }
            }
            .padding(14)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.secondary.opacity(0.6), lineWidth: 1))

            Text(caption).font(.caption).foregroundColor(Palette.secondary)

            HStack(spacing: 8) {
                Text(model.query.isEmpty ? "Nearby" : "Results").font(.subheadline.weight(.medium)).foregroundColor(Palette.secondary)
                if model.searching || model.assessing { ProgressView().scaleEffect(0.7) }
            }

            ScrollView {
                LazyVStack(spacing: 8) {
                    if model.suggestions.isEmpty && !model.searching {
                        Text(model.query.isEmpty ? "Looking for places near you…" : "No matches — try another name.")
                            .font(.subheadline).foregroundColor(Palette.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(model.suggestions) { place in
                        Button { model.select(place) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "mappin.and.ellipse").foregroundColor(Palette.secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(place.name).font(.subheadline).foregroundColor(Palette.text)
                                    Text(place.area).font(.caption).foregroundColor(Palette.secondary).lineLimit(1)
                                }
                                Spacer()
                            }
                            .padding(12)
                            .card(radius: 12)
                        }
                        .disabled(model.assessing)
                    }
                }
            }

            if showsSos {
                // Search stays on top; SOS sits under the thumb, always visible whatever the list shows.
                if let sosOutcome {
                    Text(sosOutcome)
                        .font(.footnote.weight(.medium))
                        .foregroundColor(Palette.sos)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                }
                SosHoldButton(hint: "Hold 2 sec to alert your contacts") {
                    let body = AlertMessages.sosHome(location.lastFix?.coordinate)
                    app.sendSos(body: body) { outcome in
                        sosOutcome = outcome
                        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { sosOutcome = nil }
                    }
                }
            }
        }
        .padding(20)
        .background(Palette.page)
    }

    private var originText: String {
        if !model.realMotion { return "\(Place.demoOrigin.name) · \(Place.demoOrigin.area)" }
        if model.usingDeviceLocation { return "Your location" }
        return location.isAuthorized ? "Waiting for GPS…" : "Location unavailable — allow location in Settings"
    }

    private var caption: String {
        if model.usingDeviceLocation { return "Searching near your current location" }
        return model.realMotion ? "Turn on location to search and route from where you are" : "Searching near the demo default location"
    }
}

// MARK: - Routes

private struct RoutesStep: View {
    @ObservedObject var model: RideViewModel
    @EnvironmentObject var app: AppState
    let onWhy: () -> Void

    var body: some View {
        if let routes = model.routes, let destination = model.destination, let chosen = model.chosen {
            let safePath = model.isWalkable(routes.safe) ? model.path(for: routes.safe) : nil
            let fastPath = model.isWalkable(routes.fast) ? model.path(for: routes.fast) : nil
            let patches = (model.patches(for: routes.safe) ?? []) + (model.patches(for: routes.fast) ?? [])

            VStack(spacing: 0) {
                ZStack(alignment: .top) {
                    RouteMapView(
                        primary: (model.safeSelected ? safePath : fastPath) ?? [],
                        primaryColor: Palette.roleUI(model.safeSelected),
                        primaryDotted: !model.safeSelected,
                        secondary: model.safeSelected ? fastPath : safePath,
                        secondaryColor: Palette.roleUI(!model.safeSelected),
                        secondaryWidth: 5,
                        secondaryDotted: model.safeSelected,
                        patches: patches.mapPatches,
                        topInset: 80,
                        bottomInset: 40
                    )

                    TripHeader(from: model.originName, to: "\(destination.name) · \(destination.area)",
                               assessing: model.assessing, onBack: model.backToSearch)
                        .padding(.horizontal, 14)
                        .padding(.top, 8)

                    HStack(spacing: 6) {
                        LegendChip(label: "Safest", color: Palette.routeGreen, dotted: false)
                        LegendChip(label: routes.fast.minutes < routes.safe.minutes ? "Fastest" : "Other", color: Palette.routeRed, dotted: true)
                        PatchLegend(model: model, count: patches.count)
                        Spacer()
                    }
                    .padding(12)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .frame(height: 360)

                VStack(spacing: 10) {
                    SheetHandle()
                    HStack {
                        Text("Choose your route").font(.headline)
                        Spacer()
                        Button("Why this score?", action: onWhy).font(.subheadline.weight(.medium)).foregroundColor(Palette.ink)
                    }
                    ScrollView {
                        VStack(spacing: 10) {
                            RouteCard(model: model, route: routes.safe, isSafe: true, counterpart: routes.fast, selected: model.safeSelected)
                            RouteCard(model: model, route: routes.fast, isSafe: false, counterpart: routes.safe, selected: !model.safeSelected)
                            if !model.safeSelected {
                                Text("You've picked the lower-scoring route. Safe Maps recommends the \(routes.safe.label.lowercased()) (\(routes.safe.safetyScore)/100).")
                                    .font(.caption).foregroundColor(Palette.routeRed)
                            }
                        }
                    }
                    HStack(spacing: 10) {
                        let canStart = model.isWalkable(chosen)
                        Button { model.startRide(app: app) } label: {
                            Label(!canStart ? "No real route found" : (model.safeSelected ? "Start safe route" : "Start \(chosen.label.lowercased()) route"),
                                  systemImage: "shield.fill")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .frame(height: 48)
                                .foregroundColor(.white)
                                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.role(model.safeSelected).opacity(canStart ? 1 : 0.4)))
                        }
                        .disabled(!canStart)
                        ShareLink(item: "I'm heading to \(destination.name) via the \(chosen.label.lowercased()) (\(chosen.minutes) min, Safe Maps safety score \(chosen.safetyScore)/100).") {
                            Image(systemName: "square.and.arrow.up")
                                .frame(width: 48, height: 48)
                                .foregroundColor(Palette.text)
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.hairline))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Palette.card.clipShape(RoundedRectangle(cornerRadius: 22)).ignoresSafeArea(edges: .bottom))
                .offset(y: -14)
                .padding(.bottom, -14)
            }
            .background(Palette.page)
        }
    }
}

private struct TripHeader: View {
    let from: String
    let to: String
    let assessing: Bool
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) { Image(systemName: "arrow.left").foregroundColor(Palette.text).frame(width: 32, height: 32) }
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) { Circle().fill(Palette.ink).frame(width: 7, height: 7); Text(from).font(.subheadline.weight(.medium)).lineLimit(1) }
                Divider()
                HStack(spacing: 8) { Rectangle().fill(Palette.text).frame(width: 7, height: 7); Text(to).font(.subheadline.weight(.medium)).lineLimit(1) }
            }
            VStack(alignment: .trailing, spacing: 6) {
                if assessing { ProgressView().scaleEffect(0.7) } else { Pill(text: clockTime(), color: Palette.ink, background: Palette.inkBg) }
                if isNightNow() { Text("Night mode").font(.caption2).foregroundColor(Palette.secondary) }
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.card).shadow(color: .black.opacity(0.12), radius: 6, y: 2))
    }
}

private struct LegendChip: View {
    let label: String
    let color: Color
    let dotted: Bool

    var body: some View {
        HStack(spacing: 5) {
            Path { p in p.move(to: CGPoint(x: 0, y: 2)); p.addLine(to: CGPoint(x: 16, y: 2)) }
                .stroke(color, style: StrokeStyle(lineWidth: 4, lineCap: .round, dash: dotted ? [0.1, 7] : []))
                .frame(width: 16, height: 4)
            Text(label).font(.caption)
        }
        .padding(.horizontal, 9).padding(.vertical, 5)
        .background(Capsule().fill(Palette.card))
    }
}

/// Map legend for risk patches: loading, how many, none — or, tappable, that the lookup failed.
private struct PatchLegend: View {
    @ObservedObject var model: RideViewModel
    let count: Int

    var body: some View {
        if let text {
            Button(action: model.retryPatches) {
                HStack(spacing: 5) {
                    if model.patchesLoading {
                        ProgressView().scaleEffect(0.5).frame(width: 11, height: 11)
                    } else {
                        Circle().strokeBorder(Palette.routeRed, style: StrokeStyle(lineWidth: 1.5, dash: [3, 2]))
                            .background(Circle().fill(Palette.routeRed.opacity(0.16)))
                            .frame(width: 11, height: 11)
                    }
                    Text(text).font(.caption).foregroundColor(Palette.text)
                }
                .padding(.horizontal, 9).padding(.vertical, 5)
                .background(Capsule().fill(Palette.card))
            }
            .disabled(!model.patchesFailed)
        }
    }

    private var text: String? {
        if model.patchesLoading { return "Checking risk…" }
        if model.patchesFailed { return "Risk data unavailable · Retry" }
        if model.riskPatches.isEmpty { return nil }
        return count == 0 ? "No risk patches" : "Risk patch (\(count))"
    }
}

private struct RouteCard: View {
    @ObservedObject var model: RideViewModel
    let route: RouteOption
    let isSafe: Bool
    let counterpart: RouteOption
    let selected: Bool

    var body: some View {
        let walkable = model.isWalkable(route)
        let color = Palette.role(isSafe), bg = Palette.roleBg(isSafe)
        let patches = model.patches(for: route) ?? []
        let tags = contributions(route).prefix(3)

        Button { model.selectRoute(safe: isSafe) } label: {
            HStack(spacing: 12) {
                ScoreRing(score: route.safetyScore, color: color, background: bg, dashed: !isSafe, caption: Verdict(route.safetyScore).short)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(title(route, isSafe: isSafe, counterpart: counterpart)).font(.subheadline.weight(.semibold)).foregroundColor(Palette.text)
                        if route.recommended {
                            Pill(text: "RECOMMENDED", color: Palette.routeGreen, background: Palette.routeGreenBg, filled: true)
                        } else if route.minutes < counterpart.minutes {
                            Text("−\(counterpart.minutes - route.minutes) min").font(.caption).foregroundColor(Palette.routeRed)
                        }
                    }
                    if !walkable {
                        Text("No real road route was found here, so this option can't be navigated.")
                            .font(.caption).foregroundColor(Palette.secondary).multilineTextAlignment(.leading)
                    } else {
                        Text("\(route.minutes) min · \(Geo.format(Geo.length(model.path(for: route)))) · via \(route.via ?? route.label)")
                            .font(.caption).foregroundColor(Palette.secondary).lineLimit(1)
                        FlowLayout {
                            ForEach(Array(Dictionary(grouping: patches, by: \.kind).sorted { $0.key.rawValue < $1.key.rawValue }), id: \.key) { kind, list in
                                Pill(text: "⚠ \(kind.rawValue) \(Geo.format(list.reduce(0) { $0 + $1.lengthMeters }))",
                                     color: Palette.routeRed, background: Palette.routeRedBg)
                            }
                            ForEach(Array(tags.enumerated()), id: \.offset) { _, c in
                                let good = c.points >= 0
                                Pill(text: (good ? "" : "⚠ ") + FeatureCatalog.label(c.term.feature) + " " + FeatureCatalog.format(c.term.feature, c.value),
                                     color: good ? Palette.routeGreen : Palette.routeRed,
                                     background: good ? Palette.routeGreenBg : Palette.routeRedBg)
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 16).fill(selected ? bg.opacity(0.55) : Palette.card))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? color : Palette.hairline, lineWidth: selected ? 1.5 : 0.5))
            .opacity(walkable ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!walkable)
    }
}

/// The inputs behind a route's score, biggest effect first.
func contributions(_ route: RouteOption) -> [TermContribution] {
    guard let features = route.features else { return [] }
    return SafetyEquation.seed.evaluate(features).contributions.sorted { abs($0.points) > abs($1.points) }
}

/// "Safest route" for the recommended one; the other is "Fastest route" only if it really is faster.
func title(_ route: RouteOption, isSafe: Bool, counterpart: RouteOption) -> String {
    if isSafe { return "Safest route" }
    return route.minutes < counterpart.minutes ? "Fastest route" : "Other route"
}

// MARK: - Riding

private struct RidingStep: View {
    @ObservedObject var model: RideViewModel
    @ObservedObject private var liveShare: LiveShare
    @EnvironmentObject var app: AppState
    let onEnd: () -> Void
    @State private var dismissedPatches: Set<UUID> = []
    @State private var cautionDismissed = false

    init(model: RideViewModel, onEnd: @escaping () -> Void) {
        self.model = model
        self._liveShare = ObservedObject(wrappedValue: model.live)
        self.onEnd = onEnd
    }

    var body: some View {
        if let destination = model.destination, let chosen = model.chosen {
            let path = model.path(for: chosen)
            let altPath = model.alternative.flatMap { model.isWalkable($0) ? model.path(for: $0) : nil }
            let total = Geo.length(path)
            let walked = total * model.progress
            let remaining = total - walked
            let current = model.liveFix ?? Geo.point(on: path, at: model.progress)
            let patches = model.patches(for: chosen) ?? []
            let altPatches = model.alternative.flatMap { model.patches(for: $0) } ?? []
            let offRoute = (model.offRouteMeters ?? 0) > RideViewModel.offRouteMeters
            let next = patches.first { $0.atMeters + $0.lengthMeters / 2 >= walked && $0.atMeters - $0.lengthMeters / 2 - walked <= 300 && !dismissedPatches.contains($0.id) }

            VStack(spacing: 0) {
                ZStack(alignment: .top) {
                    RouteMapView(
                        primary: path,
                        primaryColor: Palette.roleUI(model.safeSelected),
                        primaryDotted: !model.safeSelected,
                        secondary: altPath,
                        secondaryColor: Palette.roleUI(!model.safeSelected),
                        secondaryDotted: model.safeSelected,
                        patches: (patches + altPatches).mapPatches,
                        current: current,
                        zoomToCurrentOnStart: true,
                        followCurrent: model.realMotion,
                        topInset: 120,
                        bottomInset: 40
                    )
                    .ignoresSafeArea(edges: .top)

                    VStack(spacing: 10) {
                        NavBanner(distance: Geo.format(remaining), destination: destination.name,
                                  detail: bannerDetail(chosen, offRoute: offRoute), color: Palette.role(model.safeSelected))
                        if model.rerouting {
                            HStack { ProgressView().scaleEffect(0.7); Text("Recalculating route from here…").font(.caption) }
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(RoundedRectangle(cornerRadius: 10).fill(Palette.card))
                        }
                        if let next {
                            let ahead = max(0, next.atMeters - next.lengthMeters / 2 - walked)
                            CautionCard(title: ahead <= 0 ? "You're in a caution zone · \(next.kind.rawValue)" : "Caution zone \(Geo.format(ahead)) ahead · \(next.kind.rawValue)",
                                        text: next.detail) { dismissedPatches.insert(next.id) }
                        } else if !model.safeSelected, !cautionDismissed, let alt = model.alternative {
                            CautionCard(title: "You're on the lower-scoring route",
                                        text: "The \(chosen.label.lowercased()) scores \(chosen.safetyScore)/100 vs \(alt.safetyScore) for the \(alt.label.lowercased()).") { cautionDismissed = true }
                        }
                        if model.sosActive {
                            HStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                Text(model.sosMessage ?? "SOS — opening a text to \(model.notifiedNames)…").font(.caption)
                                Spacer()
                            }
                            .foregroundColor(Palette.sos)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.pinkBg))
                        }
                    }
                    .padding(12)
                }

                VStack(spacing: 12) {
                    SheetHandle()
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .lastTextBaseline, spacing: 4) {
                                Text("\(model.etaMinutesLeft) min").font(.title3.bold())
                                Text("· \(Geo.format(remaining))").font(.subheadline).foregroundColor(Palette.secondary)
                            }
                            Text("Arrive \(clockTime(Date().addingTimeInterval(Double(model.etaMinutesLeft) * 60)))")
                                .font(.caption).foregroundColor(Palette.secondary)
                        }
                        Spacer()
                        Label("Safety \(chosen.safetyScore)", systemImage: "shield.fill")
                            .font(.caption.weight(.medium))
                            .foregroundColor(Palette.role(model.safeSelected))
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(Capsule().fill(Palette.roleBg(model.safeSelected)))
                            .overlay(Capsule().stroke(Palette.role(model.safeSelected).opacity(0.4)))
                        Button(action: onEnd) {
                            Image(systemName: "xmark").foregroundColor(Palette.secondary).frame(width: 30, height: 30)
                                .overlay(Circle().stroke(Palette.hairline))
                        }
                        .accessibilityLabel("End ride")
                    }

                    LiveShareRow(model: model, status: liveShare.status)

                    HStack(spacing: 10) {
                        ActionTile(label: "Fake call", systemImage: "phone.fill") { app.fakeCallRinging = true }
                        ActionTile(label: "Reroute", systemImage: "arrow.triangle.2.circlepath", enabled: !model.rerouting) { model.reroute(app: app) }
                        SosHoldButton { model.triggerSos(app: app) }.frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 16)
                .background(Palette.card.clipShape(RoundedRectangle(cornerRadius: 22)).ignoresSafeArea(edges: .bottom))
                .offset(y: -14)
                .padding(.bottom, -14)
            }
            .background(Palette.card)
        }
    }

    private func bannerDetail(_ chosen: RouteOption, offRoute: Bool) -> String {
        if model.realMotion && model.liveFix == nil { return "Waiting for GPS…" }
        if offRoute { return "About \(Geo.format(model.offRouteMeters ?? 0)) off the route — tap Reroute" }
        return (model.safeSelected ? "Safest route" : "Lower-scoring route") + " · via \(chosen.via ?? chosen.label)"
    }
}

private struct NavBanner: View {
    let distance: String
    let destination: String
    let detail: String
    let color: Color

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "location.north.fill").font(.system(size: 24)).foregroundColor(.white)
            VStack(alignment: .leading, spacing: 1) {
                Text(distance).font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                Text("to \(destination)").font(.subheadline.weight(.medium)).foregroundColor(.white).lineLimit(1)
                Text(detail).font(.caption).foregroundColor(.white.opacity(0.85)).lineLimit(1)
            }
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16).fill(color).shadow(color: .black.opacity(0.18), radius: 6, y: 2))
    }
}

private struct CautionCard: View {
    let title: String
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(Palette.routeRed)
                    .frame(width: 28, height: 28)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Palette.routeRedBg))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).foregroundColor(Palette.routeRed)
                    Text(text).font(.caption).foregroundColor(Palette.text).lineLimit(3)
                }
                Spacer(minLength: 0)
            }
            Button("Got it", action: onDismiss)
                .font(.subheadline.weight(.medium))
                .foregroundColor(Palette.text)
                .frame(maxWidth: .infinity).frame(height: 36)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.hairline))
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.card))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.routeRed, lineWidth: 1.5))
    }
}

private struct LiveShareRow: View {
    @ObservedObject var model: RideViewModel
    let status: LiveShareStatus

    var body: some View {
        let contacts: [(String, String)] = model.realMotion
            ? model.alertNames.map { ($0, "can follow live") }
            : [model.contactAmma.map { ("Amma", $0.text) }, model.contactRohan.map { ("Rohan", $0.text) }].compactMap { $0 }
        HStack(spacing: 10) {
            HStack(spacing: -6) {
                ForEach(Array(contacts.prefix(3).enumerated()), id: \.offset) { i, c in
                    Text(String(c.0.prefix(1)))
                        .font(.system(size: 11, weight: .semibold)).foregroundColor(.white)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(i == 0 ? Palette.ink : Palette.pink))
                        .overlay(Circle().stroke(.white, lineWidth: 1.5))
                }
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(statusText).font(.subheadline.weight(.semibold))
                Text(contacts.isEmpty ? "No emergency contacts to alert — add them in Contacts"
                     : contacts.map { "\($0.0) \($0.1)" }.joined(separator: " · "))
                    .font(.caption).foregroundColor(Palette.secondary).lineLimit(1)
            }
            Spacer()
            Circle().fill(dotColor).frame(width: 8, height: 8)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.page))
    }

    private var statusText: String {
        switch status {
        case .live: return "Live location shared"
        case .connecting: return "Connecting live share…"
        case .error: return "Live share unreachable"
        case .off: return "Sharing live (local)"
        }
    }

    private var dotColor: Color {
        switch status {
        case .live: return Palette.pink
        case .connecting: return Palette.amber
        case .error: return Palette.sos
        case .off: return Palette.secondary
        }
    }
}

// MARK: - Arrived

private struct ArrivedStep: View {
    @ObservedObject var model: RideViewModel

    var body: some View {
        VStack(spacing: 10) {
            Spacer()
            Image(systemName: "checkmark.circle.fill").font(.system(size: 38)).foregroundColor(Palette.routeGreen)
                .frame(width: 72, height: 72).background(Circle().fill(Palette.routeGreenBg))
            Text("You've arrived safely").font(.title2.weight(.semibold))
            if let destination = model.destination {
                Text("at \(destination.name) · \(destination.area)").font(.subheadline).foregroundColor(Palette.secondary).multilineTextAlignment(.center)
            }
            Text(model.realMotion && model.alertNames.isEmpty ? "No emergency contacts were set up."
                 : "\(model.notifiedNames) can see you've arrived on the live tracker.")
                .font(.subheadline).foregroundColor(Palette.secondary).multilineTextAlignment(.center)
            Spacer()
            Button(action: model.newRide) {
                Text("Plan another ride").font(.headline).foregroundColor(.white)
                    .frame(maxWidth: .infinity).frame(height: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Palette.pink))
            }
        }
        .padding(24)
        .background(Palette.page)
    }
}
