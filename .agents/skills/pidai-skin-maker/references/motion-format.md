# 屁岱动作皮肤格式

## What a motion skin controls

A skin is a stack of drawn layers changing over time. There is no required body, no required silhouette, and no fixed palette. Draw a bird, a flower, a lump of dung, a logo, a photograph — the renderer plays back whatever the spec declares.

`format_version: 2` (renderer `free-motion-v2`) is the current format. `format_version: 1` (`radial-motion-v1`) still imports; the app translates it into the same layer model, so old skins keep playing unchanged. Write new skins in v2.

## Coordinate system and units

- `x` right, `y` down. Angle zero points right and increases clockwise. `rot_deg` is degrees.
- One unit is the resting body radius. The app fits roughly `±1.22` units into the icon, so keep the artwork inside about `±1.1` and the important reading inside `±1.0`.
- All coordinates, offsets and pivots are bounded to `±100`; scales to `0 < s ≤ 100`; `alpha` to `0..1`.

## Shapes

`shapes` is an object of named, reusable drawing units. Every shape is stored internally as cubic segments, so the kind you choose is only about convenience.

| kind | fields | notes |
| --- | --- | --- |
| `path` | `d` | SVG path data: `M L H V C S Q T A Z` and their relative forms. Multiple subpaths are fine. |
| `polygon` | `points` | `[[x,y], …]`, closed automatically. |
| `polyline` | `points` | Same, left open — the natural way to draw a crack, a whisker, a stroke of ink. |
| `circle` | `r`, optional `cx`/`cy` | |
| `ellipse` | `rx`, `ry`, optional `cx`/`cy` | |
| `rect` | `x`, `y`, `w`, `h`, optional `radius` | |
| `image` | `src`, `w`, `h` | A bitmap shipped inside the package. See **Bitmaps**. |

Concave outlines, crescents, rings, overhangs, several disconnected pieces in one shape, and open strokes are all representable. There is no star-shape requirement and no radial resampling.

Arcs (`A`) are converted to cubics on load. If you generate a path from a drawing tool, flatten `A` to `C` yourself when you want the keyframes to interpolate against another shape — see **Interpolation**.

### Paint

Any shape may declare:

- `fill` and `stroke`: `#RGB`, `#RRGGBB`, `#RRGGBBAA`, or one of `ink`, `paper`, `none`.
  - `ink` follows the app's foreground color, `paper` its background. Use them when the skin should stay readable in both light and dark mode, or to punch a hole that shows the bar behind it.
  - Declaring `stroke` without `fill` means fill `none`; declaring neither means `fill: "ink"`.
- `stroke_width` (default `0.04`), `stroke_cap` (`butt`/`round`/`square`), `stroke_join` (`miter`/`round`/`bevel`).
- `fill_rule`: `nonzero` (default) or `evenodd`. `evenodd` plus a second subpath is how you cut a hole.

## Layers

Each keyframe lists `layers`. Array order is z-order: index 0 is drawn first, at the bottom.

```json
{ "key": "shell_top", "shape": "shell_top", "cx": 0.1, "cy": -0.34, "rot_deg": -16, "pivot_y": 0.02, "alpha": 1 }
```

- `shape` names a declared shape. Several layers may reuse one shape.
- `key` identifies the layer across keyframes and across actions. Give every layer a stable, meaningful key. Without one it defaults to `<shape>#<index>`, which silently re-pairs when the order changes — a flying shell fragment can end up morphing into a piece of fluff.
- Transform order: scale about `pivot`, rotate about `pivot`, then translate by `cx`/`cy`. `sx`, `sy`, `rot_deg`, `pivot_x`, `pivot_y` all default to neutral.
- `alpha` multiplies the layer's opacity.
- `fill`, `stroke`, `stroke_width` override the shape's own values for this keyframe, so color can animate.

A layer that appears in one keyframe and not the next fades out over that segment; a layer that appears later fades in. You do not have to keep the layer count constant — but listing every key in every keyframe with `alpha: 0` gives you exact control over when things arrive.

## Interpolation

Two keyframes of the same layer interpolate **control point by control point** when the geometry has the same structure: same number of subpaths, each with the same number of segments and the same open/closed flag. Position, rotation, scale, alpha and solid colors always interpolate.

When the structure differs — a completely different shape, or a vector turning into a bitmap — the runtime crossfades the two geometries along the interpolated transform. That is a legal, deliberate motion, not an error. The preview report labels each such segment as `crossfade=<keys>` so you can tell an intended cut from an accident.

To morph a shape rather than crossfade it, give both shapes the same segment structure. The usual way is to author them as one path with a fixed command sequence and only move the numbers.

## Actions

```json
{
  "id": "hatch",
  "duration": 1.6,
  "return_to": "shell_bottom",
  "frames": [
    { "t": 0.0, "ease": "ease-out", "layers": [ … ] },
    { "t": 1.6, "layers": [ … ] }
  ]
}
```

