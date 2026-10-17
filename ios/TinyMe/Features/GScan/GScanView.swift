import SwiftUI

/// What TinyMe found in your Gmail: card payments to confirm, plans found in email, emails or invite replies
/// waiting for your tap, and jobs from job alerts. Payments count as spending only once you confirm them.
struct GScanView: View {
    let model: GScanModel
    @State private var editing: MailPaymentDTO?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s6) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("G Scan")
                        .textStyle(.display)
                        .accessibilityAddTraits(.isHeader)
                    Text("What TinyMe found in your Gmail")
                        .textStyle(.body)
                        .foregroundStyle(Palette.inkMuted)
                }
                .padding(.top, Space.s2)

                if let error = model.errorMessage {
                    Label(error, systemImage: "exclamationmark.circle")
                        .textStyle(.caption)
                        .foregroundStyle(Palette.danger)
                }

                if model.needsConnecting {
                    connectCard
                } else if model.phase == .loading, model.waitingCount == 0 {
                    ProgressView().tint(Palette.brandStrong).frame(maxWidth: .infinity).padding(.vertical, Space.s6)
                } else if case .failed(let message) = model.phase {
                    Text(message).textStyle(.body).foregroundStyle(Palette.inkMuted)
                } else if model.waitingCount == 0, model.isConnected {
                    emptyState
                }

                if !model.payments.isEmpty { paymentsSection }
                if !model.suggestions.isEmpty { suggestionsSection }
                if !model.confirmations.isEmpty { waitingSection }
                if !model.jobs.isEmpty { jobsSection }
            }
            .padding(.horizontal, Space.s4)
            .padding(.bottom, Space.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity)
        .background(Palette.surface.ignoresSafeArea())
        .refreshable { await model.load() }
        .task { await model.load() }
        .sheet(item: $editing) { payment in
            PaymentEditSheet(payment: payment) { amount, category, match in
                editing = nil
                model.confirm(payment, amount: amount, category: category, linking: match)
            } onCancel: {
                editing = nil
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    // MARK: Connect

    private var connectCard: some View {
        let reconnect = model.google.status?.needsReauth == true
        return VStack(alignment: .leading, spacing: Space.s3) {
            Label(reconnect ? "Reconnect Gmail" : "Connect Gmail", systemImage: "envelope.badge")
                .textStyle(.heading, weight: .semibold)
            Text(reconnect
                 ? "Google asked TinyMe to sign in again. Until then nothing new is found."
                 : "TinyMe reads new email for card payments, plans and job alerts, and keeps them here for you to confirm. Nothing is added without your tap.")
                .textStyle(.body)
                .foregroundStyle(Palette.inkMuted)
            if let error = model.google.errorMessage {
                Text(error).textStyle(.caption).foregroundStyle(Palette.danger)
            }
            Button {
                Task { await model.connect() }
            } label: {
                HStack(spacing: Space.s2) {
                    if model.google.isWorking { ProgressView().tint(Palette.onBrand) }
                    Text(reconnect ? "Reconnect Google" : "Connect Google")
                }
                .textStyle(.label, weight: .semibold)
                .foregroundStyle(Palette.onBrand)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(Palette.brand, in: Capsule())
            }
            .buttonStyle(.pressable)
            .disabled(model.google.isWorking)
        }
        .padding(Space.s4)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .cardShadow()
    }

    private var emptyState: some View {
        VStack(spacing: Space.s2) {
            Image(systemName: "tray")
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(Palette.brandStrong)
            Text("Nothing waiting")
                .textStyle(.heading, weight: .semibold)
            Text("TinyMe checks your Gmail every 15 minutes and whenever you open this tab.")
                .textStyle(.caption)
                .foregroundStyle(Palette.inkMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.s6)
    }

    // MARK: Payments

    private var paymentsSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack {
                sectionTitle("Payments", systemImage: "creditcard", count: model.payments.count)
                Spacer()
                let ready = model.readyToConfirm
                if ready.count > 1 {
                    Button("Confirm \(ready.count)") { model.confirmReady() }
                        .textStyle(.caption, weight: .semibold)
                        .foregroundStyle(Palette.brandStrong)
                        .accessibilityHint("Confirms the payments that need no decision")
                }
            }
            ForEach(model.payments) { payment in
                PaymentCard(payment: payment, isWorking: model.working.contains(payment.id),
                            onConfirm: { model.confirm(payment) },
                            onLink: { match in model.confirm(payment, linking: match) },
                            onEdit: { editing = payment },
                            onDismiss: { model.dismiss(payment) })
            }
        }
    }

    // MARK: Plans found in email

    private var suggestionsSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            sectionTitle("Found in email", systemImage: "calendar.badge.plus", count: model.suggestions.count)
            ForEach(model.suggestions) { suggestion in
                VStack(alignment: .leading, spacing: Space.s3) {
                    HStack(alignment: .top, spacing: Space.s3) {
                        icon(suggestion.type == "event" ? "calendar" : "checklist")
                        VStack(alignment: .leading, spacing: 3) {
                            Text(suggestion.title).textStyle(.label, weight: .semibold)
                            Text(suggestion.scheduleLabel).textStyle(.caption).foregroundStyle(Palette.inkMuted)
                        }
                        Spacer(minLength: 0)
                    }
                    evidence(suggestion.evidence, url: suggestion.emailURL)
                    HStack(spacing: Space.s2) {
                        primaryButton("Add to Upcoming", systemImage: "plus") { model.accept(suggestion) }
                        secondaryButton("Dismiss") { model.dismiss(suggestion) }
                    }
                    .disabled(model.working.contains(suggestion.id))
                }
                .padding(Space.s3)
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                .overlay { RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.line) }
            }
        }
    }

    // MARK: Waiting for your tap

    private var waitingSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            sectionTitle("Waiting for you", systemImage: "paperplane", count: model.confirmations.count)
            ForEach(model.confirmations) { confirmation in
                GoogleConfirmationCard(confirmation: confirmation, isSending: model.working.contains(confirmation.id),
                                       error: nil, status: nil,
                                       onSend: { model.decide(confirmation, send: true) },
                                       onCancel: { model.decide(confirmation, send: false) })
            }
        }
    }

    // MARK: Job alerts

    private var jobsSection: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            sectionTitle("Job alerts", systemImage: "briefcase", count: model.newJobsCount)
            ForEach(model.jobs) { job in
                JobCard(job: job, isWorking: model.working.contains(job.id),
                        onAdd: { model.addTask(job) },
                        onDismiss: { model.dismiss(job) })
            }
            if model.hasMoreJobs {
                Button { Task { await model.loadMoreJobs() } } label: {
                    HStack(spacing: Space.s2) {
                        if model.isLoadingMoreJobs { ProgressView().tint(Palette.brandStrong) }
                        Text("Show more jobs")
                    }
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(Palette.brandStrong)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                }
                .buttonStyle(.pressable)
                .disabled(model.isLoadingMoreJobs)
            }
        }
    }

    // MARK: Pieces

    private func sectionTitle(_ title: String, systemImage: String, count: Int) -> some View {
        HStack(spacing: Space.s2) {
            Label(title, systemImage: systemImage)
                .textStyle(.heading, weight: .semibold)
            Text("\(count)")
                .textStyle(.caption, weight: .semibold)
                .foregroundStyle(Palette.inkMuted)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One payment: what was charged, where, and what to do with it.
private struct PaymentCard: View {
    let payment: MailPaymentDTO
    let isWorking: Bool
    let onConfirm: () -> Void
    let onLink: (PaymentMatchDTO) -> Void
    let onEdit: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(alignment: .top, spacing: Space.s3) {
                icon(payment.isRefund ? "arrow.uturn.backward" : "creditcard")
                VStack(alignment: .leading, spacing: 3) {
                    Text(payment.merchant)
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(2)
                    Text([payment.whenLabel, payment.card].compactMap { $0 }.joined(separator: " · "))
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(1)
                }
                Spacer(minLength: Space.s2)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(mainAmount)
                        .textStyle(.label, weight: .semibold)
                        .foregroundStyle(payment.isRefund ? Palette.brandStrong : Palette.ink)
                    if payment.isForeign, payment.home != nil {
                        Text(payment.amount.money(payment.currency))
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                    }
                }
            }

            HStack(spacing: Space.s2) {
                if let category = payment.categoryGuess {
                    tag(category, systemImage: "tag")
                }
                if payment.provisional {
                    tag("may still change", systemImage: "hourglass")
                }
                if payment.home == nil {
                    tag("no rate yet: tap Edit", systemImage: "exclamationmark.circle")
                }
            }

            if let match = payment.matches.first {
                Button { onLink(match) } label: {
                    HStack(spacing: Space.s2) {
                        Image(systemName: "link")
                        Text("Same as “\(match.text ?? "expense")” \(match.amount.money(payment.home?.currency ?? payment.currency))?")
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                        Text("Link").fontWeight(.semibold)
                    }
                    .textStyle(.caption)
                    .foregroundStyle(Palette.brandStrong)
                    .padding(Space.s3)
                    .background(Palette.brandSoft, in: RoundedRectangle(cornerRadius: Radius.sm, style: .continuous))
                }
                .buttonStyle(.pressable)
                .accessibilityHint("Adds the card's details to the expense you logged, instead of a second one")
            }

            evidence(payment.evidence, url: payment.emailURL)

            HStack(spacing: Space.s2) {
                primaryButton(payment.matches.isEmpty ? "Confirm" : "Add as new", systemImage: "checkmark", action: onConfirm)
                    .disabled(payment.home == nil)
                secondaryButton("Edit", action: onEdit)
                secondaryButton("Dismiss", action: onDismiss)
            }
            .disabled(isWorking)
        }
        .padding(Space.s3)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.line) }
        .opacity(isWorking ? 0.6 : 1)
    }

    private var mainAmount: String {
        guard let home = payment.home else { return payment.amount.money(payment.currency) }
        return home.amount.money(home.currency)
    }

    private func tag(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .textStyle(.caption, weight: .medium)
            .foregroundStyle(Palette.inkMuted)
            .padding(.horizontal, Space.s2)
            .padding(.vertical, 4)
            .background(Palette.surfaceSunken, in: Capsule())
    }
}

