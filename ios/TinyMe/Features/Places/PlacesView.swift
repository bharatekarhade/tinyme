import SwiftUI
import MapKit

struct PlacesView: View {
    @Environment(\.dismiss) private var dismiss
    private let api: APIClient
    private let nearbyAlerts: NearbyPlacesAlerts
    private let currentCoordinate: () async -> CLLocationCoordinate2D?
    @State private var model: PlacesModel
    @State private var presentation: PlaceSelection?
    @State private var mode: PlacesMode = .list
    @State private var filter: PlacesFilter = .wantToGo
    @State private var search = ""
    @State private var alertsEnabled: Bool
    @State private var showingAlertsMessage = false
    @State private var removeFailure: String?

    init(api: APIClient, nearbyAlerts: NearbyPlacesAlerts,
         currentCoordinate: @escaping () async -> CLLocationCoordinate2D? = { nil }) {
        self.api = api
        self.nearbyAlerts = nearbyAlerts
        self.currentCoordinate = currentCoordinate
        _model = State(initialValue: PlacesModel(api: api))
        _alertsEnabled = State(initialValue: nearbyAlerts.isEnabled)
    }

    private var visiblePlaces: [PlaceDTO] {
        model.places.filter { place in
            (filter == .all || place.visited == (filter == .visited))
                && (search.isEmpty || place.name.localizedCaseInsensitiveContains(search)
                    || (place.area?.localizedCaseInsensitiveContains(search) ?? false)
                    || (place.note?.localizedCaseInsensitiveContains(search) ?? false))
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: Space.s3) {
                Picker("View", selection: $mode) {
                    ForEach(PlacesMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, Space.s4)
                .padding(.top, Space.s2)

                nearbyAlertsControl

                if mode == .list {
                    Picker("Places", selection: $filter) {
                        ForEach(PlacesFilter.allCases) { filter in Text(filter.title).tag(filter) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, Space.s4)
                    listContent
                } else {
                    mapContent
                }
            }
            .background(Palette.surface.ignoresSafeArea())
            .foregroundStyle(Palette.ink)
            .navigationTitle("Places")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
            }
            .searchable(text: $search, prompt: "Name, area, or note")
            .onChange(of: alertsEnabled) { _, enabled in
                Task {
                    if enabled {
                        if !(await nearbyAlerts.enable(api: api)) {
                            alertsEnabled = false
                            showingAlertsMessage = true
                        }
                    } else {
                        await nearbyAlerts.disable()
                    }
                }
            }
            .alert("Nearby alerts", isPresented: $showingAlertsMessage) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(nearbyAlerts.message ?? "Nearby alerts couldn't be enabled.")
            }
            .task {
                let coordinate = await currentCoordinate()
                await model.load(near: coordinate)
            }
            .alert("Couldn't remove it", isPresented: Binding(get: { removeFailure != nil }, set: { if !$0 { removeFailure = nil } })) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(removeFailure ?? "")
            }
            .sheet(item: $presentation, onDismiss: {
                Task {
                    await model.load()
                    await nearbyAlerts.refresh(api: api)
                }
            }) { selection in
                PlaceDetailSheet(api: api, placeId: selection.id) {
                    Task { await model.load() }
                } onRemoved: {
                    presentation = nil
                    Task { await model.load() }
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
        .tint(Palette.brandStrong)
    }

    private var nearbyAlertsControl: some View {
        HStack(spacing: Space.s3) {
            Image(systemName: "location.circle.fill")
                .font(.system(size: 22))
                .foregroundStyle(Palette.brandStrong)
            VStack(alignment: .leading, spacing: 2) {
                Text("Nearby place alerts").textStyle(.body, weight: .semibold)
                Text("A quiet nudge when you pass a saved place")
                    .textStyle(.caption).foregroundStyle(Palette.inkMuted)
            }
            Spacer()
            Toggle("Nearby place alerts", isOn: $alertsEnabled).labelsHidden()
                .tint(Palette.brandStrong)
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s2)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md))
        .padding(.horizontal, Space.s4)
    }

    @ViewBuilder
    private var listContent: some View {
        switch model.phase {
        case .loading:
            ProgressView("Loading places…").tint(Palette.brandStrong).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            ContentUnavailableView {
                Label("Places aren't available", systemImage: "mappin.and.ellipse")
            } description: { Text(message) } actions: {
                Button("Try again") { Task { await model.load() } }.buttonStyle(.borderedProminent).tint(Palette.brandStrong)
            }
        case .loaded:
            if visiblePlaces.isEmpty {
                ContentUnavailableView(search.isEmpty ? "No saved places" : "No matching places",
                                       systemImage: "mappin.and.ellipse",
                                       description: Text(search.isEmpty ? "Save a place from chat and it will appear here." : "Try another search."))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(visiblePlaces) { place in
                    Button { presentation = PlaceSelection(id: place.id) } label: { PlaceRow(place: place) }
                        .buttonStyle(.plain)
                        .listRowBackground(Palette.surfaceRaised)
                        .listRowSeparatorTint(Palette.line)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                Task {
                                    do { try await model.remove(place) } catch { removeFailure = "\(place.name) is still saved. Try again." }
                                }
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                            Button { Task { await model.setVisited(place, !place.visited); await model.load() } } label: {
                                Label(place.visited ? "Want to go" : "Visited", systemImage: place.visited ? "bookmark" : "checkmark")
                            }
                            .tint(Palette.brandStrong)
                        }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .refreshable { await model.load(query: search) }
            }
        }
    }

    private var mapContent: some View {
        PlacesMap(places: visiblePlaces) { place in presentation = PlaceSelection(id: place.id) }
            .overlay(alignment: .bottom) {
                HStack(spacing: Space.s2) {
                    Image(systemName: "map")
                    Text("Tap a pin to open a saved place")
                }
                .textStyle(.caption, weight: .medium)
                .foregroundStyle(Palette.ink)
                .padding(.horizontal, Space.s4)
                .padding(.vertical, Space.s2)
                .background(Palette.surfaceRaised.opacity(0.96), in: Capsule())
                .padding(.bottom, Space.s4)
            }
    }
}

