import Foundation

/// A stretch of one route that OpenStreetMap flags as riskier: `center` is its midpoint on the
/// route, `atMeters` how far along the route that is, `lengthMeters` how long it runs.
struct RiskPatch: Identifiable {
    enum Kind: String {
        case dark = "Dark lane"
        case isolated = "Isolated"
    }

    let id = UUID()
    let kind: Kind
    let center: Coord
    let radiusMeters: Double
    let atMeters: Double
    let lengthMeters: Double

    static let isolationRadius = 150.0

    var detail: String {
        switch kind {
        case .dark: return "OpenStreetMap marks \(Geo.format(lengthMeters)) of road here as unlit."
        case .isolated: return "No shops, cafés or help points mapped within \(Int(Self.isolationRadius)) m for \(Geo.format(lengthMeters))."
        }
    }
}

/// Finds risk patches from OpenStreetMap (Overpass): roads tagged `lit=no` (dark) and stretches with
/// no shop / café / police / hospital nearby (isolated). Only what OSM records is flagged — untagged
/// lighting is unknown, not dark — and "isolated" is skipped when OSM has no places along the route
/// at all (that's missing data, not isolation). Same approach as the Android app: the public servers
/// are raced in parallel, the rider's area is prefetched, and results are cached on disk for 14 days.
actor RiskPatchService {
    static let shared = RiskPatchService()

    private struct Area: Codable {
        let bbox: [Double] // south, west, north, east
        let dark: [[[Double]]]
        let pois: [[Double]]
        let at: Double

        func contains(_ s: Double, _ w: Double, _ n: Double, _ e: Double) -> Bool {
            bbox[0] <= s && bbox[1] <= w && bbox[2] >= n && bbox[3] >= e
        }
    }

    private static let endpoints = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    ]
    private static let amenities = "restaurant|cafe|fast_food|pharmacy|bank|atm|fuel|bar|pub|police|hospital|clinic|fire_station"
    private static let grid = 0.01
    private static let prefetchHalf = 0.015
    private static let ttl: TimeInterval = 14 * 24 * 3600
    private static let cacheSize = 12

    private var cache: [Area] = []
    private var loaded = false
    private let file: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("risk_areas.json")
    }()

    /// Fetches the area around the rider ahead of time, so patches are usually ready the moment she
    /// picks a destination — the public servers can be slow or down right when she needs them.
    func prefetch(around c: Coord) async {
        _ = await area(c.latitude - Self.prefetchHalf, c.longitude - Self.prefetchHalf,
                       c.latitude + Self.prefetchHalf, c.longitude + Self.prefetchHalf)
    }

    /// Patches for each route (keyed by corridor). A value is nil when OSM couldn't be reached —
    /// "unknown", as opposed to an empty list ("none found").
    func patches(for routes: [Corridor: [Coord]]) async -> [Corridor: [RiskPatch]?] {
        let usable = routes.filter { $0.value.count > 1 }
        let all = usable.values.flatMap { $0 }
        guard let first = all.first else { return routes.mapValues { _ in nil } }
        let padLat = RiskPatch.isolationRadius / 110_540
        let padLng = RiskPatch.isolationRadius / (111_320 * cos(first.latitude * .pi / 180))
        guard let area = await area(
            all.map(\.latitude).min()! - padLat, all.map(\.longitude).min()! - padLng,
            all.map(\.latitude).max()! + padLat, all.map(\.longitude).max()! + padLng
        ) else { return routes.mapValues { _ in nil } }

        let dark = area.dark.map { $0.map { Coord(latitude: $0[0], longitude: $0[1]) } }
        let pois = area.pois.map { Coord(latitude: $0[0], longitude: $0[1]) }
        return routes.mapValues { $0.count > 1 ? Self.findPatches($0, dark: dark, pois: pois) : nil }
    }

    private func area(_ s0: Double, _ w0: Double, _ n0: Double, _ e0: Double) async -> Area? {
        let s = floor(s0 / Self.grid) * Self.grid, w = floor(w0 / Self.grid) * Self.grid
        let n = ceil(n0 / Self.grid) * Self.grid, e = ceil(e0 / Self.grid) * Self.grid

        loadOnce()
        let now = Date().timeIntervalSince1970
        cache.removeAll { now - $0.at > Self.ttl }
        if let hit = cache.first(where: { $0.contains(s, w, n, e) }) {
            print("RiskPatches: area already on the phone — reusing it")
            return hit
        }

        let box = String(format: "%.4f,%.4f,%.4f,%.4f", s, w, n, e)
        let query = "[out:json][timeout:25][bbox:\(box)];(way[\"highway\"][\"lit\"=\"no\"];" +
            "node[\"amenity\"~\"^(\(Self.amenities))$\"];node[\"shop\"];);out geom;"
        guard let data = await Self.firstAnswer(query),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let elements = json["elements"] as? [[String: Any]]
        else { return nil }

        var dark: [[[Double]]] = [], pois: [[Double]] = []
        for el in elements {
            if el["type"] as? String == "way", let geometry = el["geometry"] as? [[String: Double]] {
                dark.append(geometry.compactMap { p in p["lat"].flatMap { lat in p["lon"].map { [lat, $0] } } })
            } else if el["type"] as? String == "node", let lat = el["lat"] as? Double, let lon = el["lon"] as? Double {
                pois.append([lat, lon])
            }
        }
        let area = Area(bbox: [s, w, n, e], dark: dark, pois: pois, at: now)
        cache.insert(area, at: 0)
        if cache.count > Self.cacheSize { cache.removeLast(cache.count - Self.cacheSize) }
        try? JSONEncoder().encode(cache).write(to: file, options: .atomic)
        return area
    }

    private func loadOnce() {
        guard !loaded else { return }
        loaded = true
        if let data = try? Data(contentsOf: file), let areas = try? JSONDecoder().decode([Area].self, from: data) {
            cache = areas
        }
    }

    /// Sends the query to every server at once; the first real answer wins and the rest are cancelled.
    private static func firstAnswer(_ query: String) async -> Data? {
        let body = "data=" + (query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")
        return await withTaskGroup(of: Data?.self) { group in
            for endpoint in endpoints {
                group.addTask {
                    var request = URLRequest(url: URL(string: endpoint)!, timeoutInterval: 30)
                    request.httpMethod = "POST"
                    request.httpBody = body.data(using: .utf8)
                    request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                    request.setValue("SafeMaps-iOS/0.1 (route safety research)", forHTTPHeaderField: "User-Agent")
                    guard let (data, response) = try? await URLSession.shared.data(for: request),
                          (response as? HTTPURLResponse)?.statusCode == 200 else {
                        print("RiskPatches: \(endpoint) failed")
                        return nil
                    }
                    print("RiskPatches: answered by \(URL(string: endpoint)?.host ?? endpoint)")
                    return data
                }
            }
            for await result in group {
                if let result {
                    group.cancelAll()
                    return result
                }
            }
            return nil
        }
    }

    // MARK: - Finding patches along one route

    private static func findPatches(_ route: [Coord], dark: [[Coord]], pois: [Coord]) -> [RiskPatch] {
        let total = Geo.length(route)
        guard total > 0 else { return [] }
        let step = max(40, total / 400)
        let samples = Array(stride(from: 0.0, through: total, by: step))
        let points = samples.map { Geo.point(on: route, at: $0 / total) }

        let origin = route[0]
        let kx = 111_320.0 * cos(origin.latitude * .pi / 180)
        func xy(_ p: Coord) -> (Double, Double) { ((p.longitude - origin.longitude) * kx, (p.latitude - origin.latitude) * 110_540) }
        let darkSegs = dark.flatMap { way in zip(way, way.dropFirst()).map { (xy($0.0), xy($0.1)) } }
        let poiXY = pois.map(xy)

        let darkFlags = points.map { p -> Bool in
            let q = xy(p)
            return darkSegs.contains { segmentDistance(q, $0.0, $0.1) <= 25 }
        }
        let nearPoi = points.map { p -> Bool in
            let q = xy(p)
            return poiXY.contains { hypot($0.0 - q.0, $0.1 - q.1) <= isolationRadiusValue }
        }
        // No place mapped anywhere along this route means OSM doesn't cover it — not that it's isolated.
        let isolatedFlags = nearPoi.contains(true) ? nearPoi.map { !$0 } : nearPoi.map { _ in false }

        let found = runs(darkFlags, samples, route, total, .dark, minLength: 80) +
            runs(isolatedFlags, samples, route, total, .isolated, minLength: 250)
        return Array(found.sorted { $0.atMeters < $1.atMeters }.prefix(8))
    }

    private static var isolationRadiusValue: Double { RiskPatch.isolationRadius }

    private static func runs(_ flags: [Bool], _ samples: [Double], _ route: [Coord], _ total: Double,
                             _ kind: RiskPatch.Kind, minLength: Double) -> [RiskPatch] {
        var out: [RiskPatch] = []
        var start = -1
        for i in 0...flags.count {
            let on = i < flags.count && flags[i]
            if on && start < 0 { start = i }
            if !on && start >= 0 {
                let from = samples[start], to = samples[i - 1]
                let length = to - from
                if length >= minLength {
                    let mid = (from + to) / 2
                    out.append(RiskPatch(kind: kind, center: Geo.point(on: route, at: mid / total),
                                         radiusMeters: min(max(length / 2 + 30, 50), 250),
                                         atMeters: mid, lengthMeters: length))
                }
                start = -1
            }
        }
        return out
    }

    private static func segmentDistance(_ p: (Double, Double), _ a: (Double, Double), _ b: (Double, Double)) -> Double {
        let dx = b.0 - a.0, dy = b.1 - a.1
        let len2 = dx * dx + dy * dy
        let t = len2 == 0 ? 0 : min(max(((p.0 - a.0) * dx + (p.1 - a.1) * dy) / len2, 0), 1)
        return hypot(p.0 - (a.0 + t * dx), p.1 - (a.1 + t * dy))
    }
}
