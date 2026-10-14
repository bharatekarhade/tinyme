import Foundation
import Observation

struct PersonDTO: Decodable, Sendable, Hashable, Identifiable {
    let slug: String
    let displayName: String
    let aliases: [String]
    let relationship: String?

    var id: String { slug }

    private enum CodingKeys: String, CodingKey {
        case slug, aliases, relationship
        case displayName = "display_name"
    }
}

struct PersonDetailDTO: Decodable, Sendable, Hashable {
    let person: PersonDTO
    let memoryContent: String?
    let memoryAvailable: Bool
    let recentEntries: [JSONValue]

    private enum CodingKeys: String, CodingKey {
        case person
        case memoryContent = "memory_content"
        case memoryAvailable = "memory_available"
        case recentEntries = "recent_entries"
    }
}

@MainActor
@Observable
final class PeopleDirectoryModel {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    private(set) var people: [PersonDTO] = []
    private(set) var phase: Phase = .loading

    let api: APIClient
    private var hasLoaded = false

    init(api: APIClient) {
        self.api = api
    }

    func load(force: Bool = false) async {
        guard force || !hasLoaded else { return }
        if people.isEmpty { phase = .loading }
        do {
            people = try await api.get(.people)
            phase = .loaded
            hasLoaded = true
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }
}

@MainActor
@Observable
final class PersonDetailModel {
    enum Phase: Equatable {
        case loading
        case loaded(PersonDetailDTO)
        case failed(String)
    }

    private(set) var phase: Phase = .loading
    private(set) var photos: [PhotoDTO] = []
    private(set) var photosError: String?
    private(set) var isUploadingPhoto = false
    private let api: APIClient
    private let slug: String

    init(api: APIClient, slug: String) {
        self.api = api
        self.slug = slug
    }

    func load() async {
        phase = .loading
        async let detailRequest: PersonDetailDTO = api.get(.person(slug))
        async let photosRequest: [PhotoDTO] = api.get(.personPhotos(slug))
        do { phase = .loaded(try await detailRequest) }
        catch { phase = .failed(error.localizedDescription) }
        do { photos = try await photosRequest; photosError = nil }
        catch { photosError = error.localizedDescription }
    }

    func uploadPhoto(_ data: Data) async {
        guard !isUploadingPhoto else { return }
        isUploadingPhoto = true
        defer { isUploadingPhoto = false }
        do {
            let photo: PhotoDTO = try await api.sendMultipart(
                .attachPersonPhoto(slug), fileData: data, fileName: "photo.jpg", contentType: "image/jpeg"
            )
            photos.insert(photo, at: 0)
            photosError = nil
        } catch {
            photosError = error.localizedDescription
        }
    }

    func setPhotoError(_ message: String) {
        photosError = message
    }
}
