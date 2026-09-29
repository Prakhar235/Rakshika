import Combine
import CoreLocation

/// The phone's real position. During a ride it keeps updating in the background too (the blue
/// location pill shows while it does), so tracking doesn't stop when she locks the phone.
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    @Published private(set) var authorization: CLAuthorizationStatus
    @Published private(set) var lastFix: CLLocation?

    private let manager = CLLocationManager()

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 3
    }

    var isAuthorized: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }

    /// Asks for permission if needed, then starts updates.
    func start() {
        if authorization == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else if isAuthorized {
            manager.startUpdatingLocation()
        }
    }

    /// Keeps fixes coming while the app is in the background — only during a ride.
    func setRiding(_ riding: Bool) {
        guard isAuthorized else { return }
        manager.allowsBackgroundLocationUpdates = riding
        manager.showsBackgroundLocationIndicator = riding
        manager.pausesLocationUpdatesAutomatically = !riding
        manager.startUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if isAuthorized { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last, fix.horizontalAccuracy >= 0 else { return }
        lastFix = fix
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("Location: \(error.localizedDescription)")
    }
}
