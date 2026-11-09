import SwiftUI

struct HistoryScreen: View {
    @Environment(ProfileStore.self) private var profile

    var body: some View {
        AdaptiveSeriesNavigation {
            HistoryList()
        }
        .task(id: profile.scope) {
            await profile.sync(force: true)
            // Posters of the series in Continue stay available offline.
            for entry in profile.entries {
                await PosterCache.prefetch(entry.imageUrl)
            }
        }
    }
}

/// Where a series stands for the viewer, from its history entry and the
/// relay's episode counts for the Sub/Dub kind they were watching.
private enum ContinueState {
    case inProgress
    /// The finished episode has successors of the same kind.
    case newEpisodes(count: Int)
    /// The series is complete and the watch order has a season not started yet.
    case nextSeason(Season)
    /// Watched up to the newest episode of a series that is still airing,
    /// with the estimated date of the next one when known.
    case caughtUp(next: Date?)
    case completed

    /// New episodes first, then in progress, caught up and completed.
    var section: Int {
        switch self {
        case .newEpisodes: return 0
        case .inProgress, .nextSeason: return 1
        case .caughtUp: return 2
        case .completed: return 3
        }
    }
}

private struct HistoryList: View {
    @Environment(ProfileStore.self) private var profile
    @Environment(\.openSeries) private var openSeries
    @Environment(\.openedSeriesURL) private var openedSeriesURL
    @State private var editMode: EditMode = .inactive
    @State private var selectedEntryIDs = Set<WatchHistoryEntry.ID>()
    @State private var showBulkDeleteConfirmation = false
    @State private var showRemote = false
    /// Folded sections (comma-separated keys); Completed starts folded.
    @AppStorage("relaycast.continue.folded") private var foldedSections = "completed"
    /// Relay episode counts by catalog path; only finished entries need them.
    @State private var statuses: [String: SeriesStatus] = [:]

    private var isEditing: Bool { editMode.isEditing }
    private var entries: [WatchHistoryEntry] { profile.entries }
    private var finishedURLs: [String] { entries.filter(\.isCompleted).map(\.watchUrl) }

