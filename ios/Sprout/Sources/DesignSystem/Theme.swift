import SwiftUI

/// Sprout's palette, from `ui/theme/Theme.kt`.
///
/// Android derives its colours from the wallpaper when the phone offers it and
/// falls back to these. iOS has nothing to derive from, so these *are* the
/// palette (ADR-0015) — which means they carry more weight here than there, and
/// the dark values are the ones the app is most often read in: this is a screen
/// looked at in a dark room at 3 a.m.
enum SproutColor {

    // The hues, as `Theme.kt` names them.
    static let green = Color(hex: 0x4CAF7D)
    static let greenDark = Color(hex: 0x1B5E3F)
    static let amber = Color(hex: 0xF2A65A)
    static let blush = Color(hex: 0xE8A0BF)

    /// The accent everything interactive is drawn in.
    static let primary = Color(light: 0x4CAF7D, dark: 0x7FD0A6)
    static let onPrimary = Color(light: 0xFFFFFF, dark: 0x00391F)
    static let primaryContainer = Color(light: 0xD6F2E2, dark: 0x1B5E3F)
    static let onPrimaryContainer = Color(light: 0x1B5E3F, dark: 0xD6F2E2)

    static let secondary = amber
    static let tertiary = blush

    /// The page behind everything. Slightly green-tinted in light, near-black in
    /// dark — not pure black, which reads as a hole at night.
    static let background = Color(light: 0xF7FBF8, dark: 0x101512)
    static let surface = Color(light: 0xFFFFFF, dark: 0x1B211D)
    static let onSurface = Color(light: 0x1A1C1A, dark: 0xE2E4E0)
    static let onSurfaceVariant = Color(light: 0x5C645E, dark: 0xA8B0AA)
    static let outline = Color(light: 0xA5ADA7, dark: 0x707872)

    /// The "nothing here" ground: the empty column in a bar chart, the unfilled
    /// part of a share bar.
    ///
    /// Android inherits Material 3's baseline `surfaceVariant`, which is a purple
    /// grey and would sit badly against this palette's green tint. iOS has no
    /// baseline to inherit (ADR-0015), so it is derived here instead — one step
    /// in from the background towards the outline, in both appearances.
    static let surfaceVariant = Color(light: 0xE3E9E5, dark: 0x2E3630)
    static let outlineVariant = Color(light: 0xD3DAD6, dark: 0x3C443E)

    /// Destructive actions only. Never used to grade a logged value — nothing a
    /// parent records is wrong (BDR-0014).
    static let danger = Color(light: 0xB3261E, dark: 0xF2B8B5)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }

    /// A colour that follows the system appearance.
    ///
    /// `UIColor`'s trait-aware initialiser rather than two static colours behind
    /// an `@Environment` check, so it resolves correctly everywhere — including
    /// inside a widget and in a snapshot taken in either appearance.
    init(light: UInt32, dark: UInt32) {
        self.init(
            UIColor { traits in
                UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
            }
        )
    }
}

// MARK: - Spacing and shape

/// One scale, so screens do not each invent their own gaps.
enum Spacing {
    static let hairline: CGFloat = 4
    static let tight: CGFloat = 8
    static let snug: CGFloat = 12
    static let regular: CGFloat = 16
    static let loose: CGFloat = 24
    static let section: CGFloat = 32
}

enum Radius {
    static let card: CGFloat = 16
    static let control: CGFloat = 12
    static let pill: CGFloat = 999
}

/// Applies the app's background and tint to whatever it wraps.
///
/// The equivalent of wrapping a screen in `SproutTheme { }`: every screen sits
/// inside one, so nothing has to remember to set its own background.
struct SproutStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .tint(SproutColor.primary)
            .background(SproutColor.background.ignoresSafeArea())
    }
}

extension View {
    func sproutStyle() -> some View { modifier(SproutStyle()) }
}
