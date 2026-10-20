import BackgroundTasks
import Foundation
import Observation
import OSLog
import UIKit
import UserNotifications

/// Every notification TinyMe sends: reminders before plans, what Gmail found, and the evening journal nudge.
///
/// They are all scheduled on this phone, because a free Apple account can't receive pushes from the server.
/// The schedule is rebuilt from the server when the app opens, after a chat turn changes something, and when
/// iOS wakes TinyMe in the background, so a plan that moved or was done never rings at its old time.
@MainActor
@Observable
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let refreshTaskID = "com.tinyme.refresh"

    /// The switches in Settings, kept on this phone.
    enum Key {
        static let plans = "notifications.plans"
        static let mail = "notifications.mail"
        static let journal = "notifications.journal"
        /// Minutes after midnight for the journal nudge.
        static let journalMinutes = "notifications.journal.minutes"
        static let journaledOn = "notifications.journal.lastDay"
        /// What G Scan had at the last check, so only new things are announced.
        static let seenMail = "notifications.mail.seen"
    }

    static let defaultJournalMinutes = 21 * 60

    /// A tapped notification's destination, picked up by the signed-in app.
    var route: NotificationRoute?
    private(set) var authorization: UNAuthorizationStatus = .notDetermined
    /// What is waiting to ring, for the line in Settings.
    private(set) var scheduled: [Scheduled] = []
    /// Why the last refresh couldn't load plans, for Settings; nil when it could.
    private(set) var problem: String?

    struct Scheduled: Equatable {
        let title: String
        let fireDate: Date
    }

    private let center = UNUserNotificationCenter.current()
    private let defaults: UserDefaults
    private let api: () -> APIClient?
    private var isRefreshing = false
    /// A refresh was asked for while one ran (a switch flipped mid-way): run once more when it ends.
    private var refreshAgain = false
    private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "TinyMe", category: "notifications")
    private var lastRefresh: Date?
    private var suspendTask = UIBackgroundTaskIdentifier.invalid

    init(defaults: UserDefaults = .standard, api: @escaping () -> APIClient?) {
        self.defaults = defaults
        self.api = api
        super.init()
        defaults.register(defaults: [Key.journalMinutes: Self.defaultJournalMinutes])
        // Set at launch, so a tap that launches the app is delivered here.
        center.delegate = self
    }

    var anyEnabled: Bool {
        defaults.bool(forKey: Key.plans) || defaults.bool(forKey: Key.mail) || defaults.bool(forKey: Key.journal)
    }

    // MARK: Permission

    /// Asks once; afterwards iOS only lets the person change it in Settings. Returns whether TinyMe may notify.
    func requestPermission() async -> Bool {
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            status = await center.notificationSettings().authorizationStatus
        }
        authorization = status
        return Self.allows(status)
    }

    func checkPermission() async {
        authorization = await center.notificationSettings().authorizationStatus
    }

    private static func allows(_ status: UNAuthorizationStatus) -> Bool {
        status == .authorized || status == .provisional || status == .ephemeral
    }

    // MARK: Scheduling

    /// Rebuilds every schedule from the server. Old reminders stay when the server can't be reached.
    func refresh() async {
        guard api() != nil else { return }
        guard !isRefreshing else {
            refreshAgain = true
            return
        }
        isRefreshing = true
        defer { isRefreshing = false }
        repeat {
            refreshAgain = false
            await refreshOnce()
        } while refreshAgain
    }

    private func refreshOnce() async {
        guard let api = api() else { return }
        lastRefresh = .now
        await checkPermission()
        guard Self.allows(authorization) else {
            log.info("refresh: notifications not allowed (\(self.authorization.rawValue))")
            await removeAll()
            await loadScheduled()
            return
        }
        await schedulePlans(api: api)
        await scheduleJournal()
        await checkMail(api: api)
        scheduleBackgroundRefresh()
        await loadScheduled()
        log.info("refresh: \(self.scheduled.count) scheduled, plans=\(self.defaults.bool(forKey: Key.plans)) mail=\(self.defaults.bool(forKey: Key.mail)) journal=\(self.defaults.bool(forKey: Key.journal))")
    }

    /// Reads back what iOS holds, so Settings shows what will really ring.
    private func loadScheduled() async {
        scheduled = await center.pendingNotificationRequests().compactMap { request in
            guard let trigger = request.trigger as? UNCalendarNotificationTrigger,
                  let fire = trigger.nextTriggerDate() else { return nil }
            return Scheduled(title: request.content.title, fireDate: fire)
        }.sorted { $0.fireDate < $1.fireDate }
    }

    /// Settings' "Send a test": rings in five seconds, so you can see notifications reach this phone.
    func sendTest() async -> Bool {
        guard await requestPermission() else { return false }
        let content = UNMutableNotificationContent()
        content.title = "TinyMe notifications work"
        content.body = "Reminders, Gmail news and the journal nudge will look like this."
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
        do {
            try await center.add(UNNotificationRequest(identifier: "tinyme.test", content: content, trigger: trigger))
            return true
        } catch {
            log.error("test notification failed: \(error.localizedDescription)")
            return false
        }
    }

    /// The app is going to the background: one more refresh, so what changed on screen rings at its new time.
    func refreshBeforeSuspending() {
        guard anyEnabled, suspendTask == .invalid else { return }
        suspendTask = UIApplication.shared.beginBackgroundTask(withName: "notifications") { [weak self] in
            self?.endSuspendTask()
        }
        Task {
            await refresh()
            endSuspendTask()
        }
    }

    private func endSuspendTask() {
        guard suspendTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(suspendTask)
        suspendTask = .invalid
    }

    /// For wake-ups that come often (Health data): at most every 15 minutes.
    func refreshIfStale() async {
        if let lastRefresh, Date.now.timeIntervalSince(lastRefresh) < 15 * 60 { return }
        await refresh()
    }

    private func schedulePlans(api: APIClient) async {
        guard defaults.bool(forKey: Key.plans) else {
            await removePending(prefixes: [NotificationPlan.planPrefix, NotificationPlan.tasksPrefix])
            return
        }
        let now = Date.now
        let today = LocalDay.today()
        guard let last = Calendar.current.date(byAdding: .day, value: 6, to: now).flatMap({ LocalDay(date: $0) }) else { return }
        let upcoming: UpcomingDTO
        do {
            upcoming = try await api.get(.upcoming(from: today, to: last))
            problem = nil
        } catch {
            // Keep what was scheduled: the plans most likely haven't changed.
            log.error("refresh: couldn't load plans: \(error.localizedDescription)")
            problem = "Couldn't reach your server, so reminders may be out of date."
            return
        }
        await removePending(prefixes: [NotificationPlan.planPrefix, NotificationPlan.tasksPrefix])
        for planned in NotificationPlan.plans(upcoming, now: now) {
            await add(planned)
        }
    }

    private func scheduleJournal() async {
        await removePending(prefixes: [NotificationPlan.journalPrefix])
        guard defaults.bool(forKey: Key.journal) else { return }
        let planned = NotificationPlan.journal(minutes: defaults.integer(forKey: Key.journalMinutes),
                                               journaledOn: defaults.string(forKey: Key.journaledOn).flatMap(LocalDay.init),
                                               now: .now)
        for notification in planned { await add(notification) }
    }

    /// Something was sent in chat today: no nudge tonight.
    func journaled() {
        let today = LocalDay.today()
        guard defaults.string(forKey: Key.journaledOn) != today.description else { return }
        defaults.set(today.description, forKey: Key.journaledOn)
        let id = NotificationPlan.journalPrefix + today.description
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    /// Announces payments, plans and jobs G Scan found since the last check, and keeps the app badge at what waits.
    private func checkMail(api: APIClient) async {
        guard defaults.bool(forKey: Key.mail) else {
            try? await center.setBadgeCount(0)
            return
        }
        async let payments: [MailPaymentDTO] = api.get(.mailPayments)
        async let suggestions: [MailSuggestionDTO] = api.get(.mailSuggestions)
        async let jobs: JobLeadsPageDTO = api.get(.jobs(status: "new", cursor: nil))
        // Only with all three: a list that failed to load would make its items look new next time.
        guard let payments = try? await payments, let suggestions = try? await suggestions,
              let jobs = try? await jobs else { return }
        let found = NotificationPlan.found(payments: payments, suggestions: suggestions, jobs: jobs.items)
        let seen = defaults.stringArray(forKey: Key.seenMail).map(Set.init)
        defaults.set(found.map(\.key), forKey: Key.seenMail)
        try? await center.setBadgeCount(payments.count + suggestions.count + jobs.newCount)
        // The first check after switching on only learns what is already there.
        guard let seen, let planned = NotificationPlan.mail(found.filter { !seen.contains($0.key) }) else { return }
        await add(planned)
    }

    /// G Scan is on screen: what it shows isn't news any more.
    func sawMail(payments: [MailPaymentDTO], suggestions: [MailSuggestionDTO], jobs: [JobLeadDTO], waiting: Int) {
        guard defaults.bool(forKey: Key.mail) else { return }
        let keys = NotificationPlan.found(payments: payments, suggestions: suggestions, jobs: jobs).map(\.key)
        defaults.set(Array(Set(keys).union(defaults.stringArray(forKey: Key.seenMail) ?? [])), forKey: Key.seenMail)
        center.removeDeliveredNotifications(withIdentifiers: [NotificationPlan.mailID])
        Task { try? await center.setBadgeCount(waiting) }
    }

    /// A switch was turned on or off in Settings.
    func settingsChanged() async {
        if !defaults.bool(forKey: Key.mail) { defaults.removeObject(forKey: Key.seenMail) }
        await refresh()
    }

    private func add(_ planned: PlannedNotification) async {
        let content = UNMutableNotificationContent()
        content.title = planned.title
        content.body = planned.body
        content.sound = .default
        content.threadIdentifier = planned.thread
        content.userInfo = ["route": planned.route.userInfo]
        var trigger: UNNotificationTrigger?
        if let fire = planned.fireDate {
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
            trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
        }
        do {
            try await center.add(UNNotificationRequest(identifier: planned.id, content: content, trigger: trigger))
        } catch {
            log.error("couldn't schedule \(planned.id): \(error.localizedDescription)")
        }
    }

    private func removePending(prefixes: [String]) async {
        let ids = await center.pendingNotificationRequests().map(\.identifier)
            .filter { id in prefixes.contains { id.hasPrefix($0) } }
        center.removePendingNotificationRequests(withIdentifiers: ids)
    }

    /// On sign-out, or when notifications aren't allowed: nothing of this account rings later.
    func removeAll() async {
        let prefixes = [NotificationPlan.planPrefix, NotificationPlan.tasksPrefix, NotificationPlan.journalPrefix,
                        NotificationPlan.mailID]
        await removePending(prefixes: prefixes)
        let delivered = await center.deliveredNotifications().map(\.request.identifier)
            .filter { id in prefixes.contains { id.hasPrefix($0) } }
        center.removeDeliveredNotifications(withIdentifiers: delivered)
        defaults.removeObject(forKey: Key.seenMail)
        try? await center.setBadgeCount(0)
    }

    // MARK: Background

    /// Called once at launch, before the app finishes launching, as BGTaskScheduler requires.
    /// Each wake also asks the server to look at Calendar and Gmail, so the next wake finds what they had.
    func registerBackgroundRefresh() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskID, using: .main) { task in
            nonisolated(unsafe) let task = task
            Task { @MainActor in
                let work = Task { @MainActor in
                    if let api = self.api() {
                        try? await api.send(.googleSync)
                        try? await api.send(.googleMailScan)
                    }
                    await self.refresh()
                }
                task.expirationHandler = { work.cancel() }
                await work.value
                self.scheduleBackgroundRefresh()
                task.setTaskCompleted(success: !work.isCancelled)
            }
        }
    }

    /// Asks iOS to wake TinyMe in about half an hour. iOS decides when, by how often the app is used.
    func scheduleBackgroundRefresh() {
        guard anyEnabled else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshTaskID)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskID)
        request.earliestBeginDate = .now.addingTimeInterval(30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    // MARK: UNUserNotificationCenterDelegate

    /// Shown as a banner while TinyMe is open too.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let text = response.notification.request.content.userInfo["route"] as? String
        Task { @MainActor in
            if let route = text.flatMap(NotificationRoute.init(userInfo:)) { self.route = route }
        }
        completionHandler()
    }
}