private struct PlaceRow: View {
    let place: PlaceDTO

    var body: some View {
        HStack(spacing: Space.s3) {
            Image(systemName: place.visited ? "checkmark.circle.fill" : "mappin.circle.fill")
                .font(.system(size: 25, weight: .medium))
                .foregroundStyle(place.visited ? Palette.brandStrong : Palette.accent)
                .frame(width: 42, height: 42)
                .background(Palette.surfaceSunken, in: RoundedRectangle(cornerRadius: Radius.md))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: Space.s2) {
                    Text(place.name).textStyle(.body, weight: .semibold).foregroundStyle(Palette.ink).lineLimit(1)
                    if let distance = place.distanceM {
                        Text(Self.distance(distance)).textStyle(.caption, weight: .medium).foregroundStyle(Palette.inkMuted)
                    }
                }
                if let detail = [place.area, place.note].compactMap({ $0 }).first(where: { !$0.isEmpty }) {
                    Text(detail).textStyle(.caption).foregroundStyle(Palette.inkMuted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.inkMuted)
        }
        .padding(.vertical, Space.s2)
        .contentShape(Rectangle())
    }

    private static func distance(_ metres: Double) -> String {
        metres < 1000 ? "\(Int(metres.rounded())) m" : String(format: "%.1f km", metres / 1000)
    }
}