- `duration` in seconds, `0 < duration ≤ 60`. Frame times strictly increase, start at `0`, end no later than `duration`.
- `ease` on a frame describes travel **from that frame to the next**: `linear`, `ease-in`, `ease-out`, `ease-in-out`.
- `loop: true` replays continuously; include a closing frame at exactly `duration`.
- Optional `return_to` names a shape the report compares against the final keyframe. Omit it when the action is meant to end somewhere else.

`bindings` maps app beats `rest`, `idle`, `thinking`, `alert`, `tap` to action ids. Omit a beat to fall back on the conventional action name. Optional `transitions` (`from`, `to`, `duration`, `ease`) declare handoffs worth previewing; the runtime uses `0.32s` otherwise.

Root-level `color` sets the skin's `ink` (`#RGB`, `#RRGGBB`, `#RRGGBBAA`).

There is no root-level background. The icon box is square, so a full-bleed backdrop shows its corners. If the character needs a plate behind it, draw one as the bottom-most layer — a `circle` or a rounded `rect` inset to about `±1.0` — where you control its shape, inset and color like any other layer.

## Bitmaps

```json
"me": { "kind": "image", "src": "images/me.png", "w": 1.6, "h": 1.6 }
```

- `src` must match `images/<name>.(png|jpg|jpeg|webp)`, at most 4 MiB each, 24 MiB for the whole skin.
- The bitmap is drawn centered on the layer origin in a `w × h` box, then transformed like any other layer — so it moves, rotates, scales and fades with the rest.
- Files live next to `motion.json` under `images/`. The packager copies them in and hashes them; a GitHub import fetches exactly the files `motion.json` names and nothing else.
- Bitmaps cannot morph. Two different `src` values crossfade. A photo skin usually wants a cutout PNG with transparency.

## Playback in the app

- Each frame is a pure pose. The runtime clamps segment progress, applies the frame's easing, matches layers by key, and interpolates geometry and transforms.
- Interrupting an action freezes the current composite result and morphs from it into the next action. Preview at least one dissimilar handoff, including an interruption partway through.
- No clocks, scripts, Kotlin or expressions go in a package. Timing comes only from `duration`, `loop`, frames, bindings and declared transitions.
- Limits: 512 shapes, 64 actions, 2–256 frames per action, 64 layers per frame, 60 000 cubic segments in total.

## Legacy radial format (`format_version: 1`)

Still accepted, not recommended for new work. A frame carries one `shape` plus optional `parts`; shapes are `circle` / `outline` / `circles` / `radii` and are resampled to 64 radii around a center, so they must be star-shaped. On import each frame becomes layers keyed `body` and `part0…`, with `behind` parts below the body. Motion is identical to what v1 produced before.

## Motion that reads at 46 px

The bottom-navigation icon is about 46 px across. Motion that looks fine in a 512 px preview routinely disappears there. `continuity.txt` measures all three of these and flags them; treat a flag as something to answer, not necessarily to obey.

- **Amplitude.** A looping action whose peak travel is under `0.06` body radii reads as a still picture. Breathing at 3% is invisible; 8–12% reads. The report prints `peak travel` for every action.
- **Response time.** A one-shot action bound to `tap` must show something within `0.25s` — and that budget includes the declared transition into it. Put the first visible beat at `0.08–0.15s`: a crack lighting up, a lid tipping, a squash. Save the elaborate part for after the user already knows they were heard.
- **Rotating a symmetric shape shows nothing.** A ring, a disc, a regular rosette spun through any angle is a perfectly still image. If a layer's only change is `rot_deg`, its outline must be visibly asymmetric. To show "thinking", orbit an off-center dot, tilt the body, or march elements in sequence — do not spin a halo.

Two more habits that do not have automated checks:

- Prefer motion that changes silhouette (tilt, squash, a hinge, a travelling bulge, a piece leaving) over motion that only changes interior detail; at icon size the silhouette is most of what the eye gets. [motion-design.md](motion-design.md) lists the devices that do this and the ones that only look like they do.
- Keep the whole character inside about `±1.1` units. Layers that fly out (shell fragments, fluff) should fade before they reach the edge, or they clip.

## Review checks

1. Every action has at least two frames, begins at `t = 0`, and ends no later than `duration`.
2. Every layer has a deliberate `key`, stable across the keyframes and actions where it means the same thing.
3. Segments reported as `crossfade` are intended cuts, not accidental structure mismatches.
4. Loop seam measurements match the intended style; unexpected gaps or direction changes are resolved.
5. Colors read in both light and dark mode, or the skin commits to its own palette on a backdrop layer it draws itself.
6. The artwork stays legible in `keyframes.svg` at roughly 46 px, and every `peak travel` / response-time / symmetric-rotation flag in `continuity.txt` has been answered.
7. Bot-engine tests cover any new boundary or interruption behavior you introduce in the app.
