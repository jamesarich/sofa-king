import AVKit
import UIKit

/// The transport bar's items and Show picture style actions as last handed to an `AVPlayerViewController`.
///
/// A SwiftUI update rebuilds every `UIMenu`, and handing the controller a new set closes a menu the viewer has open,
/// so a player view that updates each second (a tick, an ad-break poll, a logo landing) would shut the Channels or
/// Audio menu under the remote. The controller gets new items only when what they show, or the [context] their
/// handlers act on (the channel and program, the item), has changed.
final class TransportBarItems {
    private var menuKey: String?
    private var actionKey: String?

    func apply(menus: [UIMenuElement], actions: [UIAction], context: String, to controller: AVPlayerViewController) {
        let newMenuKey = context + "#" + Self.key(menus)
        if newMenuKey != menuKey {
            menuKey = newMenuKey
            controller.transportBarCustomMenuItems = menus
        }
        let newActionKey = context + "#" + Self.key(actions)
        if newActionKey != actionKey {
            actionKey = newActionKey
            controller.contextualActions = actions
        }
    }

    /// What a set of items shows: each title, subtitle, state and attributes, through every submenu.
    static func key(_ items: [UIMenuElement]) -> String {
        items.map { item in
            switch item {
            case let menu as UIMenu:
                "\(menu.title)[\(key(menu.children))]"
            case let action as UIAction:
                "\(action.title)|\(action.subtitle ?? "")|\(action.state.rawValue)|\(action.attributes.rawValue)"
            default:
                item.title
            }
        }
        .joined(separator: ";")
    }
}