private struct PlacesMap: View {
    let places: [PlaceDTO]
    let onSelect: (PlaceDTO) -> Void
    @State private var camera: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $camera) {
            UserAnnotation()
            ForEach(places) { place in
                Annotation(place.name, coordinate: CLLocationCoordinate2D(latitude: place.lat, longitude: place.lng), anchor: .bottom) {
                    Button { onSelect(place) } label: {
                        Image(systemName: place.visited ? "checkmark.circle.fill" : "mappin.circle.fill")
                            .font(.system(size: 30, weight: .semibold))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, place.visited ? Palette.brandStrong : Palette.accent)
                            .shadow(color: .black.opacity(0.2), radius: 3, y: 2)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(place.name)
                }
            }
        }
        .mapStyle(.standard(elevation: .flat))
        .mapControls {
            MapUserLocationButton()
        }
        .clipShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .padding(.horizontal, Space.s4)
        .padding(.bottom, Space.s4)
    }
}

struct PlaceDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    let api: APIClient
    let placeId: String
    let onChanged: () -> Void
    let onRemoved: () -> Void
    @State private var place: PlaceDTO?
    @State private var note = ""
    @State private var phase: String?
    @State private var isSaving = false

    var body: some View {
        NavigationStack {
            Group {
                if let place {
                    ScrollView {
                        VStack(alignment: .leading, spacing: Space.s4) {
                            PlaceInfoSection(name: place.name,
                                             coordinate: CLLocationCoordinate2D(latitude: place.lat, longitude: place.lng))

                            VStack(alignment: .leading, spacing: Space.s2) {
                                Text(place.name).textStyle(.title, weight: .semibold)
                                if let address = place.address { Label(address, systemImage: "mappin.and.ellipse").textStyle(.caption).foregroundStyle(Palette.inkMuted) }
                                if let category = place.category, !category.isEmpty { Text(category.replacingOccurrences(of: "_", with: " ").capitalized).textStyle(.caption).foregroundStyle(Palette.inkMuted) }
                            }
                            .foregroundStyle(Palette.ink)

                            VStack(alignment: .leading, spacing: Space.s2) {
                                Text("Note").textStyle(.label, weight: .semibold).foregroundStyle(Palette.inkMuted)
                                TextField("Why you want to go", text: $note, axis: .vertical)
                                    .lineLimit(2...4)
                                    .textStyle(.body)
                                    .padding(Space.s3)
                                    .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md))
                                Button("Save note") { Task { await saveNote() } }
                                    .buttonStyle(.borderedProminent).tint(Palette.brandStrong).disabled(isSaving)
                            }

                            Toggle(isOn: Binding(get: { place.visited }, set: { value in Task { await setVisited(value) } })) {
                                Label("I've been here", systemImage: "checkmark.circle")
                                    .textStyle(.body, weight: .medium)
                            }
                            .tint(Palette.brandStrong)
                            .padding(Space.s3)
                            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: Radius.md))

                            HStack(spacing: Space.s3) {
                                Button { openInMaps(place, directions: false) } label: { Label("Open in Maps", systemImage: "map") }
                                    .buttonStyle(.borderedProminent).tint(Palette.ink)
                                Button { openInMaps(place, directions: true) } label: { Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond") }
                                    .buttonStyle(.bordered).tint(Palette.ink)
                            }
                            .frame(maxWidth: .infinity)

                            Button(role: .destructive) { Task { await remove(place) } } label: {
                                Label("Remove saved place", systemImage: "trash")
                                    .frame(maxWidth: .infinity).frame(height: 48)
                            }
                            .buttonStyle(.bordered)
                            if let phase { Text(phase).textStyle(.caption).foregroundStyle(Palette.danger) }
                        }
                        .padding(Space.s4)
                    }
                } else {
                    ProgressView("Loading place…").tint(Palette.brandStrong)
                }
            }
            .background(Palette.surface.ignoresSafeArea())
            .navigationTitle(place?.name ?? "Place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
            }
            .task { await load() }
        }
        .tint(Palette.brandStrong)
    }

    private func load() async {
        do {
            let loaded: PlaceDTO = try await api.get(.place(placeId))
            place = loaded
            note = loaded.note ?? ""
        } catch { phase = "This place is no longer available." }
    }

    private func saveNote() async {
        guard place != nil else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            let updated: PlaceDTO = try await api.send(.updatePlace(placeId), body: PlacePatchRequest(note: note))
            place = updated
            note = updated.note ?? ""
            onChanged()
        } catch { phase = "Couldn't save the note." }
    }

    private func setVisited(_ visited: Bool) async {
        do {
            let updated: PlaceDTO = try await api.send(.updatePlace(placeId), body: PlacePatchRequest(visited: visited))
            place = updated
            onChanged()
        } catch { phase = "Couldn't update this place." }
    }

    private func remove(_ place: PlaceDTO) async {
        do {
            try await api.send(.deletePlace(place.id))
            onRemoved()
            dismiss()
        } catch { phase = "Couldn't remove this place." }
    }

    private func openInMaps(_ place: PlaceDTO, directions: Bool) {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: place.lat, longitude: place.lng)))
        item.name = place.name
        if directions {
            MKMapItem.openMaps(with: [MKMapItem.forCurrentLocation(), item], launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
        } else {
            item.openInMaps()
        }
    }
}

