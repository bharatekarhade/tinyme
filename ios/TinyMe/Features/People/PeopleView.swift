import SwiftUI

struct PeopleView: View {
    @Environment(\.dismiss) private var dismiss
    private let api: APIClient
    @State private var model: PeopleDirectoryModel
    @State private var search = ""

    init(api: APIClient) {
        self.api = api
        _model = State(initialValue: PeopleDirectoryModel(api: api))
    }

    private var filteredPeople: [PersonDTO] {
        guard !search.isEmpty else { return model.people }
        return model.people.filter { person in
            person.displayName.localizedCaseInsensitiveContains(search)
                || person.slug.localizedCaseInsensitiveContains(search)
                || person.aliases.contains { $0.localizedCaseInsensitiveContains(search) }
                || (person.relationship?.localizedCaseInsensitiveContains(search) ?? false)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                switch model.phase {
                case .loading:
                    ProgressView("Loading people…")
                        .tint(Palette.brandStrong)
                case .failed(let message):
                    ContentUnavailableView {
                        Label("People aren't available", systemImage: "person.2")
                    } description: {
                        Text(message)
                    } actions: {
                        Button("Try again") { Task { await model.load(force: true) } }
                            .buttonStyle(.borderedProminent)
                            .tint(Palette.brandStrong)
                    }
                case .loaded:
                    if filteredPeople.isEmpty {
                        ContentUnavailableView.search(text: search)
                    } else {
                        List(filteredPeople) { person in
                            NavigationLink(value: person) {
                                PersonRow(person: person)
                            }
                            .listRowBackground(Palette.surfaceRaised)
                            .listRowSeparatorTint(Palette.line)
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                        .refreshable { await model.load(force: true) }
                    }
                }
            }
            .background(Palette.surface.ignoresSafeArea())
            .foregroundStyle(Palette.ink)
            .navigationTitle("People")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .navigationDestination(for: PersonDTO.self) { person in
                PersonDetailView(api: api, person: person)
            }
            .searchable(text: $search, prompt: "Name, nickname, or relationship")
        }
        .tint(Palette.brandStrong)
        .task { await model.load() }
    }
}

private struct PersonRow: View {
    let person: PersonDTO

    var body: some View {
        HStack(spacing: Space.s3) {
            PersonAvatar(name: person.displayName, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(person.displayName)
                    .textStyle(.body, weight: .semibold)
                    .foregroundStyle(Palette.ink)
                if let relationship = person.relationship, !relationship.isEmpty {
                    Text(relationship)
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                } else if !person.aliases.isEmpty {
                    Text(person.aliases.joined(separator: " · "))
                        .textStyle(.caption)
                        .foregroundStyle(Palette.inkMuted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, Space.s2)
        .contentShape(Rectangle())
    }
}

private struct PersonDetailView: View {
    let api: APIClient
    let person: PersonDTO
    @State private var model: PersonDetailModel

    init(api: APIClient, person: PersonDTO) {
        self.api = api
        self.person = person
        _model = State(initialValue: PersonDetailModel(api: api, slug: person.slug))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s4) {
                profileHeader
                switch model.phase {
                case .loading:
                    ProgressView("Loading notes…")
                        .tint(Palette.brandStrong)
                        .frame(maxWidth: .infinity, minHeight: 120)
                case .failed(let message):
                    infoCard(title: "Notes unavailable", symbol: "exclamationmark.circle") {
                        Text(message).foregroundStyle(Palette.inkMuted)
                        Button("Try again") { Task { await model.load() } }
                            .buttonStyle(.borderedProminent)
                            .tint(Palette.brandStrong)
                    }
                case .loaded(let detail):
                    notes(detail)
                }
            }
            .padding(.horizontal, Space.s4)
            .padding(.top, Space.s4)
            .padding(.bottom, Space.s6)
        }
        .scrollIndicators(.hidden)
        .background(Palette.surface.ignoresSafeArea())
        .foregroundStyle(Palette.ink)
        .navigationTitle(person.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private var profileHeader: some View {
        VStack(spacing: Space.s3) {
            PersonAvatar(name: person.displayName, size: 82)
            VStack(spacing: 4) {
                Text(person.displayName).textStyle(.title, weight: .semibold)
                if let relationship = person.relationship, !relationship.isEmpty {
                    Text(relationship)
                        .textStyle(.body)
                        .foregroundStyle(Palette.inkMuted)
                }
            }
            if !person.aliases.isEmpty {
                FlowAliases(aliases: person.aliases)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.s6)
    }

    @ViewBuilder
    private func notes(_ detail: PersonDetailDTO) -> some View {
        if !detail.memoryAvailable {
            infoCard(title: "Notes are offline", symbol: "icloud.slash") {
                Text("Your profile is here, but TinyMe couldn't reach its memory store.")
                    .foregroundStyle(Palette.inkMuted)
            }
        } else if let content = detail.memoryContent, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            infoCard(title: "What I remember", symbol: "text.book.closed") {
                Text(content)
                    .textStyle(.body)
                    .lineSpacing(5)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            infoCard(title: "No notes yet", symbol: "text.book.closed") {
                Text("As you mention \(person.displayName) in chat, useful details can be remembered here.")
                    .foregroundStyle(Palette.inkMuted)
            }
        }
    }

    private func infoCard<Content: View>(title: String, symbol: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            Label(title, systemImage: symbol)
                .textStyle(.heading, weight: .semibold)
            content()
        }
        .padding(Space.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                .stroke(Palette.line, lineWidth: 1)
        }
    }
}

private struct PersonAvatar: View {
    let name: String
    var size: CGFloat = 48

    private var initials: String {
        let parts = name.split(whereSeparator: \.isWhitespace)
        let letters = parts.prefix(2).compactMap(\.first)
        return letters.isEmpty ? "?" : String(letters).uppercased()
    }

    var body: some View {
        Text(initials)
            .font(.system(size: size * 0.34, weight: .semibold, design: .rounded))
            .foregroundStyle(Palette.brandStrong)
            .frame(width: size, height: size)
            .background(Palette.brandSoft, in: Circle())
            .accessibilityHidden(true)
    }
}

private struct FlowAliases: View {
    let aliases: [String]

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Space.s2) {
                ForEach(aliases, id: \.self) { alias in
                    Text(alias)
                        .textStyle(.caption, weight: .medium)
                        .foregroundStyle(Palette.inkMuted)
                        .padding(.horizontal, Space.s3)
                        .padding(.vertical, 6)
                        .background(Palette.surfaceSunken, in: Capsule())
                }
            }
        }
        .scrollIndicators(.hidden)
    }
}