    var body: some View {
        List(selection: $selectedEntryIDs) {
            if !profile.isOnline {
                Section {
                    Label("Sin conexión. Tu progreso se guarda en este dispositivo y se sincroniza con el relay al volver la conexión. Sin red puedes ver los episodios descargados.", systemImage: "wifi.slash")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if !entries.isEmpty {
                let states = Dictionary(entries.map { ($0.id, state(of: $0)) }, uniquingKeysWith: { first, _ in first })
                // Each section folds; Completed always goes last, folded at first.
                section("new", String(localized: "Nuevos episodios"), entries: entries.filter { states[$0.id]?.section == 0 }, states: states)
                section("progress", String(localized: "En curso"), entries: entries.filter { states[$0.id]?.section == 1 }, states: states)
                section("current", String(localized: "Al día"), entries: entries.filter { states[$0.id]?.section == 2 }, states: states, footer: String(localized: "Pasan a «Nuevos episodios» cuando sale el siguiente episodio en Sub o Dub, según lo que estabas viendo."))
                section("completed", String(localized: "Completados"), entries: entries.filter { states[$0.id]?.section == 3 }, states: states)
            }
        }
        // Over the list, not as a row: a row leaves out the icon.
        .overlay {
            if entries.isEmpty {
                ContentUnavailableView("Nada para continuar", systemImage: "play.circle", description: Text("Las series que reproduzcas con esta identidad aparecerán aquí con el segundo en que las dejaste."))
            }
        }
        .task(id: finishedURLs) { await loadStatuses() }
        .environment(\.editMode, $editMode)
        .listStyle(.insetGrouped)
        .navigationTitle("Continuar viendo")
        .modifier(AdaptableTabRootTitleStyle())
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                DevicesButton()
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(isEditing ? String(localized: "Listo") : String(localized: "Editar")) {
                    if isEditing {
                        selectedEntryIDs.removeAll()
                        editMode = .inactive
                    } else {
                        editMode = .active
                    }
                }
                .disabled(entries.isEmpty && !isEditing)
                .accessibilityIdentifier("history.edit")
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isEditing {
                GlassEffectContainer(spacing: 12) {
                    HStack {
                        selectAllButton
                        Spacer(minLength: 12)
                        bulkDeleteButton
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .sheet(isPresented: $showRemote) { TVRemoteSheet() }
        .confirmationDialog(
            "¿Borrar \(selectedEntryIDs.count) series?",
            isPresented: $showBulkDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Borrar \(selectedEntryIDs.count) series", role: .destructive) {
                delete(entries.filter { selectedEntryIDs.contains($0.id) })
            }
        }
        .refreshable {
            await profile.sync(force: true)
            await loadStatuses()
        }
        .onChange(of: entries) { _, updatedEntries in
            selectedEntryIDs.formIntersection(Set(updatedEntries.map(\.id)))
        }
        .onChange(of: isEditing) { _, editing in
            if !editing {
                selectedEntryIDs.removeAll()
            }
        }
    }

    @ViewBuilder
    private func section(_ key: String, _ title: String, entries: [WatchHistoryEntry], states: [WatchHistoryEntry.ID: ContinueState], footer: String? = nil) -> some View {
        let folded = foldedSections.split(separator: ",").contains { $0 == key }
        if !entries.isEmpty {
            Section {
                if !folded {
                ForEach(entries) { entry in
                    let state = states[entry.id] ?? .inProgress
                    let target = series(for: entry, state: state)
                    // While editing, a tap selects the row instead.
                    Button {
                        if !isEditing { openSeries(target) }
                    } label: {
                        HistoryRow(entry: entry, state: state, language: profile.preferences.titleLanguage, showsDisclosure: openedSeriesURL == nil && !isEditing)
                    }
                    .buttonStyle(.plain)
                    .tag(entry.id)
                    // Beside the list, the open series is marked.
                    .listRowBackground(target.watchUrl == openedSeriesURL ? Color.accentColor.opacity(0.25) : nil)
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            delete([entry])
                        } label: {
                            Label("Borrar", systemImage: "trash")
                        }
                    }
                }
                }
            } header: {
                Button {
                    var keys = Set(foldedSections.split(separator: ",").map(String.init))
                    if folded { keys.remove(key) } else { keys.insert(key) }
                    withAnimation { foldedSections = keys.sorted().joined(separator: ",") }
                } label: {
                    HStack {
                        Text(title)
                        Text("\(entries.count)")
                            .foregroundStyle(.secondary)
                        Spacer()
                        Image(systemName: "chevron.down")
                            .rotationEffect(.degrees(folded ? -90 : 0))
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(folded ? Text("Mostrar") : Text("Ocultar"))
            } footer: {
                if let footer, !folded { Text(footer) }
            }
        }
    }

    /// A finished series with a season not started yet opens that season.
    private func series(for entry: WatchHistoryEntry, state: ContinueState) -> Anime {
        if case .nextSeason(let season) = state { return season.anime }
        return entry.anime
    }

    private func state(of entry: WatchHistoryEntry) -> ContinueState {
        guard entry.isCompleted else { return .inProgress }
        let kind = entry.kind ?? profile.preferences.defaultTrack
        let status = statuses[catalogPath(of: entry.watchUrl)]
        let latest = status?.latest(for: kind)
        if let latest, latest > entry.episode { return .newEpisodes(count: latest - entry.episode) }
        let isFinal = isFinalEpisode(entry.episode, totalEpisodes: status?.totalEpisodes ?? entry.totalEpisodes, status: status?.status, latestEpisode: latest)
        guard isFinal else { return .caughtUp(next: status?.nextEpisodeDate) }
        if let next = status?.nextSeason, profile.entry(for: next.watchUrl) == nil { return .nextSeason(next) }
        return .completed
    }

    private func loadStatuses() async {
        let urls = finishedURLs
        guard !urls.isEmpty else { return }
        do {
            let reply: SeriesStatusResponse = try await RelayClient.post(base: profile.relayURL, path: "/api/series/status", body: SeriesStatusRequest(watchUrls: urls))
            var updated = statuses
            for status in reply.statuses {
                updated[catalogPath(of: status.watchUrl)] = status
            }
            statuses = updated
        } catch {
            // Older relays: entries are grouped with their own details.
        }
    }

    private var allEntriesSelected: Bool {
        !entries.isEmpty && selectedEntryIDs.count == entries.count
    }

    private var selectAllButton: some View {
        Button {
            if allEntriesSelected {
                selectedEntryIDs.removeAll()
            } else {
                selectedEntryIDs = Set(entries.map(\.id))
            }
        } label: {
            Label(allEntriesSelected ? String(localized: "Deseleccionar todo") : String(localized: "Seleccionar todo"), systemImage: allEntriesSelected ? "xmark.circle" : "checkmark.circle")
        }
        .buttonStyle(.glass)
        .accessibilityIdentifier("history.selectAll")
        .disabled(entries.isEmpty)
    }

    private var bulkDeleteButton: some View {
        Button(role: .destructive) {
            showBulkDeleteConfirmation = true
        } label: {
            Label("Borrar (\(selectedEntryIDs.count))", systemImage: "trash")
        }
        .buttonStyle(.glassProminent)
        .tint(.red)
        .accessibilityIdentifier("history.deleteSelected")
        .disabled(selectedEntryIDs.isEmpty)
    }

    private func delete(_ entriesToDelete: [WatchHistoryEntry]) {
        guard !entriesToDelete.isEmpty else { return }
        profile.remove(entriesToDelete)
        selectedEntryIDs.subtract(Set(entriesToDelete.map(\.id)))
        Task {
            await profile.sync(force: true)
        }
    }
}

private struct HistoryRow: View {
    let entry: WatchHistoryEntry
    let state: ContinueState
    let language: TitleLanguage
    /// The chevron of a row that pushes the series (one column).
    var showsDisclosure = false

    private var serverLine: String {
        [entry.serverName.isEmpty ? nil : entry.serverName, entry.kind?.displayName].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PosterImage(imageURL: entry.imageUrl, cornerRadius: 8)
                .frame(width: 56)
            VStack(alignment: .leading, spacing: 4) {
                SeriesTitle(title: entry.seriesTitle, titleJp: entry.titleJp, language: language)
                detail
                    .font(.subheadline)
                if !serverLine.isEmpty {
                    Text(serverLine)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            Spacer(minLength: 0)
            if showsDisclosure {
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .frame(maxHeight: .infinity)
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var detail: some View {
        switch state {
        case .inProgress:
            Text("\(entry.episodeLabel) · \(formatTime(entry.positionSeconds))")
                .foregroundStyle(.secondary)
        case .newEpisodes(let count):
            Label(count == 1 ? String(localized: "Nuevo: episodio \(entry.episode + 1)") : String(localized: "\(count) episodios nuevos desde el \(entry.episode + 1)"), systemImage: "sparkles")
                .foregroundStyle(.tint)
        case .nextSeason(let season):
            Label("Nueva temporada: \(season.localizedLabel ?? season.anime.displayTitle(language))", systemImage: "sparkles")
                .foregroundStyle(.tint)
        case .caughtUp(let next):
            Text(next.map { String(localized: "Al día · episodio \(entry.episode) · próximo: \(airingLabel($0))") } ?? String(localized: "Al día · viste el episodio \(entry.episode)"))
                .foregroundStyle(.secondary)
        case .completed:
            Label("Completada · \(entry.episode) episodios", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.secondary)
        }
    }
}
