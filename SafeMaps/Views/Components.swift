import SwiftUI

/// [short] fits inside the small score ring on a route card.
enum Verdict {
    case safe, moderate, risky

    init(_ score: Int) { self = score >= 70 ? .safe : (score >= 50 ? .moderate : .risky) }

    var label: String { self == .safe ? "SAFE" : (self == .moderate ? "MODERATE" : "RISKY") }
    var short: String { self == .safe ? "SAFE" : (self == .moderate ? "FAIR" : "RISKY") }
}

func isNightNow() -> Bool {
    let hour = Calendar.current.component(.hour, from: Date())
    return hour >= 19 || hour < 6
}

func clockTime(_ date: Date = Date()) -> String {
    let f = DateFormatter()
    f.dateFormat = "h:mm a"
    return f.string(from: date)
}

/// Circular safety score: the arc fills to score/100, dashed for the unsafe route.
struct ScoreRing: View {
    let score: Int
    let color: Color
    let background: Color
    var dashed = false
    var size: CGFloat = 50
    var stroke: CGFloat = 4
    var caption: String?
    var numberSize: CGFloat = 16

    var body: some View {
        ZStack {
            Circle().stroke(background, lineWidth: stroke)
            Circle()
                .trim(from: 0, to: CGFloat(score.clamped(0, 100)) / 100)
                .stroke(color, style: StrokeStyle(lineWidth: stroke, lineCap: dashed ? .butt : .round,
                                                  dash: dashed ? [stroke * 1.4, stroke * 0.9] : []))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text("\(score)").font(.system(size: numberSize, weight: .bold)).foregroundColor(color)
                if let caption {
                    Text(caption).font(.system(size: 8, weight: .medium)).foregroundColor(Palette.secondary)
                }
            }
        }
        .frame(width: size, height: size)
        .padding(stroke / 2)
    }
}

struct Pill: View {
    let text: String
    let color: Color
    let background: Color
    var filled = false

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(filled ? .white : color)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: 6).fill(filled ? color : background))
    }
}

/// Wraps its children onto new lines like text — for route tags.
struct FlowLayout: Layout {
    var spacing: CGFloat = 5

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxX, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

struct CardBackground: ViewModifier {
    var radius: CGFloat = 18
    func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: radius).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: radius).stroke(Palette.hairline, lineWidth: 0.5))
    }
}

extension View {
    func card(radius: CGFloat = 18) -> some View { modifier(CardBackground(radius: radius)) }
}

struct SheetHandle: View {
    var body: some View {
        Capsule().fill(Palette.hairline).frame(width: 36, height: 4).frame(maxWidth: .infinity)
    }
}

/// Hold-to-trigger SOS: a dark fill sweeps up over 2 seconds; letting go early cancels it.
struct SosHoldButton: View {
    var hint = "Hold 2 sec"
    var height: CGFloat = 72
    let onTrigger: () -> Void

    @State private var progress: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            RoundedRectangle(cornerRadius: 14).fill(Palette.sos)
            GeometryReader { geo in
                Rectangle().fill(Palette.pinkDark)
                    .frame(height: geo.size.height * progress)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            VStack(spacing: 2) {
                Text("SOS").font(.system(size: 20, weight: .bold)).foregroundColor(.white)
                Text(hint).font(.system(size: 10)).foregroundColor(.white.opacity(0.85))
            }
            .frame(maxHeight: .infinity)
        }
        .frame(height: height)
        .shadow(color: Palette.sos.opacity(0.35), radius: 8, y: 3)
        .onLongPressGesture(minimumDuration: 2, pressing: { pressing in
            if pressing {
                withAnimation(.linear(duration: 2)) { progress = 1 }
            } else {
                withAnimation(.easeOut(duration: 0.15)) { progress = 0 }
            }
        }, perform: {
            progress = 0
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            onTrigger()
        })
        .accessibilityLabel("SOS, hold for 2 seconds")
    }
}

struct ActionTile: View {
    let label: String
    let systemImage: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage).font(.system(size: 18))
                Text(label).font(.caption)
            }
            .foregroundColor(enabled ? Palette.text : Palette.secondary)
            .frame(maxWidth: .infinity)
            .frame(height: 72)
            .card(radius: 14)
        }
        .disabled(!enabled)
    }
}