/// One job from a job alert: what and where, a link to the posting, and whether to make it a task.
private struct JobCard: View {
    let job: JobLeadDTO
    let isWorking: Bool
    let onAdd: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack(alignment: .top, spacing: Space.s3) {
                OriginLogo(origin: "linkedin", size: 28)
                    .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 3) {
                    Text(job.title)
                        .textStyle(.label, weight: .semibold)
                        .lineLimit(2)
                    Text([job.company, job.location].compactMap { $0 }.joined(separator: " · "))
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            if let url = job.postingURL {
                Link(destination: url) {
                    Label("View on LinkedIn", systemImage: "arrow.up.right.square")
                        .textStyle(.caption, weight: .semibold)
                        .foregroundStyle(Palette.brandStrong)
                }
            }
            HStack(spacing: Space.s2) {
                primaryButton("Add to tasks", systemImage: "plus", action: onAdd)
                secondaryButton("Dismiss", action: onDismiss)
            }
            .disabled(isWorking)
        }
        .padding(Space.s3)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay { RoundedRectangle(cornerRadius: Radius.md, style: .continuous).strokeBorder(Palette.line) }
        .opacity(isWorking ? 0.6 : 1)
    }
}

/// Fix a payment before confirming it: the amount in your currency, its category, or the chat expense it really is.
private struct PaymentEditSheet: View {
    let payment: MailPaymentDTO
    let onSave: (Decimal?, String?, PaymentMatchDTO?) -> Void
    let onCancel: () -> Void

