# Design system

The Compose app's look in one place: the tokens (color, type, shape, spacing, motion, focus), the shared components built on them, and the patterns screens combine them into. It is Material 3 Expressive from Compose Multiplatform's `material3`, at the version the [catalog](../../gradle/libs.versions.toml) pins, with Sofa King's palette and a focus cue a remote can read from the sofa. [Architecture](architecture.md#ui) says how screens use it.

The code is `ui/src/commonMain/kotlin/dev/jamesrich/sofaking/ui/theme` plus the shared files beside it in `ui/.../ui`. Three things keep it honest:

| Check | What it holds |
| --- | --- |
| `DesignSystemTest` (`ui` `jvmTest`) | Reads the sources: colors, corner shapes, text sizes and the theme come from this system, durations have names, spacing is on the grid, and the Apple apps and the web app copy the palette's values |
| `SchemeContrastTest` (`ui` `commonTest`) | WCAG 2.1 contrast of every text pair in the three schemes and on the over-picture scrim |
| `DesignSystem*` screenshot references | The color roles, type, shapes and components on one page (`DesignSystemPreview`), in dark at TV size and in light and Prevue at phone width, so a token change shows as a picture's diff ([screenshot tests](development.md#build-and-test)) |

## Rules

- Use a Material 3 component before writing one. A `Sofa*` component is a Material one with the focus cue and the app's hierarchy, not a new control.
- Take every color, shape and text style from `MaterialTheme`, inside `SofaKingTheme`. A screen never writes a color value, a corner radius or a font size.
- Design for the remote first: every control shows focus, takes OK and is reachable with the D-pad. Touch and mouse add to that; they never replace it.
- Add a token or a component here before using it in a second place, and add it to `DesignSystemPreview`.

## Color

`SofaKingTheme(appearance)` picks one of three schemes, each a full Material `ColorScheme` in `theme/Theme.kt` built from the palette in `theme/Color.kt`. Every role is set, the fixed ones included, so no component falls back to Material's baseline purple. The app does not use dynamic color: the brand is the same on every device.

| Scheme | Shown | Look |
| --- | --- | --- |
| Dark | Always on a TV; elsewhere when Appearance is Dark, or System on a dark device | Plum surfaces, amber primary, lavender secondary |
| Light | Off a TV when Appearance is Light, or System on a light device | The same brand on paper: dark amber ink as primary, since bright amber fails contrast on paper |
| Prevue | When Appearance is Prevue | The 1990s guide channel: blue grid, yellow text, gray channel column |

The roles screens use:

| Role | Use |
| --- | --- |
| `background`, `surface` | The page. Both are one color, so a surface never shows against the window |
| `surfaceContainerLow` to `surfaceContainerHighest`, `surfaceVariant` | Cards, panels, dialogs and a focused list row, rising in emphasis |
| `onSurface` | Text and icons; also the focus ring and the focused button's fill |
| `onSurfaceVariant` | Secondary text: times, counts, help |
| `primary`, `onPrimary` | The screen's main action, the focused guide cell, progress, the now line and links |
| `primaryContainer` | The program airing now in the guide |
| `secondaryContainer`, `onSecondaryContainer` | Every other button at rest, selected chips, badges |
| `tertiary`, `tertiaryContainer` | Markers that must differ from progress: recording progress, chapter and break marks, the kids badge |
| `error`, `errorContainer` | Failures and destructive confirmations |
| `outline` | Control borders, at 3:1 or more against the surfaces controls sit on |
| `inverseSurface`, `inverseOnSurface` | The focused button's text on its light fill |

Pair a container with its own `on` color. Text reads at 4.5:1 or better on its container in every scheme; `SchemeContrastTest` lists the pairs.

### Over the picture

Live video and artwork look the same in every scheme, so what is drawn on them does too. These are the only colors a screen names directly:

