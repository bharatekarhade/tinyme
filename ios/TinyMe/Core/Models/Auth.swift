import Foundation

struct LoginRequest: Encodable, Sendable {
    let email: String
    let password: String
    let deviceName: String

    private enum CodingKeys: String, CodingKey {
        case email, password
        case deviceName = "device_name"
    }
}

struct LoginResponse: Decodable, Sendable {
    let token: String
    let expiresAt: Date

    private enum CodingKeys: String, CodingKey {
        case token
        case expiresAt = "expires_at"
    }
}

/// `GET /actuator/health`
struct HealthDTO: Decodable, Sendable {
    let status: String
}