struct PlaceCandidateDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    let candidate: PlaceCandidateDTO

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s4) {
                    if let latitude = candidate.latitude, let longitude = candidate.longitude {
                        PlaceInfoSection(name: candidate.name,
                                         coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
                    } else {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(Palette.brandStrong)
                            .frame(maxWidth: .infinity)
                            .frame(height: 120)
                            .background(Palette.brandSoft, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
                    }

                    VStack(alignment: .leading, spacing: Space.s2) {
                        Text(candidate.name).textStyle(.title, weight: .semibold)
                        if let address = candidate.address {
                            Label(address, systemImage: "mappin.and.ellipse")
                                .textStyle(.caption).foregroundStyle(Palette.inkMuted)
                        }
                        if let category = candidate.category, !category.isEmpty {
                            Label(category.replacingOccurrences(of: "_", with: " ").capitalized, systemImage: "tag")
                                .textStyle(.caption).foregroundStyle(Palette.inkMuted)
                        }
                        if let distance = candidate.distanceLabel {
                            Label(distance, systemImage: "location")
                                .textStyle(.caption).foregroundStyle(Palette.inkMuted)
                        }
                    }
                    .foregroundStyle(Palette.ink)

                    if candidate.latitude != nil, candidate.longitude != nil {
                        HStack(spacing: Space.s3) {
                            Button { openInMaps(directions: false) } label: { Label("Open in Maps", systemImage: "map") }
                                .buttonStyle(.borderedProminent).tint(Palette.ink)
                            Button { openInMaps(directions: true) } label: { Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond") }
                                .buttonStyle(.bordered).tint(Palette.ink)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    if let url = candidate.url {
                        Link(destination: url) {
                            Label("Website", systemImage: "safari")
                                .frame(maxWidth: .infinity).frame(height: 46)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(Space.s4)
            }
            .background(Palette.surface.ignoresSafeArea())
            .navigationTitle("Place option")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
            }
        }
        .tint(Palette.brandStrong)
    }

    private func openInMaps(directions: Bool) {
        guard let latitude = candidate.latitude, let longitude = candidate.longitude else { return }
        let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude)))
        item.name = candidate.name
        if directions {
            MKMapItem.openMaps(with: [MKMapItem.forCurrentLocation(), item], launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDefault])
        } else {
            item.openInMaps()
        }
    }
}

private enum PlacesMode: String, CaseIterable, Identifiable {
    case list, map
    var id: Self { self }
    var title: String { rawValue.capitalized }
}

private enum PlacesFilter: String, CaseIterable, Identifiable {
    case wantToGo, visited, all
    var id: Self { self }
    var title: String { switch self { case .wantToGo: "Want to go"; case .visited: "Visited"; case .all: "All" } }
}

private struct PlaceSelection: Identifiable {
    let id: String
}

private struct PlacePatchRequest: Encodable, Sendable {
    var note: String? = nil
    var visited: Bool? = nil
}
