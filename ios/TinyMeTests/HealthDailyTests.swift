import Foundation
import Testing
@testable import TinyMe

struct HealthDailyTests {
    private static let tokyo: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        return calendar
    }()

    private static func metric(_ kind: String) -> HealthMetric {
        HealthCatalog.metrics.first { $0.kind == kind }!
    }

    private static func day(_ text: String) -> LocalDay { LocalDay(text)! }

    @Test func aDayTotalBecomesOneRoundedItemAtLocalNoon() throws {
        let made = HealthDailyItems.make(metric: Self.metric("walk_distance"),
                                         values: [Self.day("2026-10-14"): 5.234999999],
                                         days: [Self.day("2026-10-14")], deleteEmpty: true, calendar: Self.tokyo)
        let item = try #require(made.items.first)
        #expect(item.externalId == "walk_distance:2026-10-14")
        #expect(item.quantity == 5.23)
        #expect(item.data == ["km": .number(5.23)])
        #expect(item.ts == "2026-10-14T03:00:00Z")
        #expect(made.deleted.isEmpty)
    }

    @Test func noiseFromUnitConversionDoesNotSurvive() throws {
        let made = HealthDailyItems.make(metric: Self.metric("steps"), values: [Self.day("2026-10-14"): 6419.6],
                                         days: [Self.day("2026-10-14")], deleteEmpty: false, calendar: Self.tokyo)
        let json = String(decoding: try JSONEncoder().encode(made.items), as: UTF8.self)
        #expect(json.contains(#""quantity":6420"#))
    }

    @Test func aRecentDayWithNothingIsADeleteAndAnImportedOneIsSkipped() {
        let days = [Self.day("2026-10-14"), Self.day("2026-10-13")]
        let values = [Self.day("2026-10-14"): 0.3]   // rounds to 0 steps
        let recent = HealthDailyItems.make(metric: Self.metric("steps"), values: values, days: days,
                                           deleteEmpty: true, calendar: Self.tokyo)
        #expect(recent.items.isEmpty)
        #expect(recent.deleted == ["steps:2026-10-14", "steps:2026-10-13"])

        let imported = HealthDailyItems.make(metric: Self.metric("steps"), values: values, days: days,
                                             deleteEmpty: false, calendar: Self.tokyo)
        #expect(imported.items.isEmpty && imported.deleted.isEmpty)
    }

    @Test func theImportWalksBackInThirtyDayChunksAndStopsAtTheOldestDay() throws {
        let oldest = Self.day("2026-07-17")   // 90 days up to 2026-10-14
        let first = try #require(HealthImportPlan.chunk(oldest: oldest, end: Self.day("2026-10-12"), calendar: Self.tokyo))
        #expect(first.count == 30)
        #expect(first.first == Self.day("2026-10-11"))
        #expect(first.last == Self.day("2026-09-12"))

        var end = Self.day("2026-10-12")
        var chunks = 0
        while let days = HealthImportPlan.chunk(oldest: oldest, end: end, calendar: Self.tokyo) {
            chunks += 1
            end = days.last!
        }
        #expect(chunks == 3)
        #expect(end == oldest)
        #expect(HealthImportPlan.chunk(oldest: oldest, end: oldest, calendar: Self.tokyo) == nil)
    }

    @Test func dailyMetricsFollowTheGroups() {
        #expect(HealthCatalog.dailyMetrics(in: [.activity]).map(\.kind) == ["steps", "active_energy", "exercise", "walk_distance"])
        #expect(HealthCatalog.dailyMetrics(in: [.sleepHeart]).map(\.kind) == ["resting_hr", "hrv"])
        #expect(HealthCatalog.dailyMetrics(in: [.body, .workouts]).isEmpty)
    }

    @Test func theServersBadItemIsFoundInItsMessage() {
        #expect(HealthUploader.badItem(in: "items[12]: data.kg must be between 20 and 400") == .item(12))
        #expect(HealthUploader.badItem(in: "deleted[1]: external_id is required") == .deleted(1))
        #expect(HealthUploader.badItem(in: "send at most 500 items at a time") == nil)
    }
}

/// The uploader against a stubbed server: a bad item is dropped and the rest sent again.
@Suite(.serialized)
struct HealthUploaderTests {
    final class StubServer: URLProtocol {
        nonisolated(unsafe) static var bodies: [Data] = []
        nonisolated(unsafe) static var replies: [(Int, String)] = []

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            var body = request.httpBody ?? Data()
            if body.isEmpty, let stream = request.httpBodyStream {
                stream.open()
                var buffer = [UInt8](repeating: 0, count: 65_536)
                while stream.hasBytesAvailable {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    body.append(buffer, count: count)
                }
                stream.close()
            }
            Self.bodies.append(body)
            let (status, json) = Self.replies.isEmpty ? (200, #"{"upserted":0,"unchanged":0,"deleted":0,"merged":0}"#) : Self.replies.removeFirst()
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": status == 200 ? "application/json" : "application/problem+json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(json.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() { }
    }

    private func uploader() -> HealthUploader {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubServer.self]
        return HealthUploader(api: APIClient(baseURL: URL(string: "https://tinyme.test")!,
                                             session: URLSession(configuration: configuration), token: { "token" }))
    }

    private func item(_ day: Int) -> HealthUploadItem {
        HealthUploadItem(externalId: "steps:2026-10-\(day)", kind: "steps", localDay: LocalDay("2026-10-\(day)")!,
                         ts: "2026-10-\(day)T03:00:00Z", quantity: 100, data: ["steps": .number(100)])
    }

    @Test func aBadItemIsDroppedAndTheRestAreSentAgain() async throws {
        StubServer.bodies = []
        StubServer.replies = [
            (400, #"{"status":400,"detail":"items[1]: data.steps must be between 1 and 200000","index":1}"#),
            (200, #"{"upserted":2,"unchanged":0,"deleted":1,"merged":0}"#),
        ]
        let totals = try await uploader().send(items: [item(12), item(13), item(14)], deleted: ["steps:2026-10-11"])

        #expect(totals == HealthUploader.Totals(upserted: 2, unchanged: 0, deleted: 1, dropped: 1))
        #expect(StubServer.bodies.count == 2)
        let resent = try JSONSerialization.jsonObject(with: StubServer.bodies[1]) as! [String: Any]
        let ids = (resent["items"] as! [[String: Any]]).map { $0["external_id"] as! String }
        #expect(ids == ["steps:2026-10-12", "steps:2026-10-14"])
    }

    @Test func moreThanFiveHundredGoInSeveralBatches() async throws {
        StubServer.bodies = []
        StubServer.replies = []
        let many = (0..<501).map { index in
            HealthUploadItem(externalId: "steps:\(index)", kind: "steps", localDay: LocalDay("2026-10-14")!,
                             ts: "2026-10-14T03:00:00Z", quantity: 1, data: ["steps": .number(1)])
        }
        _ = try await uploader().send(items: many, deleted: [])
        #expect(StubServer.bodies.count == 2)
    }
}
