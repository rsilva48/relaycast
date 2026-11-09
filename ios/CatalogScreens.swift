import Observation
import SwiftUI

/// Catalog filters. Each category takes several values, like the catalog's
/// own filter page: a series matches when it has any of them.
struct CatalogFilters: Equatable {
    var seasons: Set<String> = []
    var statuses: Set<String> = []
    var ratings: Set<String> = []
    var mediaTypes: Set<String> = []
    var years: Set<String> = []
    var genres: Set<String> = []
    var sources: Set<String> = []
    /// Catalog order ("" = the relay's default).
    var sort = ""
    var minEpisodes: Int?
    var maxEpisodes: Int?
    var minimumScore = 0

    /// Filters the relay applies; the minimum score is applied on device.
    var hasServerFilters: Bool {
        !seasons.isEmpty || !statuses.isEmpty || !ratings.isEmpty || !mediaTypes.isEmpty || !years.isEmpty
            || !genres.isEmpty || !sources.isEmpty || !sort.isEmpty || minEpisodes != nil || maxEpisodes != nil
    }
    var isActive: Bool { hasServerFilters || minimumScore > 0 }

    /// Chosen values, shown on the filter button.
    var activeCount: Int {
        let lists = seasons.count + statuses.count + ratings.count + mediaTypes.count + years.count + genres.count + sources.count
        let singles = [!sort.isEmpty, minEpisodes != nil || maxEpisodes != nil, minimumScore > 0].filter { $0 }.count
        return lists + singles
    }

    func queryItems(page: Int, keyword: String? = nil) -> [URLQueryItem] {
        var items = [URLQueryItem(name: "page", value: String(page))]
        if let keyword { items.append(URLQueryItem(name: "q", value: keyword)) }
        let groups: [(name: String, values: Set<String>)] = [
            ("season", seasons), ("status", statuses), ("rating", ratings), ("mediaType", mediaTypes), ("year", years),
            ("genre", genres), ("source", sources),
        ]
        for group in groups {
            for value in group.values.sorted() {
                items.append(URLQueryItem(name: group.name, value: value))
            }
        }
        if !sort.isEmpty { items.append(URLQueryItem(name: "sort", value: sort)) }
        if let minEpisodes { items.append(URLQueryItem(name: "minEpisodes", value: String(minEpisodes))) }
        if let maxEpisodes { items.append(URLQueryItem(name: "maxEpisodes", value: String(maxEpisodes))) }
        return items
    }

    func allows(_ anime: Anime) -> Bool {
        minimumScore == 0 || (anime.score ?? 0) >= Double(minimumScore)
    }
}

/// Which episodes Recents follows: the configured kind by default.
enum CatalogLanguage: String, CaseIterable, Identifiable {
    case sub
    case dub
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .sub: return "Sub"
        case .dub: return "Dub"
        case .all: return String(localized: "Sub y Dub")
        }
    }

    init(_ kind: StreamKind) {
        self = kind == .dub ? .dub : .sub
    }
}

/// Paged catalog results. A new `reset` invalidates replies still in flight.
@MainActor
@Observable
final class CatalogFeed {
    typealias Page = (results: [Anime], hasNext: Bool)

    private(set) var items: [Anime] = []
    private(set) var isLoading = false
    private(set) var hasMore = false
    private(set) var errorMessage: String?
    private(set) var hasLoaded = false
    /// The last failure was a missing network connection.
    private(set) var isOffline = false

    @ObservationIgnored private var page = 0
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var fetch: ((Int) async throws -> Page)?

    func reset(keepingItems: Bool = false, _ fetch: ((Int) async throws -> Page)?) async {
        generation += 1
        self.fetch = fetch
        page = 0
        hasMore = false
        isLoading = false
        errorMessage = nil
        if !keepingItems {
            items = []
            // Without a fetch there is nothing to wait for, so the empty state may show.
            hasLoaded = fetch == nil
        }
        await loadNextPage()
    }

    func loadNextPage() async {
        guard let fetch, !isLoading, page == 0 || hasMore else { return }
        let current = generation
        isLoading = true
        errorMessage = nil
        isOffline = false
        defer { if current == generation { isLoading = false } }
        do {
            let reply = try await fetch(page + 1)
            guard current == generation else { return }
            if page == 0 {
                items = reply.results
            } else {
                let known = Set(items.map(\.id))
                items += reply.results.filter { !known.contains($0.id) }
            }
            page += 1
            hasMore = reply.hasNext
            hasLoaded = true
        } catch is CancellationError {
            return
        } catch {
            guard current == generation else { return }
            errorMessage = error.localizedDescription
            isOffline = (error as? RelayRequestError)?.isOffline ?? false
            hasLoaded = true
        }
    }
}

