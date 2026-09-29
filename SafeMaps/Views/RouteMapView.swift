import MapKit
import SwiftUI

/// A risky stretch drawn on the map: a dashed red circle with a label hanging from its bottom edge.
struct MapPatch {
    let center: Coord
    let radius: Double
    let label: String
}

extension Array where Element == RiskPatch {
    var mapPatches: [MapPatch] { map { MapPatch(center: $0.center, radius: $0.radiusMeters, label: $0.kind.rawValue) } }
}

/// Apple Maps view showing one or two routes (the safe one solid, the unsafe one dotted), risk
/// patches, start/end pins and the rider's live position.
struct RouteMapView: UIViewRepresentable {
    var primary: [Coord]
    var primaryColor: UIColor
    var primaryWidth: CGFloat = 7
    var primaryDotted = false
    var secondary: [Coord]? = nil
    var secondaryColor: UIColor = .gray
    var secondaryWidth: CGFloat = 4
    var secondaryDotted = false
    var patches: [MapPatch] = []
    var current: Coord? = nil
    /// Start close in on `current` rather than fitting the whole route — once the ride starts.
    var zoomToCurrentOnStart = false
    /// Keep the camera on `current` as it moves — real navigation.
    var followCurrent = false
    /// Space taken by cards drawn over the map's top and bottom edges.
    var topInset: CGFloat = 0
    var bottomInset: CGFloat = 0

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsCompass = false
        map.pointOfInterestFilter = .includingAll
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let c = context.coordinator

        // Routes: redraw only when the geometry or styling actually changed.
        let routeKey = "\(key(primary))|\(primaryColor)|\(primaryDotted)|\(key(secondary ?? []))|\(secondaryColor)|\(secondaryDotted)"
        if routeKey != c.routeKey {
            c.routeKey = routeKey
            map.removeOverlays(map.overlays.filter { $0 is StyledPolyline })
            map.removeAnnotations(map.annotations.filter { ($0 as? PinAnnotation)?.kind != nil })
            if let secondary, secondary.count > 1 {
                map.addOverlay(StyledPolyline.make(secondary, color: secondaryColor, width: secondaryWidth, dotted: secondaryDotted), level: .aboveRoads)
            }
            if primary.count > 1 {
                map.addOverlay(StyledPolyline.make(primary, color: primaryColor, width: primaryWidth, dotted: primaryDotted), level: .aboveRoads)
                map.addAnnotations([PinAnnotation(kind: .start, primary[0]), PinAnnotation(kind: .end, primary[primary.count - 1])])
            }
        }

        // Risk patches.
        let patchKey = patches.map { "\($0.center.latitude),\($0.center.longitude),\($0.radius)" }.joined(separator: ";")
        let patchesChanged = patchKey != c.patchKey
        if patchesChanged {
            c.patchKey = patchKey
            map.removeOverlays(map.overlays.filter { $0 is MKCircle })
            map.removeAnnotations(map.annotations.filter { $0 is PatchLabel })
            for p in patches {
                map.addOverlay(MKCircle(center: p.center, radius: p.radius), level: .aboveLabels)
                let south = Coord(latitude: p.center.latitude - p.radius / 110_540, longitude: p.center.longitude)
                map.addAnnotation(PatchLabel(south, text: p.label))
            }
        }

        // The rider.
        if let current {
            if c.you == nil {
                let you = YouAnnotation(current)
                c.you = you
                map.addAnnotation(you)
            } else {
                c.you?.coordinate = current
            }
        } else if let you = c.you {
            map.removeAnnotation(you)
            c.you = nil
        }

