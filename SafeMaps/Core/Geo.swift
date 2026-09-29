import CoreLocation
import Foundation

typealias Coord = CLLocationCoordinate2D

enum Geo {
    /// Great-circle distance between two points, in meters.
    static func distance(_ a: Coord, _ b: Coord) -> Double {
        let r = 6_371_000.0
        let dLat = (b.latitude - a.latitude) * .pi / 180
        let dLng = (b.longitude - a.longitude) * .pi / 180
        let lat1 = a.latitude * .pi / 180
        let lat2 = b.latitude * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1, sqrt(h)))
    }

    static func length(_ path: [Coord]) -> Double {
        guard path.count > 1 else { return 0 }
        return zip(path, path.dropFirst()).reduce(0) { $0 + distance($1.0, $1.1) }
    }

    static func format(_ meters: Double) -> String {
        meters >= 950 ? String(format: "%.1f km", meters / 1000) : "\(max(0, Int(meters))) m"
    }

    /// The point a fraction `t` (0...1) along `path`, by arc length.
    static func point(on path: [Coord], at t: Double) -> Coord {
        guard path.count > 1 else { return path.first ?? Coord(latitude: 0, longitude: 0) }
        let segs = zip(path, path.dropFirst()).map { distance($0.0, $0.1) }
        var remaining = min(max(t, 0), 1) * segs.reduce(0, +)
        for (i, d) in segs.enumerated() {
            if remaining <= d || i == segs.count - 1 {
                let local = d == 0 ? 0 : min(max(remaining / d, 0), 1)
                let a = path[i], b = path[i + 1]
                return Coord(
                    latitude: a.latitude + (b.latitude - a.latitude) * local,
                    longitude: a.longitude + (b.longitude - a.longitude) * local
                )
            }
            remaining -= d
        }
        return path[path.count - 1]
    }

    /// The tail of `path` from fraction `t` onward — used to re-base a route on the rider's spot when rerouting.
    static func remaining(_ path: [Coord], from t: Double) -> [Coord] {
        guard path.count > 1 else { return path }
        let here = point(on: path, at: t)
        let segs = zip(path, path.dropFirst()).map { distance($0.0, $0.1) }
        var remaining = min(max(t, 0), 1) * segs.reduce(0, +)
        for (i, d) in segs.enumerated() {
            if remaining <= d || i == segs.count - 1 {
                return [here] + path[(i + 1)...]
            }
            remaining -= d
        }
        return [here, path[path.count - 1]]
    }

    /// Where `point` sits relative to `path`: the fraction (by arc length) of the closest point on the
    /// path, and how far `point` is from it in meters — turns a real GPS fix into ride progress.
    static func project(_ point: Coord, onto path: [Coord]) -> (fraction: Double, offBy: Double) {
        guard path.count > 1 else { return (0, path.first.map { distance($0, point) } ?? 0) }
        let kx = 111_320.0 * cos(point.latitude * .pi / 180)
        func xy(_ p: Coord) -> (Double, Double) {
            ((p.longitude - point.longitude) * kx, (p.latitude - point.latitude) * 110_540.0)
        }
        var total = 0.0, bestAlong = 0.0, bestDist = Double.greatestFiniteMagnitude
        for i in 1..<path.count {
            let a = xy(path[i - 1]), b = xy(path[i])
            let dx = b.0 - a.0, dy = b.1 - a.1
            let len = hypot(dx, dy)
            // The fix is the origin here, so project (0,0) onto segment a→b.
            let t = len == 0 ? 0 : min(max((-a.0 * dx - a.1 * dy) / (len * len), 0), 1)
            let d = hypot(a.0 + t * dx, a.1 + t * dy)
            if d < bestDist {
                bestDist = d
                bestAlong = total + t * len
            }
            total += len
        }
        return (total == 0 ? 0 : min(max(bestAlong / total, 0), 1), bestDist)
    }
}

/// The demo's fixed stand-in paths (normalised 0...1 map offsets, y growing south), used when
/// routing finds no real road for a corridor — same shapes as the Android mock map.
enum MockPaths {
    private static let north = 12.9820, south = 12.9620, west = 77.5850, east = 77.6080
    private static let originOffset = (x: 0.18, y: 0.45)
    private static let routeA: [(Double, Double)] = [(0.18, 0.45), (0.35, 0.34), (0.50, 0.30), (0.68, 0.24), (0.85, 0.20)]
    private static let routeB: [(Double, Double)] = [(0.18, 0.45), (0.18, 0.17), (0.85, 0.17), (0.85, 0.20)]

    /// The demo's default "current location" (MG Road, Bengaluru) when the phone's position isn't known.
    static let defaultOrigin = Coord(
        latitude: north - originOffset.y * (north - south),
        longitude: west + originOffset.x * (east - west)
    )

    /// The stand-in path for `corridor`, translated so it starts at `origin`.
    static func path(for corridor: Corridor, from origin: Coord) -> [Coord] {
        let shape = corridor == .back ? routeA : routeB
        let dLat = origin.latitude - defaultOrigin.latitude
        let dLng = origin.longitude - defaultOrigin.longitude
        return shape.map { p in
            Coord(latitude: north - p.1 * (north - south) + dLat, longitude: west + p.0 * (east - west) + dLng)
        }
    }
}
