import Foundation
import Testing
@testable import TinyMe

struct SleepNightBuilderTests {
    private static func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    private static let tokyo = calendar("Asia/Tokyo")

    private static func at(_ text: String, _ calendar: Calendar = tokyo) -> Date {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.date(from: text)!
    }

    private static func sample(_ from: String, _ to: String, _ stage: SleepSample.Stage, source: String = "watch",
                               watch: Bool = true, _ calendar: Calendar = tokyo) -> SleepSample {
        SleepSample(start: at(from, calendar), end: at(to, calendar), stage: stage, source: source, isWatch: watch)
    }

    @Test func aNightAcrossMidnightBelongsToTheDayYouWokeUp() throws {
        let samples = [
            Self.sample("2026-10-12 23:40", "2026-10-13 01:10", .core),
            Self.sample("2026-10-13 01:10", "2026-10-13 02:00", .deep),
            Self.sample("2026-10-13 02:00", "2026-10-13 02:10", .awake),
            Self.sample("2026-10-13 02:10", "2026-10-13 07:10", .rem),
        ]
        #expect(SleepNightBuilder.night(for: LocalDay("2026-10-12")!, samples: samples, calendar: Self.tokyo) == nil)
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: samples, calendar: Self.tokyo))
        #expect(night.hours == 7.33)   // 7 h 30 in bed, 10 min awake
        #expect(night.data["bed_start"] == .string("23:40"))
        #expect(night.data["bed_end"] == .string("07:10"))
        #expect(night.data["deep_h"] == .number(0.83))
        #expect(night.data["awake_h"] == .number(0.17))
        if case .array(let stages)? = night.data["stages"] {
            #expect(stages.count == 4)
            #expect(stages.first == .array([.string("core"), .string("23:40"), .string("01:10")]))
        } else {
            Issue.record("stages missing")
        }
    }

    @Test func theWatchWinsAndSourcesAreNeverAddedTogether() throws {
        let samples = [
            Self.sample("2026-10-12 23:00", "2026-10-13 07:00", .unspecified, source: "phone", watch: false),
            Self.sample("2026-10-13 00:00", "2026-10-13 06:00", .core),
        ]
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: samples, calendar: Self.tokyo))
        #expect(night.hours == 6)
    }

    @Test func withoutAWatchTheSourceWithMostSleepWins() throws {
        let samples = [
            Self.sample("2026-10-12 23:00", "2026-10-13 07:00", .unspecified, source: "app", watch: false),
            Self.sample("2026-10-13 01:00", "2026-10-13 05:00", .unspecified, source: "phone", watch: false),
        ]
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: samples, calendar: Self.tokyo))
        #expect(night.hours == 8)
    }

    @Test func anAfternoonNapIsNotLastNight() throws {
        let samples = [
            Self.sample("2026-10-12 23:30", "2026-10-13 06:30", .core),
            Self.sample("2026-10-13 14:00", "2026-10-13 14:40", .core),
        ]
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: samples, calendar: Self.tokyo))
        #expect(night.hours == 7)
        #expect(night.data["bed_end"] == .string("06:30"))

        let onlyANap = [Self.sample("2026-10-13 14:00", "2026-10-13 14:40", .core)]
        #expect(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: onlyANap, calendar: Self.tokyo) == nil)
    }

    @Test func aShortNightThatStartsLateIsStillANight() throws {
        let samples = [Self.sample("2026-10-13 03:00", "2026-10-13 05:30", .core)]
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-13")!, samples: samples, calendar: Self.tokyo))
        #expect(night.hours == 2.5)
    }

    @Test func aNightOverTheClockChangeCountsRealTime() throws {
        // Clocks go back an hour at 02:00 on 25 Oct 2026 in London: 23:00 to 07:00 on the clock is 9 hours asleep.
        let london = Self.calendar("Europe/London")
        let samples = [Self.sample("2026-10-24 23:00", "2026-10-25 07:00", .core, london)]
        let night = try #require(SleepNightBuilder.night(for: LocalDay("2026-10-25")!, samples: samples, calendar: london))
        #expect(night.hours == 9)
    }

    @Test func aSampleEndingAfterThreeCountsForTheNextDay() {
        let calendar = Self.tokyo
        #expect(SleepNightBuilder.wakeDay(of: Self.at("2026-10-13 14:59"), calendar: calendar) == LocalDay("2026-10-13"))
        #expect(SleepNightBuilder.wakeDay(of: Self.at("2026-10-13 15:00"), calendar: calendar) == LocalDay("2026-10-14"))
    }
}

struct WorkoutMapperTests {
    private static let start = Date(timeIntervalSince1970: 1_791_000_000)

    @Test func aWatchWorkoutBecomesAWorkoutEntry() throws {
        let item = try #require(WorkoutMapper.item(WorkoutReading(
            id: "3F2A", activity: "traditional strength training", start: Self.start,
            end: Self.start.addingTimeInterval(52 * 60 + 20), kcal: 310.4, km: nil, avgHeartRate: 127.6, sourceApp: "Workout")))
        #expect(item.kind == "workout")
        #expect(item.externalId == "3F2A")
        #expect(item.quantity == 1)
        #expect(item.data == ["type": .string("traditional strength training"), "minutes": .number(52),
                              "kcal": .number(310), "avg_hr": .number(128), "source_app": .string("Workout")])
    }

    @Test func missingOrImplausibleNumbersAreLeftOut() throws {
        let item = try #require(WorkoutMapper.item(WorkoutReading(
            id: "run", activity: "running", start: Self.start, end: Self.start.addingTimeInterval(1800),
            kcal: 0, km: 5.0049, avgHeartRate: 400, sourceApp: nil)))
        #expect(item.data == ["type": .string("running"), "minutes": .number(30), "km": .number(5)])
    }

    @Test func aWorkoutUnderAMinuteIsSkipped() {
        #expect(WorkoutMapper.item(WorkoutReading(id: "x", activity: "walking", start: Self.start,
                                                  end: Self.start.addingTimeInterval(20), kcal: nil, km: nil,
                                                  avgHeartRate: nil, sourceApp: nil)) == nil)
    }

    @Test func activityNamesAreWhatPeopleSay() {
        #expect(WorkoutMapper.name(of: .highIntensityIntervalTraining) == "HIIT")
        #expect(WorkoutMapper.name(of: .soccer) == "football")
        #expect(WorkoutMapper.name(of: .archery) == "workout")
    }
}
