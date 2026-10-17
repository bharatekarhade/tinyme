import Foundation
import Observation

/// Everything TinyMe found in your Gmail that waits for you: payments to confirm, plans found in email, and
/// emails or invite replies waiting for your tap.
@MainActor
@Observable
final class GScanModel {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    private let api: APIClient
    let google = GoogleIntegrationModel()
    private(set) var phase: Phase = .loading
    private(set) var payments: [MailPaymentDTO] = []
    private(set) var suggestions: [MailSuggestionDTO] = []
    private(set) var confirmations: [GooglePendingConfirmationDTO] = []
    /// New jobs from job alerts, a page at a time.
    private(set) var jobs: [JobLeadDTO] = []
    private(set) var newJobsCount = 0
    private var jobsCursor: String?
    private(set) var isLoadingMoreJobs = false
    var hasMoreJobs: Bool { jobsCursor != nil }
    /// Items with a request in flight, so their buttons can't be pressed twice.
    private(set) var working: Set<String> = []
    var errorMessage: String?
    /// Something changed elsewhere (a plan was added): Upcoming and Home reload.
    var onChange: () -> Void = {}

    init(api: APIClient) {
        self.api = api
    }

    /// How many things wait for you, for the tab's badge.
    var waitingCount: Int { payments.count + suggestions.count + confirmations.count + newJobsCount }

    var isConnected: Bool { google.status?.isConnected == true }
    var needsConnecting: Bool {
        guard let status = google.status else { return false }
        return !status.isConnected
    }

    /// Payments that can be confirmed as they are: a known amount and no chat expense that may be the same one.
    var readyToConfirm: [MailPaymentDTO] {
        payments.filter { $0.home != nil && $0.matches.isEmpty }
    }

    func load() async {
        if phase != .loaded { phase = .loading }
        await google.refresh(api: api)
        guard google.status != nil else {
            // The server didn't answer: say so, rather than an empty screen that looks like "nothing found".
            phase = .failed("Couldn't reach your server. Pull down to try again.")
            return
        }
        guard isConnected else {
            payments = []
            suggestions = []
            confirmations = []
            jobs = []
            newJobsCount = 0
            jobsCursor = nil
            phase = .loaded
            return
        }
        async let paymentsResult: [MailPaymentDTO] = api.get(.mailPayments)
        async let suggestionsResult: [MailSuggestionDTO] = api.get(.mailSuggestions)
        async let confirmationsResult: [GooglePendingConfirmationDTO] = api.get(.pendingConfirmations)
        async let jobsResult: JobLeadsPageDTO = api.get(.jobs(status: "new", cursor: nil))
        do {
            let loaded = try await paymentsResult
            payments = loaded
            phase = .loaded
        } catch {
            if payments.isEmpty { phase = .failed("Couldn't load what Gmail found. Check your connection and try again.") }
        }
        suggestions = (try? await suggestionsResult) ?? suggestions
        confirmations = (try? await confirmationsResult) ?? confirmations
        if let page = try? await jobsResult {
            jobs = page.items
            jobsCursor = page.nextCursor
            newJobsCount = page.newCount
        }
    }

    func connect() async {
        await google.connect(api: api)
        await load()
    }

    // MARK: Payments

    /// Confirms a payment: as read, or with your amount, category, or the chat expense it really is.
    func confirm(_ payment: MailPaymentDTO, amount: Decimal? = nil, category: String? = nil, linking match: PaymentMatchDTO? = nil) {
        perform(payment.id, failure: "Couldn't confirm that payment. Try again.") { [api] in
            let _: ConfirmedPaymentDTO = try await api.send(.confirmPayment(payment.id), body: ConfirmPaymentRequest(
                amount: amount, category: category, linkEntryId: match?.entryId))
        } done: { model in
            model.payments.removeAll { $0.id == payment.id }
            model.onChange()
        }
    }

    /// Confirms every payment that needs no decision; the rest stay for you to look at.
    func confirmReady() {
        for payment in readyToConfirm { confirm(payment) }
    }