| Color | Use |
| --- | --- |
| `SofaScrim` | The black behind a picture and under text shadows |
| `SofaOverlayScrim` | The panel behind anything over the picture: plum at 88%, which keeps text above 4.5:1 over a white picture |
| `SofaOverlayScrimDense` | The denser edge panels with lists in them: the mini guide and the multiview picker |
| `SofaOverlayTrack` | The unfilled part of a progress bar |
| `SofaOnPicture` | Text straight on artwork, over a dark gradient or with a shadow |
| `SofaOnPictureAccent` | A card's progress and its watched tick |

Draw a panel with `Modifier.overlaySurface()` and put its content in `OverVideo`, which keeps the dark scheme's text on it in a light app. The break screen alone paints with the palette itself, since it draws the logo's own colors.

## Type

The type scale is Material's, in the platform's own font, so text follows the device's font and size settings. Use a style by its role and never set a size or weight; for emphasis use the style's emphasized twin (`titleMediumEmphasized`).

| Style | Use |
| --- | --- |
| `display*`, `headlineLarge`, `headlineMedium` | An item's title on its own page, a grid's title and the big numbers over the picture |
| `headlineSmall` | A page's title and what is playing |
| `titleLarge` | A section or dialog title |
| `titleMedium` | A row's or card's title, a status over the picture |
| `titleSmall` | A setting's label |
| `bodyLarge`, `bodyMedium` | Descriptions and a row's second line |
| `bodySmall` | Help under a setting, times and metadata |
| `labelLarge` | Buttons, chips and tabs |
| `labelMedium`, `labelSmall` | Badges |

Captions and subtitles are the exception: they draw at the size, font and colors the stream or the viewer's caption settings give (`player/Captions.kt`).

## Shape

Shapes are Material's defaults, read from `MaterialTheme.shapes` or a component's own default (`CardDefaults.shape`, `ButtonDefaults.shape`).

| Shape | Corner | Use |
| --- | --- | --- |
| `extraSmall` | 4 dp | Badges and skeleton lines |
| `small` | 8 dp | Over-picture panels, chips, thumbnails |
| `medium` | 12 dp | List rows and cards |
| `large` | 16 dp | Dialogs, sheets and album art |
| `CircleShape` | Full | Buttons, icon buttons and round badges |

## Spacing and layout

