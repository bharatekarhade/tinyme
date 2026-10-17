import Foundation

/// A card or bank payment the mail scan found. It counts as spending only once you confirm it.
struct MailPaymentDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let merchant: String
    let day: LocalDay
    let time: String?
    /// As the email states it, always positive; `direction` says purchase or refund.
    let amount: Decimal
    let currency: String
    let direction: String
    let provisional: Bool
    let card: String?
    let reference: String?
    /// The same in your currency (negative for a refund); nil while no exchange rate is available.
    let home: PaymentHomeDTO?
    let categoryGuess: String?
    let matches: [PaymentMatchDTO]
    let evidence: String
    let gmailMessageId: String

    private enum CodingKeys: String, CodingKey {
        case id, merchant, day, time, amount, currency, direction, provisional, card, reference, home, matches, evidence
        case categoryGuess = "category_guess"
        case gmailMessageId = "gmail_message_id"
    }

    var isRefund: Bool { direction == "refund" }
    /// Bought in another currency than yours.
    var isForeign: Bool { home.map { $0.currency != currency } ?? true }

    var whenLabel: String {
        let date = day.date()?.formatted(.dateTime.month(.abbreviated).day()) ?? day.description
        return time.map { "\(date) · \(String($0.prefix(5)))" } ?? date
    }

    var emailURL: URL? {
        URL(string: "https://mail.google.com/mail/u/0/#all/\(gmailMessageId)")
    }
}

struct PaymentHomeDTO: Decodable, Sendable, Hashable {
    let amount: Decimal
    let currency: String
    let rate: Decimal?
    /// email (already yours), card (the bank stated it), or the rate service's name.
    let source: String
}

/// An expense you logged in chat that may be this same purchase.
struct PaymentMatchDTO: Decodable, Sendable, Hashable, Identifiable {
    let entryId: String
    let text: String?
    let amount: Decimal
    let ts: Date

    var id: String { entryId }

    private enum CodingKeys: String, CodingKey {
        case text, amount, ts
        case entryId = "entry_id"
    }
}

struct ConfirmPaymentRequest: Encodable, Sendable {
    var amount: Decimal?
    var category: String?
    var linkEntryId: String?

    private enum CodingKeys: String, CodingKey {
        case amount, category
        case linkEntryId = "link_entry_id"
    }
}

struct ConfirmedPaymentDTO: Decodable, Sendable {
    let paymentId: String
    let entryId: String

    private enum CodingKeys: String, CodingKey {
        case paymentId = "payment_id"
        case entryId = "entry_id"
    }
}

/// A posting from a job-alert email (LinkedIn's "Jobs for you").
struct JobLeadDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let title: String
    let company: String
    let location: String?
    let url: String?
    let status: String
    let foundAt: Date

    private enum CodingKeys: String, CodingKey {
        case id, title, company, location, url, status
        case foundAt = "found_at"
    }

    var postingURL: URL? { url.flatMap(URL.init(string:)) }
}

struct JobLeadsPageDTO: Decodable, Sendable {
    let items: [JobLeadDTO]
    let nextCursor: String?
    /// How many jobs are still new, for the badge.
    let newCount: Int

    private enum CodingKeys: String, CodingKey {
        case items
        case nextCursor = "next_cursor"
        case newCount = "new_count"
    }
}

struct JobTaskDTO: Decodable, Sendable {
    let jobId: String
    let planId: String

    private enum CodingKeys: String, CodingKey {
        case jobId = "job_id"
        case planId = "plan_id"
    }
}

struct JobStatusRequest: Encodable, Sendable {
    let status: String
}

/// The currency your spending is kept in. `chosen` is false while the server's default applies.
struct CurrencySettingDTO: Codable, Sendable, Equatable {
    var currency: String
    var chosen: Bool = true
}

extension Decimal {
    /// "¥3,075", "US$20.50": in the given currency's own style.
    func money(_ currency: String) -> String {
        formatted(.currency(code: currency))
    }
}
