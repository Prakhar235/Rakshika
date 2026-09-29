import MapKit

/// Real road routing via Apple Maps (`MKDirections`) — no API key needed. As on Android, the service
/// doesn't say which alternative is "the main road", so the fastest-paced alternative is treated as
/// the main road and the slowest as the back lane.
enum Routing {
    private static let mainRoadSpeedKmh = 25.0

    static func findRoutes(from origin: Coord, to destination: Coord) async -> RoutingResult? {
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: origin))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destination))
        request.transportType = .automobile
        request.requestsAlternateRoutes = true

        guard let response = try? await MKDirections(request: request).calculate() else {
            print("Routing: no route from \(origin) to \(destination)")
            return nil
        }
        let routes = response.routes.compactMap(geoRoute)
        guard !routes.isEmpty else { return nil }

        if routes.count == 1, let only = routes.first {
            print("Routing: 1 route found, \(Int(only.distanceMeters)) m / \(Int(only.avgSpeedKmh)) km/h avg")
            return only.avgSpeedKmh >= mainRoadSpeedKmh ? RoutingResult(main: only, back: nil) : RoutingResult(main: nil, back: only)
        }
        let sorted = routes.sorted { $0.avgSpeedKmh > $1.avgSpeedKmh }
        print("Routing: \(routes.count) routes found, fastest-paced as main road, slowest as back lane")
        return RoutingResult(main: sorted.first, back: sorted.last)
    }

    private static func geoRoute(_ route: MKRoute) -> GeoRoute? {
        let polyline = route.polyline
        guard polyline.pointCount > 1, route.distance > 0, route.expectedTravelTime > 0 else { return nil }
        var coords = [Coord](repeating: kCLLocationCoordinate2DInvalid, count: polyline.pointCount)
        polyline.getCoordinates(&coords, range: NSRange(location: 0, length: polyline.pointCount))
        let name = route.name.trimmingCharacters(in: .whitespaces)
        return GeoRoute(
            points: coords,
            distanceMeters: route.distance,
            durationSeconds: route.expectedTravelTime,
            summary: name.isEmpty ? nil : name,
            stepCount: route.steps.count
        )
    }
}

/// Place search via Apple Maps, biased to where the rider is.
enum PlaceSearch {
    static func search(_ query: String, near center: Coord) async -> [Place]? {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        request.region = MKCoordinateRegion(center: center, latitudinalMeters: 20_000, longitudinalMeters: 20_000)
        guard let response = try? await MKLocalSearch(request: request).start() else { return nil }
        return response.mapItems.prefix(10).map(place)
    }

    /// Real named places near the rider, shown before she types anything.
    static func nearby(_ center: Coord) async -> [Place]? {
        let request = MKLocalPointsOfInterestRequest(center: center, radius: 1500)
        guard let response = try? await MKLocalSearch(request: request).start() else { return nil }
        return response.mapItems.prefix(6).map(place)
    }

    private static func place(_ item: MKMapItem) -> Place {
        let mark = item.placemark
        let area = [mark.subThoroughfare, mark.thoroughfare, mark.locality]
            .compactMap { $0 }
            .joined(separator: " ")
            .replacingOccurrences(of: "  ", with: " ")
        return Place(
            name: item.name ?? "Place",
            area: area.isEmpty ? (mark.title ?? "") : area,
            latitude: mark.coordinate.latitude,
            longitude: mark.coordinate.longitude
        )
    }
}
