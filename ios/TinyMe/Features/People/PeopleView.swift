import SwiftUI
import PhotosUI
import UIKit

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
    @State private var photoSelection: PhotosPickerItem?

    init(api: APIClient, person: PersonDTO) {
        self.api = api
        self.person = person
        _model = State(initialValue: PersonDetailModel(api: api, slug: person.slug))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s4) {
                profileHeader
                photoGallery
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
        .onChange(of: photoSelection) { _, selection in
            guard let selection else { return }
            Task {
                guard let data = try? await selection.loadTransferable(type: Data.self),
                      let jpeg = Self.optimizedJPEG(data) else {
                    model.setPhotoError("Couldn't read that photo. Try choosing another one.")
                    photoSelection = nil
                    return
                }
                await model.uploadPhoto(jpeg)
                photoSelection = nil
            }
        }
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

    private var photoGallery: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            HStack {
                Label("Photos", systemImage: "photo.on.rectangle.angled")
                    .textStyle(.heading, weight: .semibold)
                Spacer()
                PhotosPicker(selection: $photoSelection, matching: .images, photoLibrary: .shared()) {
                    Label("Add", systemImage: "plus")
                        .font(.system(size: 14, weight: .semibold))
                }
                .disabled(model.isUploadingPhoto)
                .accessibilityLabel("Add a photo of \(person.displayName)")
                if model.isUploadingPhoto {
                    ProgressView().tint(Palette.brandStrong)
                }
            }

            if model.photos.isEmpty {
                HStack(spacing: Space.s3) {
                    Image(systemName: "photo.badge.plus")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(Palette.brandStrong)
                        .frame(width: 44, height: 44)
                        .background(Palette.brandSoft, in: RoundedRectangle(cornerRadius: Radius.sm))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Add a shared memory")
                            .textStyle(.label, weight: .semibold)
                            .foregroundStyle(Palette.ink)
                        Text("Choose a photo from your library")
                            .textStyle(.caption)
                            .foregroundStyle(Palette.inkMuted)
                    }
                    Spacer()
                }
                .padding(Space.s3)
                .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
            } else {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: Space.s2), GridItem(.flexible(), spacing: Space.s2)], spacing: Space.s2) {
                    ForEach(model.photos) { photo in
                        PersonPhotoTile(api: api, slug: person.slug, photo: photo)
                    }
                }
            }
            if let error = model.photosError {
                Text(error)
                    .textStyle(.caption)
                    .foregroundStyle(Palette.danger)
            }
        }
        .padding(Space.s4)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.lg, style: .continuous)
                .stroke(Palette.line, lineWidth: 1)
        }
    }

    private static func optimizedJPEG(_ data: Data) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let longestSide = max(image.size.width, image.size.height)
        let scale = min(1, 2048 / max(longestSide, 1))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            .jpegData(compressionQuality: 0.82)
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

private struct PersonPhotoTile: View {
    let api: APIClient
    let slug: String
    let photo: PhotoDTO
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Rectangle()
                        .fill(Palette.surfaceSunken)
                        .overlay {
                            Image(systemName: "photo")
                                .font(.system(size: 24))
                                .foregroundStyle(Palette.inkMuted)
                        }
                }
            }
            .frame(height: 150)
            .frame(maxWidth: .infinity)
            .clipped()
            if let caption = photo.caption, !caption.isEmpty {
                Text(caption)
                    .textStyle(.caption, weight: .semibold)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .padding(Space.s2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.linearGradient(colors: [.clear, .black.opacity(0.68)], startPoint: .top, endPoint: .bottom))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .task(id: photo.id) {
            guard image == nil else { return }
            guard let data = try? await api.getData(.personPhotoContent(slug, id: photo.id)) else { return }
            image = UIImage(data: data)
        }
        .accessibilityLabel(photo.caption ?? "Photo of \(slug)")
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