Padding and gaps are a multiple of 4 dp; inside a badge or pill 1, 2 and 6 dp are allowed too. The common steps are 4 (within a control), 8 (between related things), 12, 16 (a panel's padding, the handheld side margin) and 24 (between groups, a card over the picture). A size that means something has a name beside its screen (`CARD_GAP`, `ROW_HEIGHT`, `FIELD_WIDTH`).

- Screen edges come from `screenPadding` or `listScreenPadding` (`ui/Adaptive.kt`): overscan on a TV, the system bars and cutouts plus a small margin elsewhere.
- Layout follows `LocalLayoutProfile`: a TV keeps its metrics at any size, and other windows adapt by Material's window size class. [Architecture](architecture.md#ui) has the breakpoints.
- A touch target is at least Material's 48 dp. `TvFocusControl` keeps that minimum while drawing the focus ring on the control itself.

## Motion

Component motion comes from the theme's `MotionScheme.expressive()`, read as `MaterialTheme.motionScheme`: spatial specs for things that move or resize, effects specs for color and opacity. `ui/Motion.kt` holds the shared transitions:

| Transition | Use |
| --- | --- |
| `PopIn`, `PopOut` | Small things that appear in place: pills, badges, the header loader |
| `RiseIn`, `SinkOut` | Panels that come up from the bottom edge |
| `rememberSettled` | A state that flickers, such as buffering, shown only once it has held and then kept briefly |

An ambient fade that is not a component's motion (the backdrop, the break screen, lyrics) uses a `tween` with a named duration. Where the system's animations are off, moving things hold still and focus snaps (`rememberMotionEnabled`).

## Focus

Material's focus state layer is too faint across a room, so every focusable control takes `Modifier.tvFocus(shape)`: a 3 dp ring in `onSurface` that follows the control's shape, and a scale of 1.1 on the fast spatial spring. Guide cells and list rows pass a scale of 1 so they never cover their neighbors; a focused list row also raises its container to `surfaceVariant` (`listRowColors`). On a TV, a moving mouse or pointer remote focuses what it is over. Off a TV the ring and scale show only after a key, as the web's `:focus-visible` does, so a mouse click leaves no ring on what it pressed.

Material's own focus cues, the state layer and its opt-in inset ring, stay at their defaults; the app's ring is the one cue on every platform. [Architecture](architecture.md#ui) covers moving focus.

## Components

| Component | Built on | Use |
| --- | --- | --- |
| `SofaButton` | `Button` | Every action. `main = true` for the screen's one main action (Play, Watch, Unlock); the rest are tonal. The focused one turns light. A disabled one keeps focus until the D-pad moves on |
| `SofaChip` | `FilterChip` | A filter that is on or off; a check marks it selected, an optional icon leads an unselected one that acts (Search), and a count follows the label in a dimmer tone |
| `SofaActionChip` | `AssistChip` | A chip that acts or goes somewhere: Now, a library, a cast member; an action leads with its icon (a clock on Now, a magnifier on Search) |
| `SofaMenuChip` | `AssistChip`, `DropdownMenu` | One choice from a long list, such as a genre |
| `SofaIconToggle`, `SofaIconAction` | Tonal icon buttons, `TooltipBox` | A yes-or-no or one-shot action beside the main one, named by its tooltip |
| `ToggleRow`, `SettingToggle` | Toggleable `ListItem`, `Switch` | A switch setting; the whole row takes focus |
| `ChoiceRow`, `SettingChoice` | `SingleChoiceSegmentedButtonRow` | A setting with a few named values |
| List rows | `ListItem` with `listRowColors` and `listRowShapes` | Any list the D-pad walks |
| `TvTextField` | `OutlinedTextField` | Text entry that a remote can enter and leave |
| `ProgressBar` | `LinearProgressIndicator` | How far along a program or a title is |
| `SofaLoader`, `SofaLoaderLabel` | `LoadingIndicator` | Busy; pair it with a label |
| `Modifier.overlaySurface`, `OverVideo` | `background`, the theme | Anything over the picture |
| `MessageScreen`, `LoadFailedScreen`, `SignedOutScreen` | | A page with nothing to show: empty, failed or signed out, with what to do next |
| `SofaIcons` | `ImageVector` | The icons outside Material's core set |

A component takes a `modifier`, hoists its state and has a content description or a label for anything that is only an icon.

## Other platforms

The palette in `theme/Color.kt` is the source. The other platforms copy its values by hand:

| Platform | Copy |
| --- | --- |
| iPhone, iPad and Apple TV | `appleApp/Shared/Brand.swift`, which `DesignSystemTest` compares with the palette |
| Web app | The custom properties at the top of `web/src/style.css`, dark, light under `prefers-color-scheme` and the players' dark block, which `DesignSystemTest` compares too: amber `--primary` for the main action and progress, lavender `--accent` for a selection, as in the app |
| Admin portal | The custom properties at the top of the server's `admin.css`, dark and light, with the contrast of each pair |
| Documentation site | `docs/site/brand.css` |
| Android launch screen and icons | `androidApp/src/main/res/values/colors.xml` and the launcher drawables |

## Changing it

1. Change the palette or a scheme in `theme/`, and the copies above when a brand color moves.
2. Add a new token or component to `DesignSystemPreview` and this page.
3. Run `:ui:jvmTest --tests 'dev.jamesrich.sofaking.ui.theme.*'`, then write the screenshot references again ([build and test](development.md#build-and-test)).
4. After a Compose Multiplatform bump, read the [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) for the version it is based on, which the [Compose Multiplatform release](https://github.com/JetBrains/compose-multiplatform/releases) names, and move off anything deprecated: the build fails on a warning.
