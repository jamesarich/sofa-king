import SwiftUI

/// The Compose theme's palette (`ui/theme/Color.kt`), so the Apple TV app wears the same late-night living room.
extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    static let sofaPlum = Color(hex: 0x14111F)
    static let sofaPlumRaised = Color(hex: 0x2A2440)
    static let sofaDusk = Color(hex: 0xF4EFFA)
    static let sofaMauve = Color(hex: 0xC4BAD9)
    static let sofaOutline = Color(hex: 0x8C80A8)

    static let sofaAmber = Color(hex: 0xFFB547)
    static let sofaAmberDeep = Color(hex: 0x3D2B12)

    static let sofaLavender = Color(hex: 0xC9B8FF)
    static let sofaLavenderDeep = Color(hex: 0x332A52)

    static let sofaError = Color(hex: 0xFFB4AB)

    /// Over live video: plum at 88% keeps dusk text above 11:1 and mauve above 7:1 even over a white picture.
    static let sofaOverlayScrim = Color.sofaPlum.opacity(0.88)
}

extension View {
    /// The tint for a tab's own buttons. The app's amber marks the open tab and selections, but as a button's tint it
    /// fills an unfocused tvOS button amber under its white label, too faint to read; deep lavender keeps the label above
    /// 11:1 and a focused button still turns white.
    func buttonTint() -> some View { tint(.sofaLavenderDeep) }
}
