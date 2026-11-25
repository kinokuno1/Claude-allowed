import SwiftUI

/// 60-30-10, same palette as the Windows and Android apps:
/// 60 % dominant near-black, 30 % dark violet-grey surfaces, 10 % purple→pink accent (only for what you act on or what's live).
enum Theme {
    static let c60 = Color(red: 0x0E / 255, green: 0x0E / 255, blue: 0x12 / 255)
    static let c30 = Color(red: 0x1A / 255, green: 0x18 / 255, blue: 0x23 / 255)
    static let c30hi = Color(red: 0x25 / 255, green: 0x22 / 255, blue: 0x31 / 255)
    static let accent = Color(red: 0x7C / 255, green: 0x4D / 255, blue: 0xEE / 255)
    static let accent2 = Color(red: 0xD4 / 255, green: 0x33 / 255, blue: 0x7F / 255)
    static let accentLight = Color(red: 0xC9 / 255, green: 0xB6 / 255, blue: 0xFF / 255)
    static let pinkLight = Color(red: 0xFF / 255, green: 0xA8 / 255, blue: 0xD8 / 255)
    static let good = Color(red: 0x6E / 255, green: 0xE7 / 255, blue: 0xB7 / 255)
    static let text = Color(red: 0xF7 / 255, green: 0xF7 / 255, blue: 0xFA / 255)
    static let muted = Color(red: 0xB5 / 255, green: 0xB3 / 255, blue: 0xC2 / 255)
    // Flat colours, no gradients (these are single-colour fills, kept as LinearGradient for the Cover view).
    static let likedColor = Color(red: 0x5B / 255, green: 0x45 / 255, blue: 0xB8 / 255)
    static let downloadsColor = Color(red: 0x0F / 255, green: 0x6E / 255, blue: 0x5C / 255)
    static let accentGradient = LinearGradient(colors: [likedColor], startPoint: .top, endPoint: .bottom)
    static let downloadsGradient = LinearGradient(colors: [downloadsColor], startPoint: .top, endPoint: .bottom)
    static let glow = Color(red: 0x3A / 255, green: 0x33 / 255, blue: 0x50 / 255)

    static func sourceColor(_ s: Source) -> Color {
        switch s {
        case .youtube: return Color(red: 1, green: 0.35, blue: 0.32)
        case .soundcloud: return Color(red: 1, green: 0.54, blue: 0.24)
        case .tiktok: return Color(red: 0.15, green: 0.96, blue: 0.93)
        case .spotify: return Color(red: 0.12, green: 0.84, blue: 0.38)
        case .other: return Color(white: 0.77)
        }
    }
}

extension View {
    /// Liquid glass on iOS 26, frosted glass before.
    @ViewBuilder
    func glassy(cornerRadius: CGFloat = 20) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(.white.opacity(0.14)))
        }
        #else
        self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(.white.opacity(0.14)))
        #endif
    }
}
