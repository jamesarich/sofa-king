import CoreText
import MediaAccessibility
import SwiftUI

/// The look the viewer set in the Apple TV's Settings › Accessibility › Subtitles and Captioning, for the subtitles
/// the app draws itself: text and background color and opacity, size, font and edge style. A value the viewer did not
/// set keeps the app's own default.
struct CaptionStyle {
    var foreground = Color.white
    var background = Color.black.opacity(0.6)
    var window = Color.clear
    var size: CGFloat = 38
    var fontName: String?
    var edge = MACaptionAppearanceTextEdgeStyle.undefined

    static func current() -> CaptionStyle {
        var style = CaptionStyle()
        var behavior = MACaptionAppearanceBehavior.useContentIfAvailable
        func color(_ copy: Unmanaged<CGColor>, _ opacity: CGFloat) -> Color {
            Color(cgColor: copy.takeRetainedValue()).opacity(Double(opacity))
        }
        let foreground = MACaptionAppearanceCopyForegroundColor(.user, &behavior)
        if behavior == .useValue {
            style.foreground = color(foreground, MACaptionAppearanceGetForegroundOpacity(.user, nil))
        } else {
            _ = foreground.takeRetainedValue()
        }
        let background = MACaptionAppearanceCopyBackgroundColor(.user, &behavior)
        if behavior == .useValue {
            style.background = color(background, MACaptionAppearanceGetBackgroundOpacity(.user, nil))
        } else {
            _ = background.takeRetainedValue()
        }
        let window = MACaptionAppearanceCopyWindowColor(.user, &behavior)
        if behavior == .useValue {
            style.window = color(window, MACaptionAppearanceGetWindowOpacity(.user, nil))
        } else {
            _ = window.takeRetainedValue()
        }
        let relative = MACaptionAppearanceGetRelativeCharacterSize(.user, &behavior)
        if behavior == .useValue, relative > 0 { style.size *= relative }
        let edge = MACaptionAppearanceGetTextEdgeStyle(.user, &behavior)
        if behavior == .useValue { style.edge = edge }
        let descriptor = MACaptionAppearanceCopyFontDescriptorForStyle(.user, &behavior, .default).takeRetainedValue()
        if behavior == .useValue {
            style.fontName = CTFontDescriptorCopyAttribute(descriptor, kCTFontNameAttribute) as? String
        }
        return style
    }

    var font: Font {
        if let fontName { return .custom(fontName, size: size) }
        return .system(size: size)
    }
}

extension View {
    /// Draws the text of a subtitle in the viewer's caption style.
    func captionStyled(_ style: CaptionStyle) -> some View {
        self.font(style.font)
            .foregroundStyle(style.foreground)
            .modifier(CaptionEdge(edge: style.edge))
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(style.background, in: .rect(cornerRadius: 8))
            .padding(8)
            .background(style.window, in: .rect(cornerRadius: 12))
    }
}

private struct CaptionEdge: ViewModifier {
    let edge: MACaptionAppearanceTextEdgeStyle

    @ViewBuilder
    func body(content: Content) -> some View {
        switch edge {
        case .raised: content.shadow(color: .black, radius: 1, x: 0, y: -2)
        case .depressed: content.shadow(color: .black, radius: 1, x: 0, y: 2)
        case .uniform: content.shadow(color: .black, radius: 2)
        case .dropShadow: content.shadow(color: .black, radius: 2, x: 2, y: 2)
        default: content
        }
    }
}
