import Foundation

/// The currency your spending is kept in. The first time, the server takes your phone region's; after that it is
/// only what you choose in Profile.
enum CurrencySetting {
    /// The usual choices, then whatever else your region or server uses.
    static let common = ["JPY", "USD", "EUR", "GBP", "INR", "CNY", "KRW", "TWD", "HKD", "SGD", "THB", "AUD", "CAD", "CHF"]

    static var phoneCurrency: String? { Locale.current.currency?.identifier }

    /// While the server still has its built-in default, it takes this phone's region currency. Never asks twice.
    static func adoptPhoneRegion(api: APIClient) async {
        guard let current: CurrencySettingDTO = try? await api.get(.currency), !current.chosen,
              let phone = phoneCurrency else { return }
        let _: CurrencySettingDTO? = try? await api.send(.setCurrency, body: CurrencyRequest(currency: phone))
    }

    static func name(_ code: String) -> String {
        Locale.current.localizedString(forCurrencyCode: code).map { "\($0) (\(code))" } ?? code
    }
}

struct CurrencyRequest: Encodable, Sendable {
    let currency: String
}
