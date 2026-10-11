import SwiftUI
#if canImport(UIKit)
import UIKit
private typealias PlatformColor = UIColor
#else
import AppKit
private typealias PlatformColor = NSColor
#endif

// Values mirror tokens.json in the TinyMe design system (version 5).

enum Palette {
    static let surface = Color(light: 0xfffaf3, dark: 0x0f1716)
    static let surfaceRaised = Color(light: 0xffffff, dark: 0x182321)
    static let surfaceSunken = Color(light: 0xf4ecdf, dark: 0x22302d)
    static let line = Color(light: 0xefe6d8, dark: 0x263432)
    static let ink = Color(light: 0x13201e, dark: 0xf2f7f5)
    static let onInk = Color(light: 0xffffff, dark: 0x13201e)
    static let inkMuted = Color(light: 0x55625f, dark: 0xa6b4b0)
    static let brand = Color(light: 0x10c4a8, dark: 0x2ee6c6)
    static let onBrand = Color(light: 0x04241f, dark: 0x04241f)
    static let brandStrong = Color(light: 0x0a7d6c, dark: 0x5ff0d6)
    static let brandSoft = Color(light: 0xc9f7ec, dark: 0x0f3d36)
    static let accent = Color(light: 0xff8a5c, dark: 0xff9a70)
    static let accentSoft = Color(light: 0xffe3d6, dark: 0x3d2219)
    static let sunny = Color(light: 0xffd166, dark: 0xffd166)
    static let sunnySoft = Color(light: 0xfff1c7, dark: 0x3a3015)
    static let danger = Color(light: 0xd92d20, dark: 0xff7a6e)
}

enum Space {
    static let s2: CGFloat = 8
    static let s3: CGFloat = 12
    static let s4: CGFloat = 16
    static let s6: CGFloat = 24
}

enum Radius {
    static let sm: CGFloat = 10
    static let md: CGFloat = 18
    static let lg: CGFloat = 28
}

enum TextStyle {
    case numeral, display, title, heading, body, label, caption

    var size: CGFloat {
        switch self {
        case .numeral: 56
        case .display: 40
        case .title: 26
        case .heading: 18
        case .body: 16
        case .label: 14
        case .caption: 12
        }
    }

    var weight: Font.Weight {
        switch self {
        case .numeral, .display, .title, .label, .caption: .medium
        case .heading: .semibold
        case .body: .regular
        }
    }

    var tracking: CGFloat {
        switch self {
        case .numeral: size * -0.03
        case .display: size * -0.025
        case .title: size * -0.015
        default: 0
        }
    }
}

extension View {
    func textStyle(_ style: TextStyle, weight: Font.Weight? = nil) -> some View {
        font(.custom(Fonts.family, size: style.size).weight(weight ?? style.weight))
            .tracking(style.tracking)
    }

    /// shadow-card: the barely-there lift under raised cards.
    func cardShadow() -> some View {
        shadow(color: Palette.cardShadowTight, radius: 1, y: 1)
            .shadow(color: Palette.cardShadowWide, radius: 15, y: 10)
    }
}

extension Palette {
    fileprivate static let cardShadowTight = Color(light: 0x13201e, dark: 0x000000, lightAlpha: 0.04, darkAlpha: 0.3)
    fileprivate static let cardShadowWide = Color(light: 0x13201e, dark: 0x000000, lightAlpha: 0.07, darkAlpha: 0.3)
}

extension Color {
    /// A colour that follows the system appearance; values are 0xRRGGBB.
    init(light: UInt32, dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? PlatformColor(hex: dark, alpha: darkAlpha)
                : PlatformColor(hex: light, alpha: lightAlpha)
        })
        #else
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? PlatformColor(hex: dark, alpha: darkAlpha)
                : PlatformColor(hex: light, alpha: lightAlpha)
        })
        #endif
    }
}

private extension PlatformColor {
    convenience init(hex: UInt32, alpha: CGFloat) {
        self.init(
            red: CGFloat((hex >> 16) & 0xff) / 255,
            green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255,
            alpha: alpha
        )
    }
}
