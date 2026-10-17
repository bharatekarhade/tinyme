import SwiftUI

/// The full-color logo of where a plan came from: Gmail, LinkedIn or Google Calendar. Nothing for what you made in TinyMe.
struct OriginLogo: View {
    let origin: String?
    var size: CGFloat = 16

    var body: some View {
        if let asset = Self.asset(origin) {
            Image(asset)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .accessibilityLabel(Self.label(origin))
        }
    }

    static func asset(_ origin: String?) -> String? {
        switch origin {
        case "gmail": "LogoGmail"
        case "linkedin": "LogoLinkedIn"
        case "google_calendar": "LogoGoogleCalendar"
        default: nil
        }
    }

    static func label(_ origin: String?) -> String {
        switch origin {
        case "gmail": "From Gmail"
        case "linkedin": "From LinkedIn"
        case "google_calendar": "From Google Calendar"
        default: ""
        }
    }
}