        // Camera.
        if !c.fitted {
            if zoomToCurrentOnStart, let current {
                DispatchQueue.main.async {
                    map.setRegion(MKCoordinateRegion(center: current, latitudinalMeters: 700, longitudinalMeters: 700), animated: false)
                }
                c.fitted = true
            } else if primary.count > 1 || (secondary?.count ?? 0) > 1 {
                fit(map, animated: false)
                c.fitted = true
            }
        } else if patchesChanged, !patches.isEmpty, !zoomToCurrentOnStart {
            // Patches arrive after the routes — widen the overview so none sit off-screen.
            fit(map, animated: true)
        }
        if followCurrent, let current, c.fitted, current.latitude != c.lastFollowed?.latitude || current.longitude != c.lastFollowed?.longitude {
            c.lastFollowed = current
            map.setCenter(current, animated: true)
        }
    }

    /// Fitting needs the map's real size — before its first layout it is zero and the zoom comes out wrong.
    private func fit(_ map: MKMapView, animated: Bool) {
        guard map.bounds.height > 0 else {
            DispatchQueue.main.async { self.fit(map, animated: animated) }
            return
        }
        var points = primary + (secondary ?? [])
        for p in patches {
            let dLat = p.radius / 110_540, dLng = p.radius / (111_320 * cos(p.center.latitude * .pi / 180))
            points += [Coord(latitude: p.center.latitude + dLat, longitude: p.center.longitude + dLng),
                       Coord(latitude: p.center.latitude - dLat, longitude: p.center.longitude - dLng)]
        }
        guard !points.isEmpty else { return }
        let rect = points.map { MKMapRect(origin: MKMapPoint($0), size: MKMapSize(width: 0, height: 0)) }
            .reduce(MKMapRect.null) { $0.union($1) }
        map.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: topInset + 24, left: 24, bottom: bottomInset + 24, right: 24), animated: animated)
    }

    private func key(_ path: [Coord]) -> String {
        guard let a = path.first, let b = path.last else { return "-" }
        return "\(path.count):\(a.latitude),\(a.longitude):\(b.latitude),\(b.longitude)"
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var routeKey = ""
        var patchKey = ""
        var fitted = false
        var you: YouAnnotation?
        var lastFollowed: Coord?

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let line = overlay as? StyledPolyline {
                let r = MKPolylineRenderer(polyline: line)
                r.strokeColor = line.color
                r.lineWidth = line.width
                r.lineCap = .round
                r.lineJoin = .round
                // Dots: zero-length dashes with round caps.
                if line.dotted { r.lineDashPattern = [0.01, NSNumber(value: Double(line.width) * 1.8)] }
                return r
            }
            if let circle = overlay as? MKCircle {
                let r = MKCircleRenderer(circle: circle)
                r.strokeColor = Palette.routeRedUI
                r.fillColor = Palette.routeRedUI.withAlphaComponent(0.16)
                r.lineWidth = 2.5
                r.lineDashPattern = [8, 5]
                return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            switch annotation {
            case let pin as PinAnnotation:
                let v = MKMarkerAnnotationView(annotation: pin, reuseIdentifier: "pin")
                v.markerTintColor = pin.kind == .end ? UIColor(hex: 0xD8365E) : UIColor(hex: 0xA0275A)
                v.glyphImage = UIImage(systemName: pin.kind == .end ? "flag.fill" : "circle.fill")
                v.displayPriority = .required
                return v
            case let label as PatchLabel:
                let v = MKAnnotationView(annotation: label, reuseIdentifier: nil)
                v.image = Self.pill(label.text)
                v.displayPriority = .required
                v.zPriority = .max
                return v
            case is YouAnnotation:
                let v = MKAnnotationView(annotation: annotation, reuseIdentifier: "you")
                v.image = Self.youDot()
                v.displayPriority = .required
                v.zPriority = .max
                return v
            default:
                return nil
            }
        }

        private static func pill(_ text: String) -> UIImage {
            let font = UIFont.boldSystemFont(ofSize: 11)
            let size = (text as NSString).size(withAttributes: [.font: font])
            let box = CGSize(width: size.width + 16, height: size.height + 8)
            return UIGraphicsImageRenderer(size: box).image { _ in
                let rect = CGRect(origin: .zero, size: box).insetBy(dx: 1, dy: 1)
                let path = UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2)
                UIColor.white.setFill(); path.fill()
                Palette.routeRedUI.setStroke(); path.lineWidth = 1.2; path.stroke()
                (text as NSString).draw(at: CGPoint(x: 8, y: 4), withAttributes: [.font: font, .foregroundColor: Palette.routeRedUI])
            }
        }

        private static func youDot() -> UIImage {
            UIGraphicsImageRenderer(size: CGSize(width: 28, height: 28)).image { ctx in
                let c = ctx.cgContext
                c.setFillColor(UIColor(red: 26 / 255, green: 115 / 255, blue: 232 / 255, alpha: 0.24).cgColor)
                c.fillEllipse(in: CGRect(x: 0, y: 0, width: 28, height: 28))
                c.setFillColor(UIColor.white.cgColor)
                c.fillEllipse(in: CGRect(x: 6, y: 6, width: 16, height: 16))
                c.setFillColor(UIColor(red: 26 / 255, green: 115 / 255, blue: 232 / 255, alpha: 1).cgColor)
                c.fillEllipse(in: CGRect(x: 8, y: 8, width: 12, height: 12))
            }
        }
    }
}

final class StyledPolyline: MKPolyline {
    var color: UIColor = .gray
    var width: CGFloat = 4
    var dotted = false

    static func make(_ coords: [Coord], color: UIColor, width: CGFloat, dotted: Bool) -> StyledPolyline {
        let line = StyledPolyline(coordinates: coords, count: coords.count)
        line.color = color
        line.width = width
        line.dotted = dotted
        return line
    }
}

final class PinAnnotation: NSObject, MKAnnotation {
    enum Kind { case start, end }
    let kind: Kind?
    let coordinate: Coord
    init(kind: Kind, _ coordinate: Coord) { self.kind = kind; self.coordinate = coordinate }
}

final class PatchLabel: NSObject, MKAnnotation {
    let coordinate: Coord
    let text: String
    init(_ coordinate: Coord, text: String) { self.coordinate = coordinate; self.text = text }
}

final class YouAnnotation: NSObject, MKAnnotation {
    @objc dynamic var coordinate: Coord
    init(_ coordinate: Coord) { self.coordinate = coordinate }
}
