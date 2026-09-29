import AVFoundation
import Foundation
import MessageUI

/// SMS bodies in the shared "[RKSH]" wire format that RakshikaSaathi parses — identical to Android:
///   [RKSH] <human text> rakshika://track?k=<TYPE>&lat=<lat>&lng=<lng>&d=<dest>
enum AlertMessages {
    private static let marker = "[RKSH]"
    private static let link = "rakshika://track"

    private static func r(_ v: Double) -> String { String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), v) }

    private static func link(_ type: String, _ at: Coord? = nil, dest: String? = nil) -> String {
        var s = "\(link)?k=\(type)"
        if let at { s += "&lat=\(r(at.latitude))&lng=\(r(at.longitude))" }
        if let dest, !dest.isEmpty { s += "&d=" + (dest.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "") }
        return s
    }

    static func sosHome(_ at: Coord?) -> String { "\(marker) SOS - I need help now. Please call me. \(link("SOS", at))" }

    static func sosRide(destination: String, at: Coord) -> String {
        "\(marker) SOS en route to \(destination). \(link("SOS", at, dest: destination))"
    }
}

/// A text for the rider to send. iOS doesn't let apps send SMS silently, so alerts open a
/// pre-filled Messages sheet and she taps Send.
struct SmsDraft: Identifiable {
    let id = UUID()
    let recipients: [String]
    let body: String
    /// Called with what happened, so the screen can say honestly whether help was actually texted.
    let onFinish: (MessageComposeResult) -> Void

    static var canSend: Bool { MFMessageComposeViewController.canSendText() }
}

/// Speaks route updates aloud, like the Android narrator.
final class Narrator {
    private let synth = AVSpeechSynthesizer()

    func say(_ text: String) {
        synth.stopSpeaking(at: .immediate)
        synth.speak(AVSpeechUtterance(string: text))
    }

    func stop() { synth.stopSpeaking(at: .immediate) }
}
