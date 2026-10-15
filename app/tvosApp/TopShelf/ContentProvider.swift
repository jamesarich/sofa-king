import TVServices

/// The Top Shelf: the channels watched most recently, newest first, each opening the app on that channel.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        let channels = TopShelfChannels.load()
        guard !channels.isEmpty else { return completionHandler(nil) }
        let items = channels.map { channel in
            let item = TVTopShelfSectionedItem(identifier: channel.guideId)
            item.title = "\(channel.number) \(channel.name)"
            item.imageShape = .square
            if let logo = channel.logo, let url = URL(string: logo) {
                item.setImageURL(url, for: .screenScale1x)
                item.setImageURL(url, for: .screenScale2x)
            }
            if let url = TopShelfChannels.url(for: channel.guideId) {
                let open = TVTopShelfAction(url: url)
                item.displayAction = open
                item.playAction = open
            }
            return item
        }
        let section = TVTopShelfItemCollection(items: items)
        section.title = "Recent channels"
        completionHandler(TVTopShelfSectionedContent(sections: [section]))
    }
}
