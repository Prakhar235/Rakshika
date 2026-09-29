import Foundation
import MessageUI

/// App-wide state shared by every tab: emergency contacts, the activity log, the fake call and the
/// pending SOS text.
@MainActor
final class AppState: ObservableObject {
    @Published var contacts: [EmergencyContact] { didSet { save() } }
    @Published private(set) var events: [AlertEvent] = []
    @Published var fakeCallRinging = false
    @Published var smsDraft: SmsDraft?

    private static let key = "contacts_json"
    private static let defaults = [
        EmergencyContact(id: "seed-primary", name: "Primary contact", phone: "+918707803069", relation: "Emergency")
    ]

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode([EmergencyContact].self, from: data) {
            contacts = saved
        } else {
            contacts = Self.defaults
        }
    }

    /// Numbers that should receive an alert right now.
    var recipients: [String] { contacts.filter { $0.alertsEnabled && !$0.phone.isEmpty }.map(\.phone) }
    var alertNames: [String] { contacts.filter { $0.alertsEnabled && !$0.phone.isEmpty }.map(\.name) }

    func addContact(name: String, phone: String, relation: String) {
        contacts.append(EmergencyContact(name: name, phone: phone, relation: relation.isEmpty ? "Contact" : relation))
    }

    func log(_ kind: AlertEvent.Kind, _ note: String) {
        events.insert(AlertEvent(kind: kind, date: Date(), note: note), at: 0)
    }

    /// Opens a pre-filled SOS text to every alert contact and reports, via `outcome`, what actually
    /// happened — never claiming help was texted when it wasn't.
    func sendSos(body: String, outcome: @escaping (String) -> Void) {
        let numbers = recipients
        guard !numbers.isEmpty else {
            outcome("SOS not sent — add an emergency contact in the Contacts tab")
            log(.sos, "SOS not sent · no contacts")
            return
        }
        guard SmsDraft.canSend else {
            outcome("This device can't send texts — call for help directly")
            log(.sos, "SOS not sent · device can't text")
            return
        }
        smsDraft = SmsDraft(recipients: numbers, body: body) { [weak self] result in
            switch result {
            case .sent:
                outcome("SOS sent to \(numbers.count) contact\(numbers.count == 1 ? "" : "s")")
                self?.log(.sos, "SOS texted to \(numbers.count) contact\(numbers.count == 1 ? "" : "s")")
            case .cancelled:
                outcome("SOS not sent — the text was cancelled")
                self?.log(.sos, "SOS text cancelled")
            default:
                outcome("SOS failed to send — call for help directly")
                self?.log(.sos, "SOS text failed")
            }
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(contacts) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}
