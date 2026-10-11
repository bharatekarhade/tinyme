import CoreText
import Foundation

enum Fonts {
    static let family = "Plus Jakarta Sans"

    /// Registers every bundled .ttf so the family resolves without Info.plist entries.
    static func register() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        for url in urls {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}