    @State private var amountText = ""
    @State private var category = ""
    @State private var linkId: String?

    private var currency: String { payment.home?.currency ?? payment.currency }
    private var amount: Decimal? { Decimal(string: amountText.replacingOccurrences(of: ",", with: ""), locale: Locale(identifier: "en_US_POSIX")) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Amount", text: $amountText)
                        .keyboardType(.decimalPad)
                } header: {
                    Text("Amount in \(currency)")
                } footer: {
                    if payment.isForeign {
                        Text("The email says \(payment.amount.money(payment.currency)).")
                    }
                }
                Section("Category") {
                    TextField("e.g. coffee, groceries, transport", text: $category)
                        .textInputAutocapitalization(.never)
                }
                if !payment.matches.isEmpty {
                    Section {
                        Picker("Same purchase as", selection: $linkId) {
                            Text("None, add as new").tag(String?.none)
                            ForEach(payment.matches) { match in
                                Text("\(match.text ?? "expense") · \(match.amount.money(currency))").tag(Optional(match.entryId))
                            }
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                    } header: {
                        Text("Already logged in chat?")
                    }
                }
            }
            .navigationTitle(payment.merchant)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Confirm") {
                        // Only an amount you changed is sent; the one read from the email stays "automatic".
                        let typed = amount == payment.home.map { abs($0.amount) } ? nil : amount
                        let trimmed = category.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(typed, trimmed.isEmpty || trimmed == payment.categoryGuess ? nil : trimmed,
                               payment.matches.first { $0.entryId == linkId })
                    }
                    .fontWeight(.semibold)
                    .disabled(amount == nil || (amount ?? 0) <= 0)
                }
            }
        }
        .onAppear {
            if let home = payment.home {
                amountText = "\(abs(home.amount))"
            }
            category = payment.categoryGuess ?? ""
        }
    }
}

// MARK: Shared pieces

@MainActor private func icon(_ systemName: String) -> some View {
    Image(systemName: systemName)
        .font(.system(size: 16, weight: .semibold))
        .foregroundStyle(Palette.brandStrong)
        .frame(width: 36, height: 36)
        .background(Palette.brandSoft, in: Circle())
        .accessibilityHidden(true)
}

@MainActor private func evidence(_ quote: String, url: URL?) -> some View {
    VStack(alignment: .leading, spacing: Space.s2) {
        Text("“\(quote)”")
            .textStyle(.caption)
            .foregroundStyle(Palette.inkMuted)
            .lineLimit(3)
            .frame(maxWidth: .infinity, alignment: .leading)
        if let url {
            Link(destination: url) {
                Label("View email", systemImage: "arrow.up.right.square")
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(Palette.brandStrong)
            }
        }
    }
}

@MainActor private func primaryButton(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Label(title, systemImage: systemImage)
            .textStyle(.caption, weight: .semibold)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(Palette.brand, in: Capsule())
            .foregroundStyle(Palette.onBrand)
    }
    .buttonStyle(.pressable)
}

@MainActor private func secondaryButton(_ title: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Text(title)
            .textStyle(.caption, weight: .semibold)
            .foregroundStyle(Palette.inkMuted)
            .frame(height: 40)
            .padding(.horizontal, Space.s3)
            .background(Palette.surfaceSunken, in: Capsule())
    }
    .buttonStyle(.pressable)
}
