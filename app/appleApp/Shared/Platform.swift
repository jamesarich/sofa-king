import SwiftUI

#if os(iOS)
// The Apple TV's remote and card styling, which the iPhone and iPad have no counterpart for: the views the two apps
// share call these, and on iOS they do nothing or draw a plain button.

/// Where the Siri Remote's touch surface was pushed; nothing sends one on iOS.
enum MoveCommandDirection {
    case left, right, up, down
}

extension View {
    func onMoveCommand(perform action: ((MoveCommandDirection) -> Void)?) -> some View { self }

    func onExitCommand(perform action: (() -> Void)?) -> some View { self }

    func onPlayPauseCommand(perform action: (() -> Void)?) -> some View { self }
}

extension PrimitiveButtonStyle where Self == PlainButtonStyle {
    /// A card on tvOS lifts and grows under focus; touch has no focus, so a card is a plain button.
    static var card: PlainButtonStyle { .plain }
}
#endif
