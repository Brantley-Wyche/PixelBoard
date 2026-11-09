# Material 3 Expressive keyboard look — design

Fork: `Brantley-Wyche/PixelBoard`, branch `feat/m3-expressive-look`.
Upstream request: Akshayykadam/PixelBoard#133 ("Make it look more material").

## Intent

Make the PixelBoard keyboard look closer to the Material 3 Expressive
screenshot in #133. Rounded and pill keys already ship (the "Rounded Keys"
toggle), so this round covers the three things that are still missing on
the owner's phone (OnePlus 15, ColorOS 17 / Android 17, PixelBoard 1.3.3 on
Gboard 18.5.3).

## What was decided (by the owner, from mockups)

1. **Toolbar buttons — soft sunburst.** The features-menu (grid) button on
   the left of the toolbar and the mic on the right each sit on a filled
   12-wave "sunburst" shape (gentle waves, about 4% depth), light lavender
   with a dark icon. Other toolbar icons stay plain.
2. **See-through — dropped after testing on the phone.** It was built (the
   keyboard surface at 88% of the theme colour) and measured working, but
   apps lay their content out above the keyboard, so the only thing behind
   it is the app's plain background and the effect is invisible (frosted
   blur would be too). The owner chose to drop it. For colour behind the
   keys, use a Gboard photo theme; the other parts still apply on top.
   Added instead (owner's request): remove the divider line above the emoji
   row; the one between the emoji row and the toolbar stays.
3. **Corners — rounded top.** The keyboard stays edge to edge; only the two
   top corners are rounded.
4. **`\?123` label fix.** Already fixed upstream in 3c63da7 (runtime label
   sanitizer); arrives by building from current `main`. No extra work.

## Non-goals

- Simple Calculator (#142) and other feature requests.
- A floating keyboard card, a separate number row, or new key shapes.
- Restyling the emoji, clipboard or other non-letter panels beyond what
  follows automatically.

## Anti-goals

- Lower readability than today.
- Losing the owner's Gboard data on install (must install as an update).
- Breaking typing, Rambler or AI Writing Tools if a hook fails on another
  Gboard build (every hook must fail closed to today's look).

## Findings in Gboard 18.5.3 (decompiled)

- **Rounded top corners exist natively.** Module `rbm` (module id 392,
  gated by Phenotype flag `top_rounded_keyboard`, default false) clips the
  keyboard body to a top-rounded outline when
  `always_top_rounded_keyboard_for_debug` is true (or the app requests
  `topRoundedKeyboard`). Radius comes from `top_rounded_keyboard_radius`
  (double, default 20.0 dp).
- **No native see-through or shaped toolbar buttons.** `render_effect_blur`
  only changes how custom photo themes are blurred; the `sdCookie9` shader
  belongs to the AI "energy" glow, not the toolbar.
- PixelBoard's existing Phenotype override hook
  (`GboardAiWritingToolsRuntime`) can force boolean, long and double flags
  by name, as Rounded Keys and Google Sans already do.

## Approach

- **Corners:** force the three flags above through the existing override
  hook (radius 28 dp — my call, matches M3 extra-large shape).
- **Sunburst and top hairline:** a new bytecode hook at the start of
  `rbm.s(qcl, View)` (the module that runs for each keyboard view once
  `top_rounded_keyboard` is on) calls a new extension runtime with the
  view. The runtime:
  - finds the toolbar keys by resource entry name
    (`key_pos_header_access_points_menu`, `key_pos_header_power_key`),
    gives them a 12-wave sunburst ripple background at 78% of the key size
    (Material You `system_accent1_200` fill, `system_accent1_900` icon),
    and clears the mic's own circle;
  - removes the line above the emoji row: that row's container (tag
    `.divider.horizontal.for-fast-access-bar`) has a layer-list background
    with a 1dp stroke whose sides sit off screen, drawing a line above and
    below the row. Its background is wrapped in an `InsetDrawable` shifted
    up 2dp, so the top line is clipped and the bottom one stays;
  - adds an 8dp top margin to the toolbar row (`keyboard_header_view_holder`)
    while it is the first visible row, so the sunbursts sit clear of the
    rounded corners (owner's request); removed when the emoji row shows;
  - re-checks all of these before every frame, because Gboard rebinds the toolbar
    keys on layout switches such as ?123. Only reset properties are
    rewritten, so there is no redraw loop.
  All of this is wrapped in try/catch and does nothing if the views are not
  found.
- **Settings:** one new toggle, "Expressive look" (default on), in the same
  feature list as Rounded Keys. Turning it off restores today's look after
  the keyboard restarts.
- **Install:** sign with `tools/morphe-data/imported.keystore` (alias
  `pixelboard`, same certificate as the installed 1.3.3) and `adb install -r`.

## Settled on the device

- Sunburst size and colours, and which views are the toolbar keys (from a
  `cmd window dump-visible-window-views` dump of the input method window).
- See-through: see decision 2.
