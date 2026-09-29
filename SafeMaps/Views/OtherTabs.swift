import MessageUI
import SwiftUI

// MARK: - Activity

struct ActivityView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        NavigationStack {
            List {
                if app.events.isEmpty {
                    Text("No activity yet").foregroundColor(Palette.secondary)
                }
                ForEach(app.events) { event in
                    HStack(spacing: 12) {
                        Image(systemName: icon(event.kind)).foregroundColor(color(event.kind))
                            .frame(width: 36, height: 36).background(Circle().fill(color(event.kind).opacity(0.12)))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title(event.kind)).font(.body)
                            Text(event.note).font(.subheadline).foregroundColor(Palette.secondary)
                        }
                        Spacer()
                        Text(event.date.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundColor(Palette.secondary)
                    }
                }
            }
            .navigationTitle("Activity")
        }
    }

    private func icon(_ kind: AlertEvent.Kind) -> String {
        switch kind {
        case .sos: return "exclamationmark.triangle.fill"
        case .locationShared: return "location.fill"
        case .rideStarted: return "car.fill"
        case .arrived: return "checkmark.circle.fill"
        }
    }

    private func color(_ kind: AlertEvent.Kind) -> Color { kind == .sos ? Palette.sos : (kind == .arrived ? Palette.routeGreen : Palette.pink) }

    private func title(_ kind: AlertEvent.Kind) -> String {
        switch kind {
        case .sos: return "SOS"
        case .locationShared: return "Location shared"
        case .rideStarted: return "Ride started"
        case .arrived: return "Arrived safely"
        }
    }
}

// MARK: - Contacts

struct ContactsView: View {
    @EnvironmentObject var app: AppState
    @State private var name = ""
    @State private var phone = ""
    @State private var relation = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach($app.contacts) { $contact in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(contact.name)
                                Text("\(contact.relation) · \(contact.phone)").font(.caption).foregroundColor(Palette.secondary)
                            }
                            Spacer()
                            Toggle("Alerts", isOn: $contact.alertsEnabled).labelsHidden().tint(Palette.pink)
                        }
                    }
                    .onDelete { app.contacts.remove(atOffsets: $0) }
                } header: {
                    Text("Emergency contacts")
                } footer: {
                    Text("Contacts with alerts on get your SOS text and can follow rides on the live tracker. iPhone asks you to tap Send on each SOS text — apps can't text silently.")
                }

                Section("Add a contact") {
                    TextField("Name", text: $name)
                    TextField("Phone (with country code)", text: $phone).keyboardType(.phonePad)
                    TextField("Relation (optional)", text: $relation)
                    Button("Add contact") {
                        app.addContact(name: name.trimmingCharacters(in: .whitespaces), phone: phone.trimmingCharacters(in: .whitespaces), relation: relation)
                        name = ""; phone = ""; relation = ""
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || phone.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Contacts")
        }
    }
}

// MARK: - Demo

/// Same ride flow as Home, but the ride is simulated — for showing the app without going anywhere.
struct DemoView: View {
    @StateObject private var model = RideViewModel(realMotion: false)
    @Binding var fullScreen: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.step == .search {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Demo").font(.title2.weight(.semibold))
                    Text("Search a destination and try the route flow — the ride walks itself, no need to go anywhere.")
                        .font(.subheadline).foregroundColor(Palette.secondary)
                }
                .padding([.horizontal, .top], 20)
            }
            RideView(model: model, fullScreen: $fullScreen)
        }
        .background(Palette.page)
    }
}

// MARK: - Fake call

struct FakeCallView: View {
    let onEnd: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer().frame(height: 60)
            Text("Incoming call").foregroundColor(Color(hex: 0xB9B9C0))
            Image(systemName: "person.fill").font(.system(size: 44)).foregroundColor(Color(hex: 0xB9B9C0))
                .frame(width: 100, height: 100).background(Circle().fill(Color(hex: 0x2B2C33)))
            Text("Papa").font(.largeTitle.weight(.semibold)).foregroundColor(.white)
            Text("Mobile").foregroundColor(Color(hex: 0xB9B9C0))
            Spacer()
            HStack {
                callButton("phone.down.fill", Color(hex: 0xE64A4A))
                Spacer()
                callButton("phone.fill", Color(hex: 0x5E9A23))
            }
            .padding(.horizontal, 50)
            .padding(.bottom, 50)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(hex: 0x14151A).ignoresSafeArea())
    }

    private func callButton(_ icon: String, _ color: Color) -> some View {
        Button(action: onEnd) {
            Image(systemName: icon).font(.system(size: 28)).foregroundColor(.white)
                .frame(width: 76, height: 76).background(Circle().fill(color))
        }
    }
}

// MARK: - SMS

/// Apple's Messages sheet, pre-filled — the rider taps Send.
struct MessageComposer: UIViewControllerRepresentable {
    let draft: SmsDraft
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(draft: draft, onDone: onDone) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let vc = MFMessageComposeViewController()
        vc.recipients = draft.recipients
        vc.body = draft.body
        vc.messageComposeDelegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let draft: SmsDraft
        let onDone: () -> Void
        init(draft: SmsDraft, onDone: @escaping () -> Void) { self.draft = draft; self.onDone = onDone }

        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
            draft.onFinish(result)
            onDone()
        }
    }
}
