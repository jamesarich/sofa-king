import UIKit

/// The players' sleep timer, as the Compose app's: a number of minutes, or the end of what plays (the live program
/// on now, or the library item or recording, in place of Up next). It lasts through Up next and is turned off when
/// the viewer leaves the player; running out leaves the player, as Menu does.
struct SleepTimer: Equatable {
    /// When playback stops; nil while the timer is off or waits for the item's end.
    var until: Date?
    /// Playback stops as the item playing ends.
    var atEnd = false

    var isOn: Bool { until != nil || atEnd }

    func due(at now: Date = .now) -> Bool { until.map { now >= $0 } ?? false }
}

/// How many items Up next plays by itself in a row before it asks "Still watching?" in place of the next.
let stillWatchingAfter = 2

extension AppModel {
    /// The transport bar's Sleep timer menu: Off, 15 to 90 minutes, and `endTitle`, which stops at `endsAt` (a live
    /// program's end) or, with no date, as the item ends; no `endTitle` leaves it out.
    func sleepMenu(endTitle: String?, endsAt: Date?) -> UIMenu {
        let timer = sleep
        var actions = [UIAction(title: "Off", state: timer.isOn ? .off : .on) { [weak self] _ in self?.sleep = SleepTimer() }]
        actions += [15, 30, 60, 90].map { minutes in
            UIAction(title: "\(minutes) minutes") { [weak self] _ in
                self?.sleep = SleepTimer(until: .now.addingTimeInterval(Double(minutes * 60)))
            }
        }
        if let endTitle, endsAt.map({ $0 > .now }) ?? true {
            actions.append(UIAction(title: endTitle, state: timer.atEnd || (endsAt != nil && timer.until == endsAt) ? .on : .off) { [weak self] _ in
                self?.sleep = endsAt.map { SleepTimer(until: $0) } ?? SleepTimer(atEnd: true)
            })
        }
        let menu = UIMenu(title: strings.sleepTimer, image: UIImage(systemName: "moon.zzz"), children: actions)
        if let until = timer.until, until != endsAt {
            menu.subtitle = strings.sleepIn(minutes: Int32(max(1, Int((until.timeIntervalSinceNow / 60).rounded(.up)))))
        } else if timer.isOn, let endTitle {
            menu.subtitle = endTitle
        }
        return menu
    }
}
