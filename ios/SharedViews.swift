import CryptoKit
import SwiftUI
import UIKit

/// A stable file name for a URL or path.
func cacheFileName(_ text: String) -> String {
    SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
}

/// Posters kept on disk (Application Support) so Continue, Downloads and the
/// series screen show them offline. Files unused for 60 days are pruned.
enum PosterCache {
    private static let memory = NSCache<NSString, UIImage>()

    static var folder: URL { URL.applicationSupportDirectory.appendingPathComponent("Posters", isDirectory: true) }

    private static func file(for url: URL) -> URL {
        folder.appendingPathComponent(cacheFileName(url.absoluteString))
    }

    /// A poster already in memory or on disk.
    static func cachedImage(for url: URL) -> UIImage? {
        let key = url.absoluteString as NSString
        if let image = memory.object(forKey: key) { return image }
        let file = file(for: url)
        guard let image = UIImage(contentsOfFile: file.path) else { return nil }
        memory.setObject(image, forKey: key)
        // Marks it as used for pruning.
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return image
    }

    /// The cached poster, or downloads and stores it; nil offline.
    static func image(for url: URL) async -> UIImage? {
        if let cached = cachedImage(for: url) { return cached }
        guard let result = try? await URLSession.shared.data(from: url),
              (result.1 as? HTTPURLResponse)?.statusCode == 200,
              let image = UIImage(data: result.0) else { return nil }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? result.0.write(to: file(for: url), options: .atomic)
        memory.setObject(image, forKey: url.absoluteString as NSString)
        return image
    }

    /// Stores a poster ahead of time (series in Continue and downloads).
    static func prefetch(_ value: String?) async {
        guard let value, let url = URL(string: value) else { return }
        _ = await image(for: url)
    }

    static func prune(olderThanDays days: Double = 60) {
        pruneFiles(in: folder, olderThanDays: days)
    }
}

func pruneFiles(in folder: URL, olderThanDays days: Double) {
    let limit = Date().addingTimeInterval(-days * 86_400)
    guard let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
    for file in files {
        let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        if modified < limit { try? FileManager.default.removeItem(at: file) }
    }
}

struct PosterImage: View {
    let imageURL: String?
    var cornerRadius: CGFloat = 10
    @State private var image: UIImage?
    @State private var failed = false

    init(imageURL: String?, cornerRadius: CGFloat = 10) {
        self.imageURL = imageURL
        self.cornerRadius = cornerRadius
        // Cached posters show on the first frame, without a spinner.
        _image = State(initialValue: imageURL.flatMap { URL(string: $0) }.flatMap { PosterCache.cachedImage(for: $0) })
    }

    var body: some View {
        Color.secondary.opacity(0.12)
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if failed || imageURL == nil {
                    placeholder
                } else {
                    ProgressView()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .accessibilityHidden(true)
            .task(id: imageURL) { await load() }
    }

    private func load() async {
        guard let imageURL, let url = URL(string: imageURL) else {
            image = nil
            failed = true
            return
        }
        if let cached = PosterCache.cachedImage(for: url) {
            image = cached
            failed = false
            return
        }
        image = nil
        failed = false
        let loaded = await PosterCache.image(for: url)
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.2)) {
            image = loaded
            failed = loaded == nil
        }
    }

    private var placeholder: some View {
        Image(systemName: "film")
            .font(.title3)
            .foregroundStyle(.secondary)
    }
}

/// The last series payload loaded for each catalog path, so the series screen
/// (synopsis, episodes, seasons) opens offline from Continue or Downloads.
enum SeriesCache {
    static var folder: URL { URL.applicationSupportDirectory.appendingPathComponent("SeriesCache", isDirectory: true) }

    private static func file(for watchUrl: String) -> URL {
        folder.appendingPathComponent(cacheFileName(catalogPath(of: watchUrl)) + ".json")
    }

    static func store(_ series: Series, for watchUrl: String) {
        guard let data = try? JSONEncoder().encode(series) else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? data.write(to: file(for: watchUrl), options: .atomic)
    }

    static func load(_ watchUrl: String) -> Series? {
        guard let data = try? Data(contentsOf: file(for: watchUrl)) else { return nil }
        return try? JSONDecoder().decode(Series.self, from: data)
    }

    static func prune(olderThanDays days: Double = 60) {
        pruneFiles(in: folder, olderThanDays: days)
    }
}

/// Series title in the selected catalog language. In the Japanese mode the
/// English name stays first and the Japanese title is shown underneath.
struct SeriesTitle: View {
    let title: String
    let titleJp: String?
    let language: TitleLanguage
    var font: Font = .headline
    var lineLimit = 2

    private var japanese: String? {
        guard language == .jp, let titleJp, !titleJp.isEmpty, titleJp != title else { return nil }
        return titleJp
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(font)
                .lineLimit(lineLimit)
            if let japanese {
                Text(japanese)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(lineLimit)
            }
        }
    }
}

/// Settings that belong to this device rather than to the relay identity.
enum DeviceSettings {
    /// Translate synopses to the device language as soon as they load.
    static let translateSynopsisKey = "relaycast.translate-synopsis"
}

