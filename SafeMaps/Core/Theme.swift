import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

extension Color {
    init(hex: UInt32) { self.init(UIColor(hex: hex)) }
}

/// Same palette as the Android app: pink theme throughout; the safe route is always green and
/// the unsafe one always red, so route roles read the same everywhere.
enum Palette {
    static let pink = Color(hex: 0xD8365E)
    static let pinkDark = Color(hex: 0x8F1E3E)
    static let pinkBg = Color(hex: 0xFBEAF0)
    static let ink = Color(hex: 0xA0275A)
    static let inkBg = Color(hex: 0xFCE4EE)
    static let sos = Color(hex: 0xC2185B)
    static let amber = Color(hex: 0xB97113)

    static let page = Color(hex: 0xF7F6F3)
    static let card = Color.white
    static let text = Color(hex: 0x201F1D)
    static let secondary = Color(hex: 0x6B6963)
    static let hairline = Color(hex: 0xE4E2DC)

    static let routeGreen = Color(hex: 0x2E9150)
    static let routeGreenBg = Color(hex: 0xE5F4EA)
    static let routeRed = Color(hex: 0xD32F2F)
    static let routeRedBg = Color(hex: 0xFDECEA)

    static let routeGreenUI = UIColor(hex: 0x2E9150)
    static let routeRedUI = UIColor(hex: 0xD32F2F)

    /// The recommended (safe) route is green, the other (unsafe) one red — map, cards and banner.
    static func role(_ isSafe: Bool) -> Color { isSafe ? routeGreen : routeRed }
    static func roleBg(_ isSafe: Bool) -> Color { isSafe ? routeGreenBg : routeRedBg }
    static func roleUI(_ isSafe: Bool) -> UIColor { isSafe ? routeGreenUI : routeRedUI }
}
