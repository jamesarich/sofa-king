import TVServices

/// The Top Shelf: the library's Continue watching, each opening the app's player where the household stopped, then
/// the channels watched most recently, newest first, each opening the app on that channel.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        let sections = [continueWatching(), recentChannels()].compactMap { $0 }
        completionHandler(sections.isEmpty ? nil : TVTopShelfSectionedContent(sections: sections))
    }

    private func continueWatching() -> TVTopShelfItemCollection<TVTopShelfSectionedItem>? {
        let items = TopShelfContent.loadContinue().map { entry in
            let item = TVTopShelfSectionedItem(identifier: "vod-" + entry.id)
            item.title = entry.title
            item.imageShape = .hdtv
            if let image = entry.image, let url = URL(string: image) {
                item.setImageURL(url, for: .screenScale1x)
                item.setImageURL(url, for: .screenScale2x)
            }
            if let progress = entry.progress { item.playbackProgress = progress }
            if let url = TopShelfContent.vodUrl(for: entry.id) {
                let open = TVTopShelfAction(url: url)
                item.displayAction = open
                item.playAction = open
            }
            return item
        }
        guard !items.isEmpty else { return nil }
        let section = TVTopShelfItemCollection(items: items)
        section.title = "Continue watching"
        return section
    }

    private func recentChannels() -> TVTopShelfItemCollection<TVTopShelfSectionedItem>? {
        let items = TopShelfContent.load().map { channel in
            let item = TVTopShelfSectionedItem(identifier: channel.guideId)
            item.title = "\(channel.number) \(channel.name)"
            item.imageShape = .square
            if let logo = channel.logo, let url = URL(string: logo) {
                item.setImageURL(url, for: .screenScale1x)
                item.setImageURL(url, for: .screenScale2x)
            }
            if let url = TopShelfContent.url(for: channel.guideId) {
                let open = TVTopShelfAction(url: url)
                item.displayAction = open
                item.playAction = open
            }
            return item
        }
        guard !items.isEmpty else { return nil }
        let section = TVTopShelfItemCollection(items: items)
        section.title = "Recent channels"
        return section
    }
}
