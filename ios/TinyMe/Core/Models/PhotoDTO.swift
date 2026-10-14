import Foundation

struct PhotoDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let thumbURL: URL?
    let url: URL?
    let contentURL: URL?
    let caption: String?
    let takenAt: Date?

    private enum CodingKeys: String, CodingKey {
        case id, url, caption
        case thumbURL = "thumb_url"
        case contentURL = "content_url"
        case takenAt = "taken_at"
    }
}