    func dismiss(_ payment: MailPaymentDTO) {
        perform(payment.id, failure: "Couldn't dismiss that payment. Try again.") { [api] in
            try await api.send(.dismissPayment(payment.id))
        } done: { model in
            model.payments.removeAll { $0.id == payment.id }
        }
    }

    // MARK: Plans found in email

    func accept(_ suggestion: MailSuggestionDTO) {
        perform(suggestion.id, failure: "Couldn't add that plan. Try again.") { [api] in
            let _: AcceptedMailSuggestionDTO = try await api.send(.acceptMailSuggestion(suggestion.id))
        } done: { model in
            model.suggestions.removeAll { $0.id == suggestion.id }
            model.onChange()
        }
    }

    func dismiss(_ suggestion: MailSuggestionDTO) {
        perform(suggestion.id, failure: "Couldn't dismiss that suggestion. Try again.") { [api] in
            try await api.send(.dismissMailSuggestion(suggestion.id))
        } done: { model in
            model.suggestions.removeAll { $0.id == suggestion.id }
        }
    }

    // MARK: Job alerts

    func loadMoreJobs() async {
        guard let cursor = jobsCursor, !isLoadingMoreJobs else { return }
        isLoadingMoreJobs = true
        defer { isLoadingMoreJobs = false }
        do {
            let page: JobLeadsPageDTO = try await api.get(.jobs(status: "new", cursor: cursor))
            let known = Set(jobs.map(\.id))
            jobs += page.items.filter { !known.contains($0.id) }
            jobsCursor = page.nextCursor
        } catch {
            errorMessage = Self.message(error, fallback: "Couldn't load more jobs. Try again.")
        }
    }

    /// Adds the job to today's tasks in Upcoming.
    func addTask(_ job: JobLeadDTO) {
        perform(job.id, failure: "Couldn't add that job to your tasks. Try again.") { [api] in
            let _: JobTaskDTO = try await api.send(.addJobTask(job.id))
        } done: { model in
            model.removeJob(job)
            model.onChange()
        }
    }

    func dismiss(_ job: JobLeadDTO) {
        perform(job.id, failure: "Couldn't dismiss that job. Try again.") { [api] in
            let _: JobLeadDTO = try await api.send(.updateJob(job.id), body: JobStatusRequest(status: "not_interested"))
        } done: { model in
            model.removeJob(job)
        }
    }

    private func removeJob(_ job: JobLeadDTO) {
        jobs.removeAll { $0.id == job.id }
        newJobsCount = max(0, newJobsCount - 1)
    }

    // MARK: Waiting for your tap

    func decide(_ confirmation: GooglePendingConfirmationDTO, send: Bool) {
        let failure = send ? "Gmail couldn't confirm it. Check Sent before trying again."
                           : "Couldn't cancel it. Try again."
        perform(confirmation.id, failure: failure) { [api] in
            let _: GmailConfirmationDecisionDTO = try await api.send(
                .decideConfirmation(confirmation.id), body: GmailConfirmationDecisionRequest(decision: send ? "send" : "cancel"))
        } done: { model in
            model.confirmations.removeAll { $0.id == confirmation.id }
        }
    }

    private func perform(_ id: String, failure: String,
                         _ request: @escaping @Sendable () async throws -> Void,
                         done: @escaping (GScanModel) -> Void) {
        guard !working.contains(id) else { return }
        working.insert(id)
        Task { [weak self] in
            do {
                try await request()
                guard let self else { return }
                self.working.remove(id)
                self.errorMessage = nil
                done(self)
            } catch {
                self?.working.remove(id)
                self?.errorMessage = Self.message(error, fallback: failure)
            }
        }
    }

    private static func message(_ error: Error, fallback: String) -> String {
        if case APIError.server(_, _, let detail?) = error, !detail.isEmpty { return detail }
        if error is URLError { return "Can't reach your server." }
        return fallback
    }
}