struct CatalogGrid<EmptyState: View>: View {
    let feed: CatalogFeed
    let filters: CatalogFilters
    let language: TitleLanguage
    var hideAdult = false
    /// Which counters the posters show: Sub, Dub or both (Recientes follows
    /// its selector; Buscar has none and shows both).
    var badges: CatalogLanguage = .all
    /// Shows a filter button above the results (where the navigation bar,
    /// and its toolbar button, can be hidden by the search field).
    var onShowFilters: (() -> Void)? = nil
    @ViewBuilder let emptyState: () -> EmptyState
    @Environment(\.openSeries) private var openSeries

    private var visible: [Anime] {
        feed.items.filter { filters.allows($0) && !(hideAdult && $0.isAdult) }
    }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 108, maximum: 176), spacing: 14, alignment: .top)], alignment: .leading, spacing: 22) {
                ForEach(visible) { anime in
                    Button {
                        openSeries(anime)
                    } label: {
                        PosterCard(anime: anime, language: language, badges: badges)
                    }
                    .buttonStyle(.plain)
                    .onAppear {
                        if anime.id == visible.last?.id {
                            Task { await feed.loadNextPage() }
                        }
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)

            VStack(spacing: 12) {
                if feed.isLoading {
                    ProgressView()
                } else if let errorMessage = feed.errorMessage, !(feed.isOffline && visible.isEmpty) {
                    ErrorNotice(message: errorMessage)
                    Button("Reintentar") { Task { await feed.loadNextPage() } }
                        .buttonStyle(.glass)
                } else if feed.hasMore {
                    // Score filtering can hide a whole page, so keep a manual way forward.
                    Button("Cargar más") { Task { await feed.loadNextPage() } }
                        .buttonStyle(.glass)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
        }
        .overlay {
            if feed.hasLoaded, visible.isEmpty, !feed.isLoading {
                if feed.isOffline {
                    ContentUnavailableView {
                        Label("Sin conexión", systemImage: "wifi.slash")
                    } description: {
                        Text("El catálogo necesita internet. Los episodios descargados siguen disponibles en Descargas, y esta lista se carga sola al volver la conexión.")
                    } actions: {
                        Button("Reintentar") { Task { await feed.loadNextPage() } }
                            .buttonStyle(.glass)
                    }
                } else if feed.errorMessage == nil {
                    emptyState()
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let onShowFilters {
                HStack {
                    Button(action: onShowFilters) {
                        Label(filters.activeCount > 0 ? String(localized: "Filtros (\(filters.activeCount))") : String(localized: "Filtros"), systemImage: filters.isActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                    }
                    .buttonStyle(.glass)
                    Spacer()
                }
                .padding(.horizontal)
                .padding(.vertical, 6)
            }
        }
    }
}

struct PosterCard: View {
    let anime: Anime
    let language: TitleLanguage
    var badges: CatalogLanguage = .all

    /// In Dub, its count with the total ("DUB 3/12"); with both, "DUB 3".
    private var dubBadge: String? {
        guard let dub = anime.dubEpisodes else { return nil }
        if badges == .dub, let total = anime.totalEpisodes, total > dub { return "DUB \(dub)/\(total)" }
        return "DUB \(dub)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PosterImage(imageURL: anime.imageUrl)
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: 4) {
                        // Only the selected kind's counter (both with Sub y Dub).
                        if badges != .dub, let badge = anime.episodeBadge {
                            PosterBadge(text: badge)
                        }
                        if badges != .sub, let dub = dubBadge {
                            PosterBadge(text: dub)
                        }
                    }
                    .padding(6)
                }
                .overlay(alignment: .topTrailing) {
                    if anime.isAdult {
                        AdultBadge()
                            .padding(6)
                    }
                }
            Text(anime.displayTitle(language))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            if !anime.metadataLine.isEmpty {
                Text(anime.metadataLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private struct PosterBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .monospacedDigit()
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(.ultraThinMaterial, in: Capsule())
    }
}

private struct FeedKey: Equatable {
    let scope: String?
    let query: String
    let filters: CatalogFilters
    var language: CatalogLanguage? = nil
    /// Reloads a list that failed offline once the connection returns.
    var online = true
}

struct LatestScreen: View {
    @Environment(ProfileStore.self) private var profile
    @State private var feed = CatalogFeed()
    @State private var filters = CatalogFilters()
    @State private var showFilters = false
    @State private var loadedKey: FeedKey?
    /// Chosen in the toolbar for this session; otherwise the configured kind.
    @State private var languageOverride: CatalogLanguage?

    private var language: CatalogLanguage { languageOverride ?? CatalogLanguage(profile.preferences.defaultTrack) }
    private var key: FeedKey { FeedKey(scope: profile.scope, query: "", filters: filters, language: language, online: profile.isOnline) }

    var body: some View {
        AdaptiveSeriesNavigation {
            CatalogGrid(feed: feed, filters: filters, language: profile.preferences.titleLanguage, hideAdult: profile.preferences.hideAdult, badges: language) {
                ContentUnavailableView("Sin resultados", systemImage: "sparkles", description: filters.isActive ? Text("Cambia o limpia los filtros.") : Text("El catálogo no devolvió novedades."))
            }
            .navigationTitle("Recientes")
            .modifier(AdaptableTabRootTitleStyle())
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("Episodios", selection: Binding(get: { language }, set: { languageOverride = $0 })) {
                            ForEach(CatalogLanguage.allCases) { Text($0.title).tag($0) }
                        }
                    } label: {
                        Label(language.title, systemImage: "captions.bubble")
                            .labelStyle(.titleAndIcon)
                    }
                    .accessibilityLabel("Episodios: \(language.title)")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    DevicesButton()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    FiltersButton(isActive: filters.isActive) { showFilters = true }
                }
            }
            .sheet(isPresented: $showFilters) { CatalogFilterSheet(filters: $filters) }
            .task(id: key) {
                // Tab switches re-run the task; only reload when the inputs changed.
                guard key != loadedKey else { return }
                await reload(keepingItems: false)
                if !Task.isCancelled, feed.errorMessage == nil { loadedKey = key }
            }
            .refreshable { await reload(keepingItems: true) }
        }
    }

    private func reload(keepingItems: Bool) async {
        let base = profile.relayURL, filters = filters, language = language
        await feed.reset(keepingItems: keepingItems) { page in
            let query = filters.queryItems(page: page) + [URLQueryItem(name: "language", value: language.rawValue)]
            let reply: LatestResponse = try await RelayClient.get(base: base, path: "/api/latest", query: query)
            return (reply.results, reply.hasNext)
        }
    }
}

struct SearchScreen: View {
    /// Changes every time the Search tab is selected.
    let activation: Int
    @Environment(ProfileStore.self) private var profile
    @State private var feed = CatalogFeed()
    @State private var query = ""
    @State private var searchPresented = false
    @State private var handledActivation = -1
    @State private var submittedQuery = ""
    @State private var filters = CatalogFilters()
    @State private var showFilters = false
    @State private var loadedKey: FeedKey?
    @AppStorage("relaycast.recent-searches") private var recentSearchesStorage = ""
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var key: FeedKey { FeedKey(scope: profile.scope, query: submittedQuery, filters: filters, online: profile.isOnline) }

    private var recentSearches: [String] {
        recentSearchesStorage.split(separator: "\n").map(String.init)
    }

    var body: some View {
        AdaptiveSeriesNavigation {
            // On iPhone the search field replaces the navigation bar, so the
            // filter button sits above the results instead of in the toolbar.
            CatalogGrid(
                feed: feed,
                filters: filters,
                language: profile.preferences.titleLanguage,
                hideAdult: profile.preferences.hideAdult,
                onShowFilters: horizontalSizeClass == .compact ? { showFilters = true } : nil
            ) {
                if submittedQuery.isEmpty {
                    ContentUnavailableView("Busca una serie", systemImage: "magnifyingglass", description: Text("Escribe un título y pulsa Buscar. Los filtros también se aplican a la búsqueda."))
                } else {
                    ContentUnavailableView.search(text: submittedQuery)
                }
            }
            .navigationTitle("Buscar")
            .modifier(AdaptableTabRootTitleStyle())
            .searchable(text: $query, isPresented: $searchPresented, prompt: "Título de la serie")
            .task(id: activation) {
                // Focus the field when the tab is selected, but not when the
                // screen reappears after going back from a series.
                guard activation != handledActivation else { return }
                handledActivation = activation
                try? await Task.sleep(for: .milliseconds(150))
                guard !Task.isCancelled else { return }
                searchPresented = true
            }
            .searchSuggestions {
                if query.isEmpty {
                    ForEach(recentSearches, id: \.self) { recent in
                        Label(recent, systemImage: "clock.arrow.circlepath")
                            .searchCompletion(recent)
                    }
                }
            }
            .onSubmit(of: .search, submit)
            .toolbar {
                if horizontalSizeClass != .compact {
                    ToolbarItem(placement: .topBarTrailing) {
                        FiltersButton(isActive: filters.isActive) { showFilters = true }
                    }
                }
            }
            .sheet(isPresented: $showFilters) { CatalogFilterSheet(filters: $filters) }
            .task(id: key) {
                guard key != loadedKey else { return }
                await runSearch()
                if !Task.isCancelled, feed.errorMessage == nil { loadedKey = key }
            }
            .refreshable { await runSearch() }
        }
    }

    private func submit() {
        let value = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        if value == submittedQuery {
            // Same key, so the task will not re-run: search again explicitly.
            Task { await runSearch() }
        }
        submittedQuery = value
        let updated = [value] + recentSearches.filter { $0.caseInsensitiveCompare(value) != .orderedSame }
        recentSearchesStorage = updated.prefix(8).joined(separator: "\n")
    }

    private func runSearch() async {
        let keyword = submittedQuery, base = profile.relayURL, filters = filters
        guard !keyword.isEmpty else {
            await feed.reset(nil)
            return
        }
        // The catalog's filter page, paged: its quick search returns only the
        // first five matches.
        await feed.reset { page in
            let reply: LatestResponse = try await RelayClient.get(base: base, path: "/api/latest", query: filters.queryItems(page: page, keyword: keyword))
            return (reply.results, reply.hasNext)
        }
    }
}

private struct FiltersButton: View {
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("Filtros", systemImage: isActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
    }
}

struct FilterOption: Hashable {
    let tag: String
    let label: String
}

/// Option lists published by the relay (`GET /api/catalog/filters`): the
/// genre ids belong to the catalog. Older relays answer 404; their
/// filters fall back to the built-in lists and genres are not offered.
struct CatalogFilterOptions: Decodable {
    struct Option: Decodable {
        let id: String
        /// Spanish label; `labelEn` is the English one (newer relays).
        let label: String
        var labelEn: String?
        var adult: Bool?

        /// The label in the app's language.
        var displayLabel: String {
            AppLanguage.isSpanish ? label : (labelEn ?? label)
        }
    }

    var genres: [Option] = []
    var mediaTypes: [Option] = []
    var sources: [Option] = []
    var sorts: [Option] = []

    @MainActor private static var cached: (base: String, options: CatalogFilterOptions)?

    @MainActor
    static func load(base: String) async -> CatalogFilterOptions? {
        if let cached, cached.base == base { return cached.options }
        guard let options: CatalogFilterOptions = try? await RelayClient.get(base: base, path: "/api/catalog/filters", timeoutInterval: 10),
              !options.genres.isEmpty else { return nil }
        cached = (base, options)
        return options
    }
}

enum FilterOptions {
    static let seasons = [
        FilterOption(tag: "winter", label: String(localized: "Invierno")),
        FilterOption(tag: "spring", label: String(localized: "Primavera")),
        FilterOption(tag: "summer", label: String(localized: "Verano")),
        FilterOption(tag: "fall", label: String(localized: "Otoño")),
    ]
    static let statuses = [
        FilterOption(tag: "currently-airing", label: String(localized: "En emisión")),
        FilterOption(tag: "finished-airing", label: String(localized: "Finalizado")),
        FilterOption(tag: "not-yet-aired", label: String(localized: "Próximamente")),
    ]
    static let ratings = ["G", "PG", "PG-13", "R", "R+", "Rx"].map { FilterOption(tag: $0, label: $0) }
    static let mediaTypes = ["TV", "Movie", "ONA", "OVA", "Special"].map { FilterOption(tag: $0, label: $0) }
    static var years: [FilterOption] {
        let next = Calendar.current.component(.year, from: Date()) + 1
        return (1980...next).reversed().map { FilterOption(tag: String($0), label: String($0)) }
    }
}

struct CatalogFilterSheet: View {
    @Binding var filters: CatalogFilters
    @State private var draft: CatalogFilters
    @State private var options: CatalogFilterOptions?
    @Environment(ProfileStore.self) private var profile
    @Environment(\.dismiss) private var dismiss

    private func choices(_ list: [CatalogFilterOptions.Option]) -> [FilterOption] {
        list.filter { !(profile.preferences.hideAdult && $0.adult == true) }.map { FilterOption(tag: $0.id, label: $0.displayLabel) }
    }

    private func episodeBinding(_ keyPath: WritableKeyPath<CatalogFilters, Int?>) -> Binding<String> {
        Binding(
            get: { draft[keyPath: keyPath].map(String.init) ?? "" },
            set: { text in
                let digits = text.filter(\.isNumber).prefix(5)
                draft[keyPath: keyPath] = Int(digits).flatMap { $0 > 0 ? $0 : nil }
            }
        )
    }

    // The draft starts from the applied filters once. Copying them in
    // onAppear lost the choices: the form appears again on every return
    // from a checklist.
    init(filters: Binding<CatalogFilters>) {
        _filters = filters
        _draft = State(initialValue: filters.wrappedValue)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let options {
                        MultiSelectLink(title: String(localized: "Género"), options: choices(options.genres), selection: $draft.genres)
                    }
                    MultiSelectLink(title: String(localized: "Temporada"), options: FilterOptions.seasons, selection: $draft.seasons)
                    MultiSelectLink(title: String(localized: "Año"), options: FilterOptions.years, selection: $draft.years)
                    MultiSelectLink(title: String(localized: "Estado"), options: FilterOptions.statuses, selection: $draft.statuses)
                    MultiSelectLink(title: String(localized: "Tipo"), options: options.map { choices($0.mediaTypes) } ?? FilterOptions.mediaTypes, selection: $draft.mediaTypes)
                    if let options {
                        MultiSelectLink(title: String(localized: "Origen"), options: choices(options.sources), selection: $draft.sources)
                    }
                    MultiSelectLink(title: String(localized: "Clasificación"), options: FilterOptions.ratings, selection: $draft.ratings)
                    Picker("Puntuación mínima", selection: $draft.minimumScore) {
                        Text("Cualquiera").tag(0)
                        ForEach([5, 6, 7, 8, 9], id: \.self) { Text("\($0).0+").tag($0) }
                    }
                } footer: {
                    Text("Puedes elegir varias opciones en cada filtro: aparecen las series que cumplan cualquiera de ellas. La puntuación mínima se aplica a los resultados ya cargados.")
                }
                if let options {
                    Section("Orden y episodios") {
                        Picker("Ordenar por", selection: $draft.sort) {
                            Text("Predeterminado").tag("")
                            ForEach(options.sorts, id: \.id) { Text($0.displayLabel).tag($0.id) }
                        }
                        LabeledContent("Episodios mínimo") {
                            TextField("Cualquiera", text: episodeBinding(\.minEpisodes))
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                        }
                        LabeledContent("Episodios máximo") {
                            TextField("Cualquiera", text: episodeBinding(\.maxEpisodes))
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                        }
                    }
                }
                Section {
                    Button("Limpiar filtros", role: .destructive) { draft = CatalogFilters() }
                        .disabled(!draft.isActive)
                }
            }
            .navigationTitle("Filtros")
            .navigationBarTitleDisplayMode(.inline)
            .task { options = await CatalogFilterOptions.load(base: profile.relayURL) }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Aplicar") {
                        filters = draft
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A filter row that opens a checklist; several values can be chosen.
private struct MultiSelectLink: View {
    let title: String
    let options: [FilterOption]
    @Binding var selection: Set<String>

    private var summary: String {
        let chosen = options.filter { selection.contains($0.tag) }.map { $0.label }
        return chosen.isEmpty ? String(localized: "Todos") : chosen.joined(separator: ", ")
    }

    var body: some View {
        NavigationLink {
            List {
                Section {
                    ForEach(options, id: \.self) { option in
                        Button {
                            if selection.contains(option.tag) {
                                selection.remove(option.tag)
                            } else {
                                selection.insert(option.tag)
                            }
                        } label: {
                            HStack {
                                Text(option.label)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if selection.contains(option.tag) {
                                    Image(systemName: "checkmark")
                                        .fontWeight(.semibold)
                                        .foregroundStyle(.tint)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .accessibilityAddTraits(selection.contains(option.tag) ? [.isSelected] : [])
                    }
                } footer: {
                    Text("Elige una o varias opciones.")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !selection.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Limpiar") { selection.removeAll() }
                    }
                }
            }
        } label: {
            LabeledContent {
                Text(summary)
                    .lineLimit(1)
            } label: {
                Text(title)
            }
        }
    }
}
