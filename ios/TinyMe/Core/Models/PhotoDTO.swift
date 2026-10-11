import Foundation

struct PhotoDTO: Decodable, Sendable, Hashable, Identifiable {
    let id: String
    let thumbURL: URL
    let url: URL

    private enum CodingKeys: String, CodingKey {
        case id
        case thumbURL = "thumb_url"
        case url
    }
}
