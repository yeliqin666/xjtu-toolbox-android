---
name: pidai-skin-maker
description: Create or revise importable 屁岱 character skins in xjtu-toolbox-android from a text idea, storyboard, video, or visual reference. Use when inventing actions, persona prompts, catchphrases, and character chatter, reviewing motion continuity, or packaging a skin for Settings import. The form is free: any vector shape, any color, any number of pieces, and bundled bitmaps are all playable.
---

# Pidai Skin Maker

Turn the user's character idea into a coherent set of motions that can be reviewed before it is integrated.

The form is not constrained. A skin is a stack of layers: arbitrary vector outlines (concave, holes, open strokes, separate pieces), arbitrary colors, and optionally bitmaps shipped inside the package. Draw a bird, a flower, a pile of dung, or the user's own photograph — the renderer plays whatever the spec declares. Do not talk the user out of a shape, and do not flatten a concept into a blob because it seems easier.

Image generation is optional. A text-only LLM can create a complete skin by writing the vector geometry and keyframes directly; it may also draft SVG previews and lift their path data into `motion.json`. Never block skin creation merely because image-generation tools are unavailable. When image generation or a user-supplied photo *is* available, that art can ship in the package as an `images/` bitmap layer instead of being traced by hand.

This directory is the canonical Agent Skills package. It can live under `.agents/skills/`, `.cursor/skills/`, or `.claude/skills/`; keep scripts, references, and workflow changes beside this file so every client produces the same skin format.

The Skill can create a complete shareable package without the App source tree. When working inside an `xjtu-toolbox-android` checkout or changing its renderer, read the current `SkinGeometry.kt`, `ImportedMotionEngine.kt`, `PidaiSkinParser.kt`, `BloubBotIcon.kt`, and tests first because the runtime can evolve. Eyes are just layers like anything else: draw them when the character has them, leave them out when it does not.

Read [references/motion-design.md](references/motion-design.md) before deciding what the character does — it is the method for getting motion out of a premise instead of reaching for stock loops. Read [references/motion-format.md](references/motion-format.md) before writing or implementing any geometry. Read [references/package-format.md](references/package-format.md) when the skin will be shared, imported, or given its own voice.

## Workflow

1. Answer the three questions in [motion-design.md](references/motion-design.md) first — what the character is made of, what it wants, what stops it — and name its signature move. Everything else is derived from those four answers, including the speaking style, catchphrases and local chatter. The concept decides the actions' count, names, timing, and intensity. Reuse app states when useful, and add or reshape states when the idea calls for it.
2. Make a compact storyboard of the important forms and transitions. Before settling on translate-and-scale, go through the device table in [motion-design.md](references/motion-design.md) — hinges, travelling bulges, opening holes, growing lines, counter-motion — and check the five beats differ in kind rather than in amplitude. Name the moving pieces first — a shell lid, a crack, three tufts of down — and give each one a stable layer `key`; that decision, not the outline, is what makes the motion readable. Add enough keyframes to express the motion; do not force every action into the same anticipation-action-settle pattern.
3. Write a temporary motion spec under `build/pidai-skin/<id>/motion.json`. Put any bitmaps beside it under `images/`. Run `scripts/preview_motion.py` to generate animated SVGs, a keyframe sheet, and a continuity report.
4. Read `continuity.txt` before looking at anything else. It measures peak travel, time-to-first-motion, and layers whose rotation is invisible because the shape is symmetric — the three ways a skin ends up technically animating while looking dead on a 46 px icon. Then inspect the animation and keyframe sheet. Use measured jumps and seams as evidence, not automatic rejection: a snap, impact, collapse, or volume jump may be intentional. Check every segment the report marks `crossfade=`: a crossfade is a legitimate cut, but an unintended one usually means two keyframes of the same layer disagree about path structure. Judge readability at the bottom-navigation size as well as full size.
5. Keep the accepted motion in the importable `motion.json`. The App draws the declared geometry as-is — do not simplify a defining feature away, and do not ask the user to give one up. If something genuinely cannot be expressed (a gradient, a blur, a per-pixel effect), say so plainly and offer the closest buildable version, or extend the renderer with the smallest coherent change plus tests.
6. Use the preview continuity report and packager validation for every skin. If changing the App renderer or importer, add or update tests for observable invariants such as valid geometry, an intended loop closure, or interruption during a morph. Avoid tests that merely duplicate constants.
7. For a shareable skin, prepare `manifest.json`, `motion.json`, and optional `persona.json`, then run `scripts/package_skin.py`. Deliver both the standard directory and its ZIP-compatible `.pidaiskin` archive. The standard directory is ready to place at a public GitHub repository root for community import. Keep character chatter distinct from the persona Prompt so the App can mix it locally without calling a model.
8. Apply the substitution test from [motion-design.md](references/motion-design.md) before packaging: write *this action shows that the character is ___* for each action, and rewrite any sentence that still reads true with a different character in it. A skin can pass every measurement and still be filler; this is the only check that catches that. Then run the bundled preview and packaging checks. When App code changed, also run the narrow bot-engine tests and relevant app unit-test task. Report the actions and voice created, link the previews, standard directory, and package, and say plainly if anything the user asked for could not be built and why.
9. In every delivery that contains a persona, explicitly remind the user that the character Prompt, catchphrases, and chatter pool are editable. Point them to `persona.json`, briefly name the relevant fields, and say the package can be rebuilt after edits. Show the generated phrases in the delivery when the list is short enough to review comfortably.

## Preview command

```text
python <skill-dir>/scripts/preview_motion.py \
  --spec build/pidai-skin/<id>/motion.json \
  --out-dir build/pidai-skin/<id>/preview
```

The helper emits one looping SVG per action and a `keyframes.svg` overview, with the skin's real colors and any bundled bitmaps inlined. `continuity.txt` measures per-layer travel, loop seams, and which segments crossfade.

Package the accepted skin as a ZIP-compatible `.pidaiskin` file:

```text
python <skill-dir>/scripts/package_skin.py \
  --manifest build/pidai-skin/<id>/manifest.json \
  --motion build/pidai-skin/<id>/motion.json \
  --persona build/pidai-skin/<id>/persona.json \
  --directory build/pidai-skin/<id>/standard \
  --output build/pidai-skin/<id>/<id>.pidaiskin
```

The standard directory contains the same normalized JSON payload as the archive. It can be committed directly as a skin repository; the App accepts the public GitHub repository URL. If the skin does not need a persona, omit both `--persona` and `persona.json`.

Replace `<skill-dir>` with the directory containing this `SKILL.md`. Agents should resolve it from their discovered Skill path; Claude Code may use `${CLAUDE_SKILL_DIR}`.

Do not present generated wording as final or locked. Treat it as an editable first draft even when the user did not ask to revise the voice yet.
