import SwiftUI

/// "Why this score?" — the chosen route's score, what went into it, its risk patches and facts.
struct RouteSafetyView: View {
    @ObservedObject var model: RideViewModel
    let onBack: () -> Void
    let onStart: () -> Void

    var body: some View {
        if let routes = model.routes, let destination = model.destination, let chosen = model.chosen {
            let other = routes.option(safe: !model.safeSelected)
            let color = Palette.role(model.safeSelected), bg = Palette.roleBg(model.safeSelected)
            let verdict = Verdict(chosen.safetyScore)
            let reason = chosen.scoreReason ?? chosen.reasons.first
            let parts = contributions(chosen)
            let patches = model.patches(for: chosen)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Button(action: onBack) {
                            Image(systemName: "arrow.left").foregroundColor(Palette.text)
                                .frame(width: 34, height: 34).background(Circle().fill(Palette.card))
                                .overlay(Circle().stroke(Palette.hairline, lineWidth: 0.5))
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Route safety").font(.title3.weight(.semibold))
                            Text("\(model.originName) → \(destination.name) · \(clockTime())").font(.caption).foregroundColor(Palette.secondary)
                        }
                    }

                    HStack(spacing: 16) {
                        ScoreRing(score: chosen.safetyScore, color: color, background: bg, dashed: !model.safeSelected,
                                  size: 100, stroke: 9, caption: "out of 100", numberSize: 32)
                        VStack(alignment: .leading, spacing: 6) {
                            Pill(text: verdict == .safe && isNightNow() ? "SAFE AT NIGHT" : verdict.label, color: color, background: bg, filled: true)
                            if let reason { Text(reason).font(.subheadline) }
                            Text("\(title(other, isSafe: !model.safeSelected, counterpart: chosen)) scores \(other.safetyScore)")
                                .font(.caption).foregroundColor(Palette.secondary)
                        }
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).card()

                    if !parts.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("What goes into the score").font(.subheadline.weight(.semibold))
                            ForEach(Array(parts.enumerated()), id: \.offset) { _, c in
                                FactorBar(label: FeatureCatalog.label(c.term.feature),
                                          measured: FeatureCatalog.format(c.term.feature, c.value),
                                          score: c.subScore)
                            }
                            Text("50 is neutral — each bar shows how far that input moved this route's score.")
                                .font(.caption).foregroundColor(Palette.secondary)
                        }
                        .padding(16).card()
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Risk patches on this route").font(.subheadline.weight(.semibold))
                        if model.patchesLoading {
                            HStack { ProgressView().scaleEffect(0.7); Text("Checking OpenStreetMap for unlit and isolated stretches…").font(.caption).foregroundColor(Palette.secondary) }
                        } else if let patches {
                            if patches.isEmpty {
                                Text("No unlit or isolated stretches found in OpenStreetMap along this route.").font(.caption).foregroundColor(Palette.routeGreen)
                            }
                            ForEach(patches) { p in
                                FactRow(positive: false, title: "\(p.kind.rawValue) · \(Geo.format(p.atMeters)) in", text: p.detail)
                            }
                        } else {
                            HStack {
                                Text("Couldn't reach OpenStreetMap's servers to check for unlit or isolated stretches.")
                                    .font(.caption).foregroundColor(Palette.secondary)
                                Button("Retry", action: model.retryPatches).foregroundColor(Palette.routeRed)
                            }
                        }
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).card()

                    let facts = chosen.facts.filter { $0.text != reason }
                    if !facts.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Along the way").font(.subheadline.weight(.semibold))
                            ForEach(facts, id: \.self) { f in FactRow(positive: f.positive, title: nil, text: f.text) }
                        }
                        .padding(16).frame(maxWidth: .infinity, alignment: .leading).card()
                    }

                    Button(action: onStart) {
                        Label(model.safeSelected ? "Start safe route" : "Start \(chosen.label.lowercased()) route", systemImage: "shield.fill")
                            .font(.headline).foregroundColor(.white)
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(RoundedRectangle(cornerRadius: 12).fill(color.opacity(model.isWalkable(chosen) ? 1 : 0.4)))
                    }
                    .disabled(!model.isWalkable(chosen))
                }
                .padding(.horizontal, 20).padding(.vertical, 16)
            }
            .background(Palette.page)
        }
    }
}

private struct FactorBar: View {
    let label: String
    let measured: String
    let score: Int

    var body: some View {
        let color = score >= 50 ? Palette.routeGreen : Palette.routeRed
        VStack(spacing: 6) {
            HStack(alignment: .lastTextBaseline, spacing: 6) {
                Text(label).font(.subheadline)
                Text(measured).font(.caption).foregroundColor(Palette.secondary)
                Spacer()
                Text("\(score)").font(.subheadline.weight(.semibold)).foregroundColor(color)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Palette.hairline)
                    Capsule().fill(color).frame(width: geo.size.width * CGFloat(score) / 100)
                }
            }
            .frame(height: 5)
        }
    }
}

private struct FactRow: View {
    let positive: Bool
    let title: String?
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(positive ? "✓" : "⚠").font(.caption).foregroundColor(Palette.role(positive))
            VStack(alignment: .leading, spacing: 2) {
                if let title { Text(title).font(.caption.weight(.semibold)).foregroundColor(Palette.routeRed) }
                Text(text).font(.caption)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.roleBg(positive)))
    }
}
