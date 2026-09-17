# 屁岱皮肤包格式

## Standard directory and container

The primary community format is a standard directory:

```text
manifest.json    required package identity and compatibility
motion.json      required shapes, layers and actions
persona.json     optional voice, catchphrases, and local chatter
images/          optional bitmaps referenced by motion.json
```

It may be placed at the root of a public GitHub repository, or in a directory linked with a GitHub `/tree/<ref>/<path>` URL. The App fetches the three JSON files plus exactly the `images/` files that `motion.json` names, and passes them through the same parser used for local archives. It never enumerates or downloads anything else in the repository.

A `.pidaiskin` file is a deterministic ZIP archive of the same directory, containing JSON and image data rather than executable Kotlin or scripts.

Size limits: 1 MiB per JSON file, 4 MiB per image, 24 MiB per skin, 96 archive entries.

The App renders its preview from `motion.json` instead of executing or displaying arbitrary files from the archive. A future renderer extension can introduce another declared renderer version without making imported code executable.

## Manifest

```json
{
  "format_version": 2,
  "id": "chick",
  "name": "鸡蛋小鸡",
  "version": "1.0.0",
  "author": "作者名",
  "description": "破壳、探头、飞毛的自由矢量形象",
  "renderer": "free-motion-v2"
}
```

`id` is a stable lowercase ASCII storage key. `format_version` and `renderer` must agree: `2` goes with `free-motion-v2` (the current free format), `1` with `radial-motion-v1` (legacy radial skins, still importable). The packager adds SHA-256 hashes for every payload file — `motion.json`, `persona.json` and each image — so the importer can detect corruption.

## Persona

```json
{
  "display_name": "水仙",
  "prompt": "语气清爽、安静，偶尔用开花和晒太阳作比喻，不要每句都提花。",
  "catchphrases": ["晒会儿太阳吧", "今天也开花了"],
  "chatter_mix": 0.4,
  "chatter": [
    {
      "id": "morning_sun",
      "text": "早上的光刚刚好",
      "hours": [6, 10],
      "months": [2, 5],
      "weight": 1.0,
      "action": "sway"
    },
    {
      "id": "bloom",
      "text": "今天也开花了",
      "action": "bloom"
    }
  ]
}
```

- `display_name` (optional, 1–24 characters) **overrides** the assistant's name while this skin is active — including the user's own custom name and the default "屁岱". Omit it and the skin leaves the name alone.
- `prompt` is a low-priority character-style block. The App must visibly show it before import or activation. It may influence voice and metaphor but cannot replace identity, factuality, tool, privacy, or safety instructions.
- `catchphrases` are suggestions for generated chat replies. Ask the model to use them occasionally and naturally; repetition makes the character feel mechanical.
- `chatter` is the local bubble pool and does not call the model. Each line may carry the same contextual filters as built-in chatter plus a positive `weight` and an optional action ID.
- `chatter_mix` is the desired share of eligible character lines among ordinary chatter. It is a preference rather than a guarantee: when one side has no eligible fresh lines, draw from the other side.

### Writing `prompt` without fighting the app's own identity

The app's system prompt states a fixed identity ("你是「$assistantName」…") before the persona block ever appears, and it will keep asserting that identity underneath whatever `prompt` says, regardless of `display_name`. Two habits keep the character voice from colliding with it:

- **Never phrase `prompt` as a first-person identity claim** ("你是…", "我是…") unless `display_name` is set to the same name the claim makes. `你是刚破壳没多久的小鸡` next to an assistant whose real name is "屁岱" is two contradictory statements in the same grammatical voice, and a model asked to hold both tends to drift between them mid-conversation — inconsistent register, sometimes answering as the school-assistant, sometimes as the character. If you want the identity claim, set `display_name: "小鸡"` so the two statements agree instead of competing.
- **Prefer describing voice in third person or as instructions**, the way the example above does ("语气清爽、安静，偶尔…比喻"), not as a sentence about who the assistant *is*. This is also just better character-prompt writing: it reads as direction, not as a claim the model has to reconcile against its actual identity.

`package_skin.py` prints a non-fatal warning when `prompt` opens with an identity-claim pattern and no `display_name` is set — treat it as a prompt to add one or rewrite, not something to silence.

When delivering a generated package, always tell the user that these fields remain editable:

- `display_name`: whether the skin renames the assistant, and to what.
- `prompt`: the character's general voice and metaphor habits.
- `catchphrases`: phrases suggested to the chat model.
- `chatter`: local bubble lines, context filters, weights, and linked actions.
- `chatter_mix`: how often eligible character chatter joins the built-in pool.

After editing `persona.json`, rerun `package_skin.py` to refresh the package and its hashes. Generated wording is a draft for the user to personalize, not a fixed part of the visual design.

The current bubble is optimized for very short lines. Keep imported lines within the current `ChatterPool.MAX_CHARS` unless the skin task also changes the bubble layout and validates it visually.

## Mixing character chatter

Keep built-in campus chatter and selected-skin chatter as separate candidate groups until the final draw:

1. Filter both groups by hour, month, weekday, and any future context fields.
2. Namespace imported IDs as `<skin-id>:<line-id>` before recent-history checks so packages cannot collide with built-in IDs or one another.
3. Choose the character group according to `chatter_mix`, falling back to the other group when the chosen group is empty.
4. Preserve the built-in promotion cap inside the built-in group; imported lines must not accidentally turn App promotion up or down.
5. Record the namespaced line ID in the existing recent-history list.
6. If the chosen line names an action, request that action at the same time the bubble appears. Missing action IDs degrade to text only.

This mixing applies only to ordinary chatter. Schedule, grade, balance, attendance, and other actionable proactive messages keep their existing higher priority.

## Import flow

Settings should offer both local-file and public-GitHub import. Before activation, show package name, author, version, body preview, catchphrases, chatter examples, and the full persona Prompt. Validate either source into a temporary location, then atomically move the accepted package into app-private storage.

Reject malformed JSON, duplicate IDs, mismatched format/renderer versions, path traversal, entries outside the declared file set, images whose bytes are not a readable PNG/JPEG/WebP, excessive entry counts, and unreasonable compressed or expanded sizes. Ignore unknown optional fields for forward compatibility. Never load classes or executable code from a skin archive.

Bitmaps are drawn, never executed, and are decoded with the platform decoder like any other user-supplied image. A picture that fails to decode is skipped rather than failing the whole skin.