/// "+18" marker for R+ and Rx series.
struct AdultBadge: View {
    var body: some View {
        Text("+18")
            .font(.caption2.weight(.heavy))
            .monospacedDigit()
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(.red, in: Capsule())
            .accessibilityLabel("Para mayores de 18 años")
    }
}

struct ErrorNotice: View {
    let message: String

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .foregroundStyle(.red)
            .font(.footnote)
    }
}

struct LoadingOverlay: View {
    let message: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.2).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                    .controlSize(.large)
                Text(message)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .glassEffect(.regular, in: .rect(cornerRadius: 24))
            .padding(32)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }
}

/// On regular width (iPad, an unfolded iPhone Duo) the adaptable tab bar
/// floats over the navigation bar area; inline root titles avoid being
/// covered. Decided by size class, not by device idiom: an unfolded iPhone
/// Duo reports the iPhone idiom with a regular-width layout.
struct AdaptableTabRootTitleStyle: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    func body(content: Content) -> some View {
        content.navigationBarTitleDisplayMode(horizontalSizeClass == .regular ? .inline : .automatic)
    }
}

/// Opens a series from a list: pushes it on one column, shows it beside the
/// list on two.
struct OpenSeriesAction {
    let action: (Anime) -> Void

    func callAsFunction(_ anime: Anime) { action(anime) }
}

extension EnvironmentValues {
    @Entry var openSeries = OpenSeriesAction { _ in }
    /// The series shown beside the list, to mark its row.
    @Entry var openedSeriesURL: String? = nil
}

/// Circular Liquid Glass icon used by the player controls and menus.
struct GlassIcon: View {
    let systemName: String
    var size: CGFloat = 44
    var font: Font = .title3

    var body: some View {
        Image(systemName: systemName)
            .font(font.weight(.semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .contentShape(Circle())
            .glassEffect(.regular.interactive(), in: .circle)
    }
}

/// Recientes, Buscar and Continuar: one column, or the list with the series
/// beside it where both size classes are regular (iPad, the unfolded iPhone
/// Duo). An iPhone in landscape (compact height) keeps one column.
///
/// The two columns are two NavigationStacks side by side rather than a
/// NavigationSplitView: inside a tab with the floating tab bar, a split view
/// created when its tab is first selected was laid out below the tab bar and
/// then moved up (the column title and buttons jumped). Navigation stacks take
/// the tab's safe area from their first layout, like every other tab.
///
/// The open series is this view's state, so it survives switching between one
/// and two columns (folding, rotating, resizing). Each series gets its own
/// identity, so choosing another one always replaces the screen beside the
/// list. The player is presented above the tabs (`PlayerHost`), so changing
/// the layout never closes it.
struct AdaptiveSeriesNavigation<Content: View>: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @ViewBuilder let content: () -> Content
    @State private var path: [Anime] = []

    private var twoColumns: Bool { horizontalSizeClass == .regular && verticalSizeClass == .regular }

    var body: some View {
        if twoColumns {
            GeometryReader { proxy in
                let columns = AdaptiveColumns.split(in: proxy)
                HStack(spacing: 0) {
                    NavigationStack(root: content)
                        .environment(\.openSeries, OpenSeriesAction { path = [$0] })
                        .environment(\.openedSeriesURL, path.last?.watchUrl)
                        // In landscape the floating tab bar stands at the
                        // trailing edge and its inset reached this column too,
                        // which never touches that edge: the list stopped short.
                        .ignoresSafeArea(.container, edges: .trailing)
                        .frame(width: columns.list)
                    if columns.gap > 0 {
                        Color(uiColor: .systemGroupedBackground)
                            .frame(width: columns.gap)
                            .ignoresSafeArea()
                    } else {
                        Divider()
                            .ignoresSafeArea()
                    }
                    NavigationStack {
                        if let anime = path.last {
                            EpisodesScreen(anime: anime)
                                .id(anime.watchUrl)
                        } else {
                            ContentUnavailableView("Elige una serie", systemImage: "rectangle.split.2x1", description: Text("Se abre aquí, junto a la lista."))
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        } else {
            NavigationStack(path: $path) {
                content()
                    .navigationDestination(for: Anime.self) { EpisodesScreen(anime: $0) }
            }
            .environment(\.openSeries, OpenSeriesAction { path.append($0) })
        }
    }

}

/// Where two columns split (series lists and Settings).
enum AdaptiveColumns {
    /// The list's width and the gap after it. On a foldable (iOS 27.1+ reports
    /// the fold as a division region) the columns meet at the fold, so the
    /// hinge never splits a column; otherwise the list takes about a third.
    static func split(in proxy: GeometryProxy) -> (list: CGFloat, gap: CGFloat) {
        let width = proxy.size.width
        if #available(iOS 27.1, *) {
            let folds = proxy.reservedRegions(kind: .division, options: .includeInactive)
            if let fold = folds.first(where: { $0.frame.height > $0.frame.width }),
               fold.frame.minX >= 320, width - fold.frame.maxX >= 320 {
                return (fold.frame.minX, max(0, fold.frame.width))
            }
        }
        return (min(max(width * 0.34, 340), 440), 0)
    }
}
