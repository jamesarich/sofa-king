import SwiftUI

/// The sizes the shared pages lay out with: the Apple TV's ten-foot numbers, and touch sizes on an iPhone or iPad,
/// a step larger in the regular horizontal size class. Read it with `@PageLayout`.
struct PageMetrics {
    /// A poster card's width (2:3), which a music cover (1:1) shares.
    let poster: CGFloat
    /// A wide card's width (16:9), which a library tile shares.
    let wide: CGFloat
    /// The narrowest a grid column gets; nil keeps the Apple TV's six fixed columns.
    let gridMinimum: CGFloat?
    /// The inset of a page's text from the screen's edge, inside its safe area, and of a card row's first card; none on
    /// Apple TV, whose screen keeps its own.
    let margin: CGFloat
    /// Between a page's sections.
    let sectionSpacing: CGFloat
    /// Between cards in a row or a grid.
    let cardSpacing: CGFloat
    /// An episode's still.
    let still: CGFloat
    /// An item page's poster.
    let detailPoster: CGFloat
    /// A list row's fixed width on Apple TV; nil fills the page.
    let rowWidth: CGFloat?
    /// Whether the page is touched rather than focused, so it stacks what does not fit side by side.
    let touch: Bool

    static let tv = PageMetrics(
        poster: 240, wide: 400, gridMinimum: nil, margin: 0, sectionSpacing: 48, cardSpacing: 40,
        still: 320, detailPoster: 300, rowWidth: 1500, touch: false
    )
    static let compact = PageMetrics(
        poster: 120, wide: 240, gridMinimum: 112, margin: 16, sectionSpacing: 24, cardSpacing: 12,
        still: 128, detailPoster: 160, rowWidth: nil, touch: true
    )
    static let regular = PageMetrics(
        poster: 160, wide: 320, gridMinimum: 152, margin: 24, sectionSpacing: 32, cardSpacing: 20,
        still: 200, detailPoster: 220, rowWidth: nil, touch: true
    )
}

/// The `PageMetrics` for where the view is: Apple TV's, or the iPhone's and iPad's for the horizontal size class.
@propertyWrapper
struct PageLayout: DynamicProperty {
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass

    var wrappedValue: PageMetrics { sizeClass == .regular ? .regular : .compact }
    #else
    var wrappedValue: PageMetrics { .tv }
    #endif

    init() {}
}

extension View {
    /// Insets a page's text from the screen's edges on iPhone and iPad; Apple TV's pages keep their own.
    @ViewBuilder
    func pageMargin(_ metrics: PageMetrics) -> some View {
        #if os(iOS)
        padding(.horizontal, metrics.margin)
        #else
        self
        #endif
    }

    /// On iPhone and iPad a row that scrolls sideways starts at the page's margin and scrolls out to the screen's edge.
    @ViewBuilder
    func rowMargins(_ metrics: PageMetrics) -> some View {
        #if os(iOS)
        contentMargins(.horizontal, metrics.margin, for: .scrollContent).scrollIndicators(.hidden)
        #else
        self
        #endif
    }

    /// A list row as wide as Apple TV's, or as wide as the page on iPhone and iPad.
    @ViewBuilder
    func rowWidth(_ metrics: PageMetrics) -> some View {
        if let width = metrics.rowWidth {
            frame(width: width, alignment: .leading)
        } else {
            frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
