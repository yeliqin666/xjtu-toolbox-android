#!/usr/bin/env python3
"""Validate and package a 屁岱 skin as a deterministic ZIP-compatible file."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
import zipfile
from pathlib import Path
from typing import Any, NoReturn


ID_RE = re.compile(r"^[a-z][a-z0-9_-]{0,63}$")
KEY_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
IMAGE_RE = re.compile(r"^images/[A-Za-z0-9_-]{1,64}\.(png|jpg|jpeg|webp)$")
COLOR_RE = re.compile(r"^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
PAINT_WORDS = {"ink", "paper", "none", "transparent"}
RENDERERS = {1: "radial-motion-v1", 2: "free-motion-v2"}
FREE_KINDS = {"path", "polygon", "polyline", "circle", "ellipse", "rect", "image"}
RADIAL_KINDS = {"circle", "outline", "circles", "radii"}
MAX_JSON_BYTES = 1_048_576
MAX_IMAGE_BYTES = 4 * 1_048_576
MAX_TOTAL_BYTES = 24 * 1_048_576
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def fail(message: str) -> NoReturn:
    raise SystemExit(f"error: {message}")


def load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        fail(f"cannot read {label}: {exc}")
    if len(data) > MAX_JSON_BYTES:
        fail(f"{label} exceeds {MAX_JSON_BYTES} bytes")
    try:
        value = json.loads(data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        fail(f"invalid {label}: {exc}")
    if not isinstance(value, dict):
        fail(f"{label} root must be an object")
    return value


def require_id(value: Any, field: str) -> str:
    if not isinstance(value, str) or not ID_RE.fullmatch(value):
        fail(f"{field} must be a lowercase ASCII id")
    return value


def require_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{field} must be non-empty text")
    return value.strip()


def require_number(value: Any, field: str, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        fail(f"{field} must be a finite number")
    if not low <= float(value) <= high:
        fail(f"{field} must be between {low} and {high}")
    return float(value)


def require_paint(value: Any, field: str) -> None:
    if not isinstance(value, str):
        fail(f"{field} must be text")
    if value.strip().lower() in PAINT_WORDS:
        return
    if not COLOR_RE.fullmatch(value.strip()):
        fail(f"{field} must be ink, paper, none, or #RGB / #RRGGBB / #RRGGBBAA")


def validate_manifest(value: dict[str, Any]) -> tuple[str, dict[str, Any], int]:
    version = value.get("format_version")
    if version not in RENDERERS:
        fail("manifest.format_version must be 1 or 2")
    skin_id = require_id(value.get("id"), "manifest.id")
    require_text(value.get("name"), "manifest.name")
    require_text(value.get("version"), "manifest.version")
    renderer = value.get("renderer", RENDERERS[version])
    if renderer != RENDERERS[version]:
        fail(f"manifest.renderer must be {RENDERERS[version]} for format_version {version}")
    for field in ("author", "description"):
        if field in value and not isinstance(value[field], str):
            fail(f"manifest.{field} must be text")
    normalized = dict(value)
    normalized["renderer"] = RENDERERS[version]
    normalized.pop("files", None)
    return skin_id, normalized, version


def validate_free_shape(shape: Any, field: str) -> None:
    if not isinstance(shape, dict):
        fail(f"{field} must be an object")
    kind = shape.get("kind")
    if kind not in FREE_KINDS:
        fail(f"{field}.kind must be one of {', '.join(sorted(FREE_KINDS))}")
    if kind == "image":
        src = shape.get("src")
        if not isinstance(src, str) or not IMAGE_RE.fullmatch(src):
            fail(f"{field}.src must be images/<name>.png|jpg|jpeg|webp")
        require_number(shape.get("w"), f"{field}.w", 1e-6, 100.0)
        require_number(shape.get("h"), f"{field}.h", 1e-6, 100.0)
        return
    if kind == "path":
        d = shape.get("d")
        if not isinstance(d, str) or not d.strip():
            fail(f"{field}.d must be a non-empty string")
        if re.search(r"[^MmLlHhVvCcSsQqTtAaZz0-9eE+\-.,\s]", d):
            fail(f"{field}.d may only use M L H V C S Q T A Z and numbers")
    elif kind in ("polygon", "polyline"):
        points = shape.get("points")
        if not isinstance(points, list) or len(points) < 2:
            fail(f"{field}.points needs at least two points")
        for index, point in enumerate(points):
            if not isinstance(point, list) or len(point) != 2:
                fail(f"{field}.points[{index}] must be [x, y]")
            require_number(point[0], f"{field}.points[{index}].x", -100.0, 100.0)
            require_number(point[1], f"{field}.points[{index}].y", -100.0, 100.0)
    elif kind == "circle":
        require_number(shape.get("r"), f"{field}.r", 1e-6, 100.0)
    elif kind == "ellipse":
        require_number(shape.get("rx"), f"{field}.rx", 1e-6, 100.0)
        require_number(shape.get("ry"), f"{field}.ry", 1e-6, 100.0)
    elif kind == "rect":
        for name in ("x", "y", "w", "h"):
            require_number(shape.get(name), f"{field}.{name}", -100.0, 100.0)
    for name in ("fill", "stroke"):
        if name in shape:
            require_paint(shape[name], f"{field}.{name}")
    if "stroke_width" in shape:
        require_number(shape["stroke_width"], f"{field}.stroke_width", 0.0, 10.0)
    if shape.get("fill_rule", "nonzero") not in ("nonzero", "evenodd"):
        fail(f"{field}.fill_rule must be nonzero or evenodd")
    if shape.get("stroke_cap", "round") not in ("butt", "round", "square"):
        fail(f"{field}.stroke_cap is not supported")
    if shape.get("stroke_join", "round") not in ("miter", "round", "bevel"):
        fail(f"{field}.stroke_join is not supported")


def validate_free_frame(frame: Any, shape_ids: set[str], field: str) -> None:
    if not isinstance(frame, dict):
        fail(f"{field} must be an object")
    layers = frame.get("layers")
    if not isinstance(layers, list):
        fail(f"{field}.layers must be an array")
    if len(layers) > 64:
        fail(f"{field}.layers must hold at most 64 layers")
    keys: set[str] = set()
    for index, layer in enumerate(layers):
        item = f"{field}.layers[{index}]"
        if not isinstance(layer, dict):
            fail(f"{item} must be an object")
        if layer.get("shape") not in shape_ids:
            fail(f"{item}.shape must name a declared shape")
        key = layer.get("key")
        if key is None:
            key = f"{layer['shape']}#{index}"
        elif not isinstance(key, str) or not KEY_RE.fullmatch(key):
            fail(f"{item}.key is invalid")
        if key in keys:
            fail(f"{field} reuses the layer key {key!r}")
        keys.add(key)
        for name in ("cx", "cy", "pivot_x", "pivot_y"):
            if name in layer:
                require_number(layer[name], f"{item}.{name}", -100.0, 100.0)
        for name in ("sx", "sy"):
            if name in layer:
                require_number(layer[name], f"{item}.{name}", 1e-6, 100.0)
        if "rot_deg" in layer:
            require_number(layer["rot_deg"], f"{item}.rot_deg", -100_000.0, 100_000.0)
        if "alpha" in layer:
            require_number(layer["alpha"], f"{item}.alpha", 0.0, 1.0)
        for name in ("fill", "stroke"):
            if name in layer:
                require_paint(layer[name], f"{item}.{name}")
        if "stroke_width" in layer:
            require_number(layer["stroke_width"], f"{item}.stroke_width", 0.0, 10.0)


def validate_radial_frame(frame: Any, shape_ids: set[str], field: str, part_count: int | None) -> int:
    if not isinstance(frame, dict):
        fail(f"{field} must be an object")
    if frame.get("shape") not in shape_ids:
        fail(f"{field}.shape must name a declared shape")
    if "alpha" in frame:
        require_number(frame["alpha"], f"{field}.alpha", 0.0, 1.0)
    parts = frame.get("parts", [])
    if not isinstance(parts, list):
        fail(f"{field}.parts must be an array")
    if part_count is not None and len(parts) != part_count:
        fail(f"{field} must keep the same part count as the other keyframes")
    for index, part in enumerate(parts):
        item = f"{field}.parts[{index}]"
        if not isinstance(part, dict) or part.get("shape") not in shape_ids:
            fail(f"{item}.shape must name a declared shape")
        if "alpha" in part:
            require_number(part["alpha"], f"{item}.alpha", 0.0, 1.0)
    return len(parts)


def validate_motion(value: dict[str, Any], skin_id: str, version: int) -> set[str]:
    if value.get("skin_id") != skin_id:
        fail("motion.skin_id must match manifest.id")
    shapes = value.get("shapes")
    if not isinstance(shapes, dict) or not shapes:
        fail("motion.shapes must be a non-empty object")
    for name, shape in shapes.items():
        require_id(name, "motion.shapes key")
        if version == 2:
            validate_free_shape(shape, f"motion.shapes.{name}")
        elif not isinstance(shape, dict) or shape.get("kind") not in RADIAL_KINDS:
            fail(f"motion.shapes.{name}.kind must be one of {', '.join(sorted(RADIAL_KINDS))}")
    if value.get("background") is not None:
        fail("motion.background was removed: draw the backdrop as the bottom-most layer instead")
    if value.get("color") is not None:
        if not isinstance(value["color"], str) or not COLOR_RE.fullmatch(value["color"]):
            fail("motion.color must be #RGB, #RRGGBB or #RRGGBBAA")

    actions = value.get("actions")
    if not isinstance(actions, list) or not actions:
        fail("motion.actions must be a non-empty array")
    ids: set[str] = set()
    shape_ids = set(shapes)
    for index, action in enumerate(actions):
        field = f"motion.actions[{index}]"
        if not isinstance(action, dict):
            fail(f"{field} must be an object")
        action_id = require_id(action.get("id"), f"{field}.id")
        if action_id in ids:
            fail(f"duplicate motion action id: {action_id}")
        ids.add(action_id)
        require_number(action.get("duration"), f"{field}.duration", 1e-9, 60.0)
        frames = action.get("frames")
        if not isinstance(frames, list) or len(frames) < 2:
            fail(f"{field}.frames must contain at least two frames")
        part_count: int | None = None
        times: list[float] = []
        for frame_index, frame in enumerate(frames):
            item = f"{field}.frames[{frame_index}]"
            times.append(require_number(frame.get("t") if isinstance(frame, dict) else None, f"{item}.t", 0.0, 60.0))
            if version == 2:
                validate_free_frame(frame, shape_ids, item)
            else:
                part_count = validate_radial_frame(frame, shape_ids, item, part_count)
        if abs(times[0]) > 1e-9:
            fail(f"{field} must begin at t = 0")
        if any(b <= a for a, b in zip(times, times[1:])):
            fail(f"{field} frame times must be strictly increasing")
        if times[-1] > float(action["duration"]) + 1e-9:
            fail(f"{field} ends after its duration")
        if version == 2 and all(not frame.get("layers") for frame in frames):
            fail(f"{field} has nothing to draw")

    bindings = value.get("bindings", {})
    if not isinstance(bindings, dict):
        fail("motion.bindings must be an object")
    for beat, action_id in bindings.items():
        if beat not in {"rest", "idle", "thinking", "alert", "tap"}:
            fail(f"unknown motion binding: {beat}")
        if action_id not in ids:
            fail(f"motion binding {beat} must name an action")
    return ids


def collect_assets(motion: dict[str, Any]) -> list[str]:
    seen: list[str] = []
    for shape in motion.get("shapes", {}).values():
        if isinstance(shape, dict) and shape.get("kind") == "image":
            src = shape.get("src")
            if isinstance(src, str) and src not in seen:
                seen.append(src)
    return sorted(seen)


def read_asset(root: Path, src: str) -> bytes:
    path = root / src
    if not path.is_file():
        fail(f"missing asset: {src} (looked in {root})")
    data = path.read_bytes()
    if len(data) > MAX_IMAGE_BYTES:
        fail(f"{src} exceeds {MAX_IMAGE_BYTES} bytes")
    png = data[:4] == b"\x89PNG"
    jpeg = data[:3] == b"\xff\xd8\xff"
    webp = data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    if not (png or jpeg or webp):
        fail(f"{src} is not a readable PNG, JPEG or WebP")
    return data


def validate_range(value: Any, field: str, low: int, high: int) -> None:
    if not isinstance(value, list) or len(value) != 2:
        fail(f"{field} must be [start, end]")
    if not all(isinstance(item, int) and low <= item <= high for item in value):
        fail(f"{field} values must be integers in {low}..{high}")
    if value[0] > value[1]:
        fail(f"{field} start must not exceed end")


def validate_persona(value: dict[str, Any], action_ids: set[str]) -> None:
    prompt = value.get("prompt", "")
    if not isinstance(prompt, str):
        fail("persona.prompt must be text")
    display_name = value.get("display_name")
    if display_name is not None:
        if not isinstance(display_name, str) or not display_name.strip():
            fail("persona.display_name must be non-empty text")
        if len(display_name.strip()) > 24:
            fail("persona.display_name must be 24 characters or fewer")
    if prompt.strip().startswith(("你是", "我是", "我叫", "你叫")) and not display_name:
        print(
            "warning: persona.prompt opens with an identity claim ('你是…') but no "
            "display_name is set. The app overrides its assistant name with "
            "display_name when present; without it, the prompt's claimed identity and "
            "the assistant's real name disagree, which reads as two inconsistent "
            "personas in a long conversation. Either set display_name to match what "
            "the prompt claims, or rewrite prompt to describe voice/tone in third "
            "person instead of asserting an identity. See references/package-format.md.",
        )
    catchphrases = value.get("catchphrases", [])
    if not isinstance(catchphrases, list) or not all(isinstance(item, str) and item.strip() for item in catchphrases):
        fail("persona.catchphrases must be an array of non-empty strings")
    mix = value.get("chatter_mix", 0.4)
    if not isinstance(mix, (int, float)) or isinstance(mix, bool) or not math.isfinite(float(mix)) or not 0.0 <= float(mix) <= 1.0:
        fail("persona.chatter_mix must be between 0 and 1")
    chatter = value.get("chatter", [])
    if not isinstance(chatter, list):
        fail("persona.chatter must be an array")
    ids: set[str] = set()
    for index, line in enumerate(chatter):
        field = f"persona.chatter[{index}]"
        if not isinstance(line, dict):
            fail(f"{field} must be an object")
        line_id = require_id(line.get("id"), f"{field}.id")
        if line_id in ids:
            fail(f"duplicate chatter id: {line_id}")
        ids.add(line_id)
        require_text(line.get("text"), f"{field}.text")
        if "hours" in line:
            validate_range(line["hours"], f"{field}.hours", 0, 23)
        if "months" in line:
            validate_range(line["months"], f"{field}.months", 1, 12)
        if "weekdays" in line:
            weekdays = line["weekdays"]
            if not isinstance(weekdays, list) or not weekdays or not all(isinstance(day, int) and 1 <= day <= 7 for day in weekdays):
                fail(f"{field}.weekdays must contain ISO weekday numbers 1..7")
        if "weight" in line:
            weight = line["weight"]
            if not isinstance(weight, (int, float)) or isinstance(weight, bool) or not math.isfinite(float(weight)) or float(weight) <= 0.0:
                fail(f"{field}.weight must be positive")
        if "action" in line and line["action"] not in action_ids:
            fail(f"{field}.action must name a motion action")


def canonical_json(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def write_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data, compresslevel=9)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--motion", required=True, type=Path)
    parser.add_argument("--persona", type=Path)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--assets-root",
        type=Path,
        help="directory holding images/ referenced by motion.json (default: the motion.json folder)",
    )
    args = parser.parse_args()

    skin_id, manifest, version = validate_manifest(load_json(args.manifest, "manifest.json"))
    motion = load_json(args.motion, "motion.json")
    action_ids = validate_motion(motion, skin_id, version)

    assets_root = args.assets_root or args.motion.parent
    payloads: dict[str, bytes] = {"motion.json": canonical_json(motion)}
    for src in collect_assets(motion):
        payloads[src] = read_asset(assets_root, src)
    if args.persona is not None:
        persona = load_json(args.persona, "persona.json")
        validate_persona(persona, action_ids)
        payloads["persona.json"] = canonical_json(persona)

    manifest["files"] = {name: f"sha256:{hashlib.sha256(data).hexdigest()}" for name, data in sorted(payloads.items())}
    entries = {"manifest.json": canonical_json(manifest), **payloads}
    total = sum(len(data) for data in entries.values())
    if total > MAX_TOTAL_BYTES:
        fail(f"skin is {total} bytes, over the {MAX_TOTAL_BYTES} byte limit")

    args.directory.mkdir(parents=True, exist_ok=True)
    for stale_name in {"manifest.json", "motion.json", "persona.json"} - entries.keys():
        stale = args.directory / stale_name
        if stale.exists():
            stale.unlink()
    images_dir = args.directory / "images"
    if images_dir.exists():
        shutil.rmtree(images_dir)
    for name, data in sorted(entries.items()):
        target = args.directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w") as archive:
        for name, data in sorted(entries.items()):
            write_entry(archive, name, data)

    print(f"packaged {skin_id} ({RENDERERS[version]}) -> {args.output}")
    print(f"standard directory -> {args.directory}")
    print("entries: " + ", ".join(sorted(entries)))


if __name__ == "__main__":
    main()
