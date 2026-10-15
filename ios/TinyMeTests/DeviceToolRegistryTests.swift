import Foundation
import Testing
@testable import TinyMe

struct DeviceToolRegistryTests {
    @Test func unknownDeviceToolIsAnsweredToServer() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UnknownToolURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }

        let api = APIClient(baseURL: URL(string: "https://tinyme.test")!, session: session,
                            token: { "test-device-token" })
        let registry = DeviceToolRegistry(api: api, tools: [])
        await registry.execute(actionId: "action-123", tool: "device_future_tool", input: .object([:]))

        let captured = try #require(UnknownToolURLProtocol.state.request())
        #expect(captured.url?.path == "/actions/action-123/result")
        let bodyData = try #require(captured.body)
        let body = try #require(JSONSerialization.jsonObject(with: bodyData) as? [String: Any])
        #expect(body["ok"] as? Bool == false)
        let error = try #require(body["error"] as? [String: String])
        #expect(error["code"] == "unknown_tool")
    }
}

private final class UnknownToolURLProtocol: URLProtocol, @unchecked Sendable {
    static let state = CapturedRequest()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.state.set(request, body: Self.bodyData(request))
        let response = HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func bodyData(_ request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var body = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            body.append(buffer, count: count)
        }
        return body
    }
}

private final class CapturedRequest: @unchecked Sendable {
    private let lock = NSLock()
    private var value: (url: URL?, body: Data?)?

    func set(_ request: URLRequest, body: Data?) {
        lock.lock()
        defer { lock.unlock() }
        value = (request.url, body)
    }

    func request() -> (url: URL?, body: Data?)? {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
