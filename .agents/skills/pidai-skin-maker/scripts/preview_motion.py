#!/usr/bin/env python3
"""Render 屁岱 motion previews and measure continuity.

The free format (`layers`) draws whatever the spec declares: any number of
subpaths, holes, open strokes, arbitrary colors, separate pieces, bitmaps. The
older radial format (`shape` + 64 radii) is still accepted and is translated to
the same cubic representation, so both preview through one renderer.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import math
import re
from dataclasses import dataclass, field as dc_field
from pathlib import Path
from typing import Any, NoReturn


SAMPLES = 64
SEGMENT_SAMPLES = 8
ID_RE = re.compile(r"^[a-z][a-z0-9_-]{0,63}$")
KEY_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
IMAGE_RE = re.compile(r"^images/[A-Za-z0-9_-]{1,64}\.(png|jpg|jpeg|webp)$")
EASING = {
    "linear": "0 0 1 1",
    "ease-in": "0.42 0 1 1",
    "ease-out": "0 0 0.58 1",
    "ease-in-out": "0.42 0 0.58 1",
}
PAPER = "#F1EFE9"
DEFAULT_INK = "#0A0A0C"
KAPPA = 0.5522847498307936


def fail(message: str) -> NoReturn:
    raise SystemExit(f"error: {message}")


def finite(value: Any, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        fail(f"{field} must be a finite number")
    return float(value)


def positive(value: Any, field: str) -> float:
    result = finite(value, field)
    if result <= 0.0:
        fail(f"{field} must be positive")
    return result


def unit(value: Any, field: str) -> float:
    result = finite(value, field)
    if not 0.0 <= result <= 1.0:
        fail(f"{field} must be between 0 and 1")
    return result


# --------------------------------------------------------------------------
# Geometry: every curve is stored as cubic segments so two outlines with the
# same structure interpolate control point by control point.
# --------------------------------------------------------------------------


@dataclass
class Sub:
    pts: list[float]
    closed: bool

    @property
    def segments(self) -> int:
        return (len(self.pts) - 2) // 6


@dataclass
class Outline:
    subs: list[Sub]

    @property
    def signature(self) -> str:
        return "|".join(f"{s.segments}{'c' if s.closed else 'o'}" for s in self.subs)

    def sample(self) -> list[tuple[float, float]]:
        """Points along the curve, for measuring how far the shape travelled."""
        out: list[tuple[float, float]] = []
        for sub in self.subs:
            p = sub.pts
            for index in range(sub.segments):
                base = 2 + index * 6
                x0, y0 = (p[0], p[1]) if index == 0 else (p[base - 2], p[base - 1])
                for step in range(SEGMENT_SAMPLES):
                    t = step / SEGMENT_SAMPLES
                    out.append(cubic_at(x0, y0, p[base], p[base + 1], p[base + 2], p[base + 3], p[base + 4], p[base + 5], t))
        return out


def cubic_at(x0, y0, x1, y1, x2, y2, x3, y3, t):  # noqa: ANN001, ANN201
    u = 1.0 - t
    a, b, c, d = u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t
    return (a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3)


class Builder:
    def __init__(self) -> None:
        self.done: list[Sub] = []
        self.cur: list[float] | None = None
        self.sx = self.sy = self.x = self.y = 0.0

    def move_to(self, x: float, y: float) -> None:
        self.flush(False)
        self.cur = [x, y]
        self.sx, self.sy, self.x, self.y = x, y, x, y

    def line_to(self, x: float, y: float) -> None:
        self.cubic_to(
            self.x + (x - self.x) / 3.0, self.y + (y - self.y) / 3.0,
            self.x + (x - self.x) * 2.0 / 3.0, self.y + (y - self.y) * 2.0 / 3.0,
            x, y,
        )

    def quad_to(self, qx: float, qy: float, x: float, y: float) -> None:
        self.cubic_to(
            self.x + (qx - self.x) * 2.0 / 3.0, self.y + (qy - self.y) * 2.0 / 3.0,
            x + (qx - x) * 2.0 / 3.0, y + (qy - y) * 2.0 / 3.0,
            x, y,
        )

    def cubic_to(self, c1x: float, c1y: float, c2x: float, c2y: float, x: float, y: float) -> None:
        if self.cur is None:
            self.cur = [self.x, self.y]
            self.sx, self.sy = self.x, self.y
        self.cur.extend([c1x, c1y, c2x, c2y, x, y])
        self.x, self.y = x, y

    def arc_to(self, rx: float, ry: float, rot_deg: float, large: bool, sweep: bool, x: float, y: float) -> None:
        x1, y1 = self.x, self.y
        if rx == 0.0 or ry == 0.0 or (x1 == x and y1 == y):
            self.line_to(x, y)
            return
        rx, ry = abs(rx), abs(ry)
        phi = math.radians(rot_deg)
        co, si = math.cos(phi), math.sin(phi)
        dx2, dy2 = (x1 - x) / 2.0, (y1 - y) / 2.0
        x1p = co * dx2 + si * dy2
        y1p = -si * dx2 + co * dy2
        lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if lam > 1.0:
            k = math.sqrt(lam)
            rx, ry = rx * k, ry * k
        denom = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        factor = 0.0 if denom <= 0.0 else max(0.0, (rx * rx * ry * ry - denom) / denom)
        coef = math.sqrt(factor)
        if large == sweep:
            coef = -coef
        cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
        cx = co * cxp - si * cyp + (x1 + x) / 2.0
        cy = si * cxp + co * cyp + (y1 + y) / 2.0
        ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
        vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry
        theta = angle_between(1.0, 0.0, ux, uy)
        delta = angle_between(ux, uy, vx, vy)
        if not sweep and delta > 0.0:
            delta -= math.tau
        if sweep and delta < 0.0:
            delta += math.tau
        steps = max(1, math.ceil(abs(delta) / (math.pi / 2) - 1e-9))
        step = delta / steps
        alpha = 4.0 / 3.0 * math.tan(step / 4.0)
        for _ in range(steps):
            nxt = theta + step
            ca, sa, cb, sb = math.cos(theta), math.sin(theta), math.cos(nxt), math.sin(nxt)
            ax = cx + rx * ca * co - ry * sa * si
            ay = cy + rx * ca * si + ry * sa * co
            bx = cx + rx * cb * co - ry * sb * si
            by = cy + rx * cb * si + ry * sb * co
            dax, day = -rx * sa * co - ry * ca * si, -rx * sa * si + ry * ca * co
            dbx, dby = -rx * sb * co - ry * cb * si, -rx * sb * si + ry * cb * co
            self.x, self.y = ax, ay
            self.cubic_to(ax + alpha * dax, ay + alpha * day, bx - alpha * dbx, by - alpha * dby, bx, by)
            theta = nxt
        self.x, self.y = x, y

    def close(self) -> None:
        if self.cur is None:
            return
        if math.hypot(self.x - self.sx, self.y - self.sy) > 1e-9:
            self.line_to(self.sx, self.sy)
        if len(self.cur) >= 8:
            self.done.append(Sub(list(self.cur), True))
        self.cur = None
        self.x, self.y = self.sx, self.sy

    def flush(self, closed: bool) -> None:
        if self.cur is not None and len(self.cur) >= 8:
            self.done.append(Sub(list(self.cur), closed))
        self.cur = None

    def build(self) -> Outline:
        self.flush(False)
        return Outline(self.done)


def angle_between(ux: float, uy: float, vx: float, vy: float) -> float:
    n = math.hypot(ux, uy) * math.hypot(vx, vy)
    if n == 0.0:
        return 0.0
    cosine = max(-1.0, min(1.0, (ux * vx + uy * vy) / n))
    sign = -1.0 if (ux * vy - uy * vx) < 0 else 1.0
    return sign * math.acos(cosine)


NUMBER_RE = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def parse_path(d: str, field: str) -> Outline:
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?", d)
    if not tokens:
        fail(f"{field}.d is empty")
    b = Builder()
    i = 0
    command = ""
    last_c: tuple[float, float] | None = None
    last_q: tuple[float, float] | None = None
    started = False

    def number() -> float:
        nonlocal i
        if i >= len(tokens) or re.fullmatch(r"[A-Za-z]", tokens[i]):
            fail(f"{field}.d ran out of numbers after {command!r}")
        value = float(tokens[i])
        i += 1
        if not math.isfinite(value):
            fail(f"{field}.d has a non-finite number")
        return value

    def flag() -> bool:
        value = number()
        if value not in (0.0, 1.0):
            fail(f"{field}.d arc flags must be 0 or 1")
        return value == 1.0

    while i < len(tokens):
        if re.fullmatch(r"[A-Za-z]", tokens[i]):
            command = tokens[i]
            i += 1
            if command.upper() not in "MLHVCSQTAZ":
                fail(f"{field}.d uses unsupported command {command!r}")
        elif not command:
            fail(f"{field}.d must start with a command letter")
        elif command == "M":
            command = "L"
        elif command == "m":
            command = "l"
        if command.upper() == "Z":
            b.close()
            last_c = last_q = None
            continue
        if i >= len(tokens):
            fail(f"{field}.d ends after {command!r} with no parameters")
        rel = command.islower()
        ox, oy = (b.x, b.y) if rel else (0.0, 0.0)
        upper = command.upper()
        if upper == "M":
            b.move_to(ox + number(), oy + number())
            started = True
        elif not started:
            fail(f"{field}.d must start with M")
        elif upper == "L":
            b.line_to(ox + number(), oy + number())
        elif upper == "H":
            b.line_to(ox + number(), b.y)
        elif upper == "V":
            b.line_to(b.x, oy + number())
        elif upper == "C":
            c1 = (ox + number(), oy + number())
            c2 = (ox + number(), oy + number())
            b.cubic_to(c1[0], c1[1], c2[0], c2[1], ox + number(), oy + number())
            last_c = c2
        elif upper == "S":
            c1 = (2 * b.x - last_c[0], 2 * b.y - last_c[1]) if last_c else (b.x, b.y)
            c2 = (ox + number(), oy + number())
            b.cubic_to(c1[0], c1[1], c2[0], c2[1], ox + number(), oy + number())
            last_c = c2
        elif upper == "Q":
            q = (ox + number(), oy + number())
            b.quad_to(q[0], q[1], ox + number(), oy + number())
            last_q = q
        elif upper == "T":
            q = (2 * b.x - last_q[0], 2 * b.y - last_q[1]) if last_q else (b.x, b.y)
            b.quad_to(q[0], q[1], ox + number(), oy + number())
            last_q = q
        elif upper == "A":
            rx, ry, rot = number(), number(), number()
            large, sweep = flag(), flag()
            b.arc_to(rx, ry, rot, large, sweep, ox + number(), oy + number())
        if upper not in ("C", "S"):
            last_c = None
        if upper not in ("Q", "T"):
            last_q = None
    outline = b.build()
    if not outline.subs:
        fail(f"{field}.d has nothing to draw")
    return outline


def outline_from_points(points: list[tuple[float, float]], closed: bool) -> Outline:
    b = Builder()
    b.move_to(*points[0])
    for x, y in points[1:]:
        b.line_to(x, y)
    if closed:
        b.close()
    return b.build()


def outline_from_ellipse(cx: float, cy: float, rx: float, ry: float) -> Outline:
    b = Builder()
    b.move_to(cx + rx, cy)
    b.cubic_to(cx + rx, cy + ry * KAPPA, cx + rx * KAPPA, cy + ry, cx, cy + ry)
    b.cubic_to(cx - rx * KAPPA, cy + ry, cx - rx, cy + ry * KAPPA, cx - rx, cy)
    b.cubic_to(cx - rx, cy - ry * KAPPA, cx - rx * KAPPA, cy - ry, cx, cy - ry)
    b.cubic_to(cx + rx * KAPPA, cy - ry, cx + rx, cy - ry * KAPPA, cx + rx, cy)
    b.close()
    return b.build()


def outline_from_rect(x: float, y: float, w: float, h: float, radius: float) -> Outline:
    r = max(0.0, min(radius, min(abs(w), abs(h)) / 2.0))
    b = Builder()
    if r <= 0.0:
        b.move_to(x, y)
        b.line_to(x + w, y)
        b.line_to(x + w, y + h)
        b.line_to(x, y + h)
        b.close()
        return b.build()
    k = r * KAPPA
    b.move_to(x + r, y)
    b.line_to(x + w - r, y)
    b.cubic_to(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
    b.line_to(x + w, y + h - r)
    b.cubic_to(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
    b.line_to(x + r, y + h)
    b.cubic_to(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
    b.line_to(x, y + r)
    b.cubic_to(x, y + r - k, x + r - k, y, x + r, y)
    b.close()
    return b.build()


def outline_from_radii(radii: list[float]) -> Outline:
    n = len(radii)
    xs = [radii[i] * math.cos(i / n * math.tau) for i in range(n)]
    ys = [radii[i] * math.sin(i / n * math.tau) for i in range(n)]
    tension = 1.0 / 6.0
    pts = [xs[0], ys[0]]
    for i in range(n):
        p0, p2, p3 = (i - 1) % n, (i + 1) % n, (i + 2) % n
        pts.extend([
            xs[i] + (xs[p2] - xs[p0]) * tension, ys[i] + (ys[p2] - ys[p0]) * tension,
            xs[p2] - (xs[p3] - xs[i]) * tension, ys[p2] - (ys[p3] - ys[i]) * tension,
            xs[p2], ys[p2],
        ])
    return Outline([Sub(pts, True)])


Matrix = tuple[float, float, float, float, float, float]


def matrix_of(cx: float, cy: float, sx: float, sy: float, rot: float, px: float = 0.0, py: float = 0.0) -> Matrix:
    co, si = math.cos(rot), math.sin(rot)
    a, b, c, d = co * sx, si * sx, -si * sy, co * sy
    return (a, b, c, d, px + cx - (a * px + c * py), py + cy - (b * px + d * py))


def matrix_radial(cx: float, cy: float, sx: float, sy: float, rot: float) -> Matrix:
    co, si = math.cos(rot), math.sin(rot)
    return (sx * co, sy * si, -sx * si, sy * co, cx, cy)


def apply_matrix(outline: Outline, m: Matrix) -> Outline:
    a, b, c, d, e, f = m
    subs = []
    for sub in outline.subs:
        pts = []
        for i in range(0, len(sub.pts), 2):
            x, y = sub.pts[i], sub.pts[i + 1]
            pts.extend([a * x + c * y + e, b * x + d * y + f])
        subs.append(Sub(pts, sub.closed))
    return Outline(subs)


def outline_to_d(outline: Outline, scale: float, center: float) -> str:
    out: list[str] = []
    for sub in outline.subs:
        p = sub.pts
        out.append(f"M {center + p[0] * scale:.3f} {center + p[1] * scale:.3f}")
        for i in range(2, len(p), 6):
            out.append(
                f"C {center + p[i] * scale:.3f} {center + p[i + 1] * scale:.3f}"
                f" {center + p[i + 2] * scale:.3f} {center + p[i + 3] * scale:.3f}"
                f" {center + p[i + 4] * scale:.3f} {center + p[i + 5] * scale:.3f}"
            )
        if sub.closed:
            out.append("Z")
    return " ".join(out)


# --------------------------------------------------------------------------
# Spec model
# --------------------------------------------------------------------------


@dataclass
class Shape:
    id: str
    outline: Outline | None
    image: str | None = None
    image_w: float = 0.0
    image_h: float = 0.0
    fill: str = "ink"
    stroke: str = "none"
    stroke_width: float = 0.04
    even_odd: bool = False
    cap: str = "round"
    join: str = "round"


@dataclass
class Layer:
    key: str
    shape: Shape
    cx: float = 0.0
    cy: float = 0.0
    sx: float = 1.0
    sy: float = 1.0
    rot: float = 0.0
    pivot_x: float = 0.0
    pivot_y: float = 0.0
    alpha: float = 1.0
    fill: str | None = None
    stroke: str | None = None
    stroke_width: float | None = None
    radial: bool = False

    def placed(self) -> Outline | None:
        if self.shape.outline is None:
            return None
        m = matrix_radial(self.cx, self.cy, self.sx, self.sy, self.rot) if self.radial else \
            matrix_of(self.cx, self.cy, self.sx, self.sy, self.rot, self.pivot_x, self.pivot_y)
        return apply_matrix(self.shape.outline, m)

    def paint(self, name: str) -> str:
        return (self.fill if name == "fill" else self.stroke) or \
            (self.shape.fill if name == "fill" else self.shape.stroke)

    def width(self) -> float:
        return self.shape.stroke_width if self.stroke_width is None else self.stroke_width


@dataclass
class Frame:
    t: float
    ease: str
    layers: list[Layer] = dc_field(default_factory=list)


def parse_paint(raw: Any, field: str, default: str | None) -> str | None:
    if raw is None:
        return default
    if not isinstance(raw, str):
        fail(f"{field} must be a string")
    value = raw.strip().lower()
    if value in ("none", "transparent", "ink", "paper"):
        return value
    if not re.fullmatch(r"#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})", value):
        fail(f"{field} must be ink, paper, none, or #RGB / #RRGGBB / #RRGGBBAA")
    return value


def resolve_paint(value: str, ink: str) -> tuple[str, float] | None:
    """SVG needs a color plus a separate opacity for #RRGGBBAA."""
    if value in ("none", "transparent"):
        return None
    if value == "ink":
        return (ink, 1.0)
    if value == "paper":
        return (PAPER, 1.0)
    if len(value) == 4:
        return ("#" + "".join(ch * 2 for ch in value[1:]), 1.0)
    if len(value) == 9:
        return (value[:7], int(value[7:9], 16) / 255.0)
    return (value, 1.0)


def parse_free_shape(raw: Any, name: str, base: Path) -> Shape:
    field = f"shapes.{name}"
    if not isinstance(raw, dict):
        fail(f"{field} must be an object")
    kind = raw.get("kind")
    if kind == "image":
        src = raw.get("src")
        if not isinstance(src, str) or not IMAGE_RE.fullmatch(src):
            fail(f"{field}.src must be images/<name>.png|jpg|jpeg|webp")
        if not (base / src).is_file():
            fail(f"{field}.src is missing on disk: {src}")
        return Shape(name, None, src, positive(raw.get("w"), f"{field}.w"), positive(raw.get("h"), f"{field}.h"))
    if kind == "path":
        d = raw.get("d")
        if not isinstance(d, str) or not d.strip():
            fail(f"{field}.d must be a non-empty string")
        outline = parse_path(d, field)
    elif kind in ("polygon", "polyline"):
        raw_points = raw.get("points")
        if not isinstance(raw_points, list) or len(raw_points) < 2:
            fail(f"{field}.points needs at least two points")
        points = []
        for index, item in enumerate(raw_points):
            if not isinstance(item, list) or len(item) != 2:
                fail(f"{field}.points[{index}] must be [x, y]")
            points.append((finite(item[0], f"{field}.points[{index}].x"), finite(item[1], f"{field}.points[{index}].y")))
        outline = outline_from_points(points, kind == "polygon")
    elif kind == "circle":
        r = positive(raw.get("r"), f"{field}.r")
        outline = outline_from_ellipse(finite(raw.get("cx", 0.0), f"{field}.cx"), finite(raw.get("cy", 0.0), f"{field}.cy"), r, r)
    elif kind == "ellipse":
        outline = outline_from_ellipse(
            finite(raw.get("cx", 0.0), f"{field}.cx"), finite(raw.get("cy", 0.0), f"{field}.cy"),
            positive(raw.get("rx"), f"{field}.rx"), positive(raw.get("ry"), f"{field}.ry"),
        )
    elif kind == "rect":
        outline = outline_from_rect(
            finite(raw.get("x"), f"{field}.x"), finite(raw.get("y"), f"{field}.y"),
            finite(raw.get("w"), f"{field}.w"), finite(raw.get("h"), f"{field}.h"),
            finite(raw.get("radius", 0.0), f"{field}.radius"),
        )
    else:
        fail(f"{field}.kind is not supported: {kind!r}")

    declared_fill = parse_paint(raw.get("fill"), f"{field}.fill", None)
    declared_stroke = parse_paint(raw.get("stroke"), f"{field}.stroke", None)
    fill = declared_fill if declared_fill is not None else ("none" if declared_stroke else "ink")
    stroke = declared_stroke or "none"
    if fill in ("none", "transparent") and stroke in ("none", "transparent"):
        fail(f"{field} has neither fill nor stroke")
    fill_rule = raw.get("fill_rule", "nonzero")
    if fill_rule not in ("nonzero", "evenodd"):
        fail(f"{field}.fill_rule must be nonzero or evenodd")
    cap = raw.get("stroke_cap", "round")
    if cap not in ("butt", "round", "square"):
        fail(f"{field}.stroke_cap is not supported")
    join = raw.get("stroke_join", "round")
    if join not in ("miter", "round", "bevel"):
        fail(f"{field}.stroke_join is not supported")
    return Shape(
        name, outline, None, 0.0, 0.0, fill, stroke,
        finite(raw.get("stroke_width", 0.04), f"{field}.stroke_width"),
        fill_rule == "evenodd", cap, join,
    )


def parse_radial_shape(raw: Any, name: str) -> Shape:
    field = f"shapes.{name}"
    if not isinstance(raw, dict):
        fail(f"{field} must be an object")
    kind = raw.get("kind")
    if kind == "circle":
        radii = [positive(raw.get("radius"), f"{field}.radius")] * SAMPLES
    elif kind == "radii":
        values = raw.get("values")
        if not isinstance(values, list) or len(values) < 3:
            fail(f"{field}.values needs at least three radii")
        floats = [positive(v, f"{field}.values[{i}]") for i, v in enumerate(values)]
        radii = []
        for i in range(SAMPLES):
            p = i / SAMPLES * len(floats)
            a = int(p) % len(floats)
            b = (a + 1) % len(floats)
            radii.append(floats[a] + (floats[b] - floats[a]) * (p - int(p)))
    elif kind == "outline":
        points = raw.get("points")
        if not isinstance(points, list) or len(points) < 3:
            fail(f"{field}.points needs at least three points")
        polygon = [(finite(p[0], f"{field}.x"), finite(p[1], f"{field}.y")) for p in points]
        radii = polygon_profile(polygon, field)
    elif kind == "circles":
        items = raw.get("items")
        if not isinstance(items, list) or not items:
            fail(f"{field}.items must be a non-empty array")
        discs = [(finite(c.get("x", 0.0), field), finite(c.get("y", 0.0), field), positive(c.get("r"), f"{field}.r")) for c in items]
        radii = circles_profile(discs)
    else:
        fail(f"{field}.kind is not supported in the radial format: {kind!r}")
    cap = raw.get("max_radius")
    if cap is not None:
        peak = max(radii)
        scale = positive(cap, f"{field}.max_radius") / peak
        radii = [r * scale for r in radii]
    return Shape(name, outline_from_radii(radii))


def polygon_profile(points: list[tuple[float, float]], field: str) -> list[float]:
    radii = []
    for index in range(SAMPLES):
        angle = index / SAMPLES * math.tau
        dx, dy = math.cos(angle), math.sin(angle)
        best = None
        for i in range(len(points)):
            ax, ay = points[i]
            bx, by = points[(i + 1) % len(points)]
            ex, ey = bx - ax, by - ay
            denom = dx * ey - dy * ex
            if abs(denom) < 1e-12:
                continue
            t = (ax * ey - ay * ex) / denom
            u = (ax * dy - ay * dx) / denom
            if t > 0 and -1e-9 <= u <= 1 + 1e-9 and (best is None or t > best):
                best = t
        if best is None:
            fail(f"{field} is not star-shaped around the origin")
        radii.append(best)
    return radii


def circles_profile(discs: list[tuple[float, float, float]]) -> list[float]:
    radii = []
    for index in range(SAMPLES):
        angle = index / SAMPLES * math.tau
        dx, dy = math.cos(angle), math.sin(angle)
        best = 0.0
        for cx, cy, r in discs:
            b = cx * dx + cy * dy
            c = cx * cx + cy * cy - r * r
            disc = b * b - c
            if disc < 0:
                continue
            best = max(best, b + math.sqrt(disc))
        radii.append(max(best, 1e-3))
    return radii


def parse_layer(raw: Any, shapes: dict[str, Shape], field: str, index: int) -> Layer:
    if not isinstance(raw, dict):
        fail(f"{field} must be an object")
    name = raw.get("shape")
    if not isinstance(name, str) or name not in shapes:
        fail(f"{field}.shape must name a declared shape")
    key = raw.get("key")
    if key is None:
        # The default carries '#', which authored keys may not use, so they never collide.
        key = f"{name}#{index}"
    elif not isinstance(key, str) or not KEY_RE.fullmatch(key):
        fail(f"{field}.key is invalid")
    width = raw.get("stroke_width")
    return Layer(
        key=key, shape=shapes[name],
        cx=finite(raw.get("cx", 0.0), f"{field}.cx"),
        cy=finite(raw.get("cy", 0.0), f"{field}.cy"),
        sx=positive(raw.get("sx", 1.0), f"{field}.sx"),
        sy=positive(raw.get("sy", 1.0), f"{field}.sy"),
        rot=math.radians(finite(raw.get("rot_deg", 0.0), f"{field}.rot_deg")),
        pivot_x=finite(raw.get("pivot_x", 0.0), f"{field}.pivot_x"),
        pivot_y=finite(raw.get("pivot_y", 0.0), f"{field}.pivot_y"),
        alpha=unit(raw.get("alpha", 1.0), f"{field}.alpha"),
        fill=parse_paint(raw.get("fill"), f"{field}.fill", None),
        stroke=parse_paint(raw.get("stroke"), f"{field}.stroke", None),
        stroke_width=None if width is None else finite(width, f"{field}.stroke_width"),
    )


def parse_frame(raw: Any, shapes: dict[str, Shape], field: str, radial: bool) -> Frame:
    if not isinstance(raw, dict):
        fail(f"{field} must be an object")
    ease = raw.get("ease", "linear")
    if ease not in EASING:
        fail(f"{field}.ease must be one of {', '.join(EASING)}")
    t = finite(raw.get("t"), f"{field}.t")
    if radial:
        body = parse_layer(raw, shapes, field, 0)
        body.key, body.radial = "body", True
        behind: list[Layer] = []
        front: list[Layer] = []
        raw_parts = raw.get("parts", [])
        if not isinstance(raw_parts, list):
            fail(f"{field}.parts must be an array")
        for index, item in enumerate(raw_parts):
            part = parse_layer(item, shapes, f"{field}.parts[{index}]", index)
            part.key, part.radial = f"part{index}", True
            (behind if bool(item.get("behind", False)) else front).append(part)
        return Frame(t, ease, behind + [body] + front)
    raw_layers = raw.get("layers")
    if not isinstance(raw_layers, list):
        fail(f"{field}.layers must be an array")
    layers = [parse_layer(item, shapes, f"{field}.layers[{index}]", index) for index, item in enumerate(raw_layers)]
    keys = [layer.key for layer in layers]
    if len(set(keys)) != len(keys):
        fail(f"{field} reuses a layer key")
    return Frame(t, ease, layers)


# --------------------------------------------------------------------------
# SVG output
# --------------------------------------------------------------------------


def paint_attrs(value: str, ink: str, prefix: str) -> str:
    resolved = resolve_paint(value, ink)
    if resolved is None:
        return f'{prefix}="none"'
    color, opacity = resolved
    attrs = f'{prefix}="{color}"'
    if opacity < 1.0:
        attrs += f' {prefix}-opacity="{opacity:.3f}"'
    return attrs


def runs_of(frames: list[Frame], key: str) -> list[tuple[int, int]]:
    """Maximal keyframe ranges over which this layer keeps one interpolatable shape."""
    runs: list[tuple[int, int]] = []
    start: int | None = None
    signature: str | None = None
    for index, frame in enumerate(frames):
        layer = next((item for item in frame.layers if item.key == key), None)
        current = None
        if layer is not None:
            placed = layer.placed()
            current = placed.signature if placed is not None else f"image:{layer.shape.image}"
        if current is None:
            if start is not None:
                runs.append((start, index - 1))
            start, signature = None, None
        elif current != signature:
            if start is not None:
                runs.append((start, index - 1))
            start, signature = index, current
    if start is not None:
        runs.append((start, len(frames) - 1))
    return runs


def data_uri(base: Path, src: str) -> str:
    mime = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp"}[src.rsplit(".", 1)[1].lower()]
    return f"data:{mime};base64," + base64.b64encode((base / src).read_bytes()).decode("ascii")


def layer_elements(
    frames: list[Frame],
    duration: float,
    ink: str,
    base: Path,
    scale: float,
    center: float,
) -> list[str]:
    times = [frame.t for frame in frames]
    key_times = ";".join(f"{t / duration:.8f}" for t in times)
    splines = ";".join(EASING[frame.ease] for frame in frames[:-1])
    order: list[str] = []
    for frame in frames:
        for layer in frame.layers:
            if layer.key not in order:
                order.append(layer.key)
    elements: list[str] = []
    for key in order:
        present = {index: next((item for item in frame.layers if item.key == key), None) for index, frame in enumerate(frames)}
        for run_index, (start, end) in enumerate(runs_of(frames, key)):
            anchor = present[start]
            assert anchor is not None
            opacity = []
            geometry = []
            for index in range(len(frames)):
                layer = present[index]
                inside = start <= index <= end
                fade_in = index == start - 1
                fade_out = index == end + 1
                opacity.append(0.0 if (fade_in or fade_out or layer is None) else (layer.alpha if inside else 0.0))
                source = present[min(max(index, start), end)]
                assert source is not None
                geometry.append(source)
            element_id = html.escape(f"{key}" if run_index == 0 else f"{key}~{run_index}")
            opacity_values = ";".join(f"{value:.4f}" for value in opacity)
            animate_opacity = (
                f'<animate attributeName="opacity" dur="{duration:.6f}s" repeatCount="indefinite"'
                f' calcMode="spline" keyTimes="{key_times}" keySplines="{splines}" values="{opacity_values}"/>'
            )
            if anchor.shape.image is not None:
                href = data_uri(base, anchor.shape.image)
                w = anchor.shape.image_w * scale
                h = anchor.shape.image_h * scale
                x = center + anchor.cx * scale - w / 2
                y = center + anchor.cy * scale - h / 2
                elements.append(
                    f'<image data-layer="{element_id}" href="{href}" x="{x:.3f}" y="{y:.3f}" width="{w:.3f}"'
                    f' height="{h:.3f}" opacity="{opacity[0]:.4f}" preserveAspectRatio="none">{animate_opacity}</image>'
                )
                continue
            values = ";".join(outline_to_d(layer.placed(), scale, center) for layer in geometry)
            fill = paint_attrs(anchor.paint("fill"), ink, "fill")
            stroke = paint_attrs(anchor.paint("stroke"), ink, "stroke")
            extra = ""
            if anchor.shape.even_odd:
                extra += ' fill-rule="evenodd"'
            if resolve_paint(anchor.paint("stroke"), ink) is not None:
                extra += (
                    f' stroke-width="{anchor.width() * scale:.3f}" stroke-linecap="{anchor.shape.cap}"'
                    f' stroke-linejoin="{anchor.shape.join}"'
                )
            elements.append(
                f'<path data-layer="{element_id}" d="{outline_to_d(geometry[0].placed(), scale, center)}" {fill} {stroke}{extra}'
                f' opacity="{opacity[0]:.4f}">'
                f'<animate attributeName="d" dur="{duration:.6f}s" repeatCount="indefinite" calcMode="spline"'
                f' keyTimes="{key_times}" keySplines="{splines}" values="{values}"/>{animate_opacity}</path>'
            )
    return elements


def action_svg(skin_id: str, action_id: str, duration: float, frames: list[Frame], ink: str, background: str, base: Path) -> str:
    animated = list(frames)
    if animated[-1].t < duration:
        last = animated[-1]
        animated.append(Frame(duration, last.ease, last.layers))
    body = "\n  ".join(layer_elements(animated, duration, ink, base, scale=180.0, center=256.0))
    title = html.escape(f"{skin_id} · {action_id}")
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="560" viewBox="0 0 512 560">
  <rect width="512" height="560" rx="32" fill="{background}"/>
  {body}
  <text x="256" y="535" text-anchor="middle" font-family="sans-serif" font-size="22" fill="#333333">{title}</text>
</svg>
'''


def static_frame_svg(frame: Frame, ink: str, base: Path, scale: float) -> str:
    out: list[str] = []
    for layer in frame.layers:
        if layer.alpha <= 0.002:
            continue
        if layer.shape.image is not None:
            w, h = layer.shape.image_w * scale, layer.shape.image_h * scale
            out.append(
                f'<image href="{data_uri(base, layer.shape.image)}" x="{layer.cx * scale - w / 2:.3f}"'
                f' y="{layer.cy * scale - h / 2:.3f}" width="{w:.3f}" height="{h:.3f}"'
                f' opacity="{layer.alpha:.3f}" preserveAspectRatio="none"/>'
            )
            continue
        fill = paint_attrs(layer.paint("fill"), ink, "fill")
        stroke = paint_attrs(layer.paint("stroke"), ink, "stroke")
        extra = ' fill-rule="evenodd"' if layer.shape.even_odd else ""
        if resolve_paint(layer.paint("stroke"), ink) is not None:
            extra += (
                f' stroke-width="{layer.width() * scale:.3f}" stroke-linecap="{layer.shape.cap}"'
                f' stroke-linejoin="{layer.shape.join}"'
            )
        out.append(f'<path d="{outline_to_d(layer.placed(), scale, 0.0)}" {fill} {stroke}{extra} opacity="{layer.alpha:.3f}"/>')
    return "".join(out)


def sheet_svg(actions: list[tuple[str, list[Frame]]], ink: str, background: str, base: Path) -> str:
    cell, label_width, row_height = 132, 130, 158
    columns = max(len(frames) for _, frames in actions)
    width = label_width + columns * cell + 24
    height = len(actions) * row_height + 24
    items = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="{width}" height="{height}" fill="{background}"/>',
    ]
    for row, (action_id, frames) in enumerate(actions):
        y = 12 + row * row_height
        items.append(f'<text x="16" y="{y + 72}" font-family="sans-serif" font-size="20" fill="#333333">{html.escape(action_id)}</text>')
        for column, frame in enumerate(frames):
            x = label_width + column * cell
            items.append(f'<g transform="translate({x + cell / 2:.1f} {y + 62:.1f})">{static_frame_svg(frame, ink, base, 48.0)}</g>')
            items.append(
                f'<text x="{x + cell / 2:.1f}" y="{y + 142}" text-anchor="middle" font-family="sans-serif"'
                f' font-size="15" fill="#666666">{frame.t:.2f}s · {len(frame.layers)} layer(s)</text>'
            )
    items.append("</svg>\n")
    return "\n".join(items)


# --------------------------------------------------------------------------
# Continuity measurement
# --------------------------------------------------------------------------


def layer_delta(a: Frame, b: Frame) -> tuple[float, list[str]]:
    """Largest sampled travel between two keyframes, plus the layers that crossfade."""
    by_a = {layer.key: layer for layer in a.layers}
    by_b = {layer.key: layer for layer in b.layers}
    worst = 0.0
    crossfades: list[str] = []
    for key, layer_b in by_b.items():
        layer_a = by_a.get(key)
        if layer_a is None:
            continue
        pa, pb = layer_a.placed(), layer_b.placed()
        if pa is None or pb is None:
            if layer_a.shape.image != layer_b.shape.image:
                crossfades.append(key)
            continue
        if pa.signature != pb.signature:
            crossfades.append(key)
            continue
        worst = max(worst, max(math.hypot(bx - ax, by - ay) for (ax, ay), (bx, by) in zip(pa.sample(), pb.sample())))
    return worst, crossfades


def seam_velocity(before: Frame, seam: Frame, start: Frame, after: Frame) -> float:
    before_dt, after_dt = seam.t - before.t, after.t
    if before_dt <= 0.0 or after_dt <= 0.0:
        return math.inf
    end_factor = {"linear": 1.0, "ease-in": 1.724, "ease-out": 0.0, "ease-in-out": 0.0}[before.ease]
    start_factor = {"linear": 1.0, "ease-in": 0.0, "ease-out": 1.724, "ease-in-out": 0.0}[start.ease]
    keys = {layer.key for layer in seam.layers} & {layer.key for layer in start.layers}
    result = 0.0
    for key in keys:
        samples = []
        for frame in (before, seam, start, after):
            layer = next((item for item in frame.layers if item.key == key), None)
            placed = layer.placed() if layer is not None else None
            samples.append(placed.sample() if placed is not None else None)
        if any(s is None for s in samples):
            continue
        if len({len(s) for s in samples}) != 1:
            continue
        for p0, p1, p2, p3 in zip(*samples):
            incoming = ((p1[0] - p0[0]) / before_dt * end_factor, (p1[1] - p0[1]) / before_dt * end_factor)
            outgoing = ((p3[0] - p2[0]) / after_dt * start_factor, (p3[1] - p2[1]) / after_dt * start_factor)
            result = max(result, math.hypot(outgoing[0] - incoming[0], outgoing[1] - incoming[1]))
    return result


ROTATION_SYMMETRY_TOL = 0.02
IDLE_MIN_TRAVEL = 0.06
RESPONSE_DEADLINE = 0.25
RESPONSE_TRAVEL = 0.05


def layer_travel(a: Frame, b: Frame, key: str) -> float:
    """How far this layer's outline actually moves between two keyframes."""
    la = next((item for item in a.layers if item.key == key), None)
    lb = next((item for item in b.layers if item.key == key), None)
    if la is None or lb is None:
        return 0.0
    pa, pb = la.placed(), lb.placed()
    if pa is None or pb is None or pa.signature != pb.signature:
        return 0.0
    return max(math.hypot(bx - ax, by - ay) for (ax, ay), (bx, by) in zip(pa.sample(), pb.sample()))


def action_travel(frames: list[Frame]) -> float:
    """Peak displacement of any layer from its own first-keyframe pose."""
    keys = {layer.key for frame in frames for layer in frame.layers}
    return max((layer_travel(frames[0], frame, key) for frame in frames[1:] for key in keys), default=0.0)


def rotation_is_invisible(frames: list[Frame], key: str) -> bool:
    """
    A shape that maps onto itself under its own rotation shows nothing when rotated.

    A ring, a disc or a regular rosette spun 360 degrees is a perfectly still picture.
    This is the check that would have caught the "thinking state does not animate" report.
    """
    poses = [next((item for item in frame.layers if item.key == key), None) for frame in frames]
    present = [p for p in poses if p is not None]
    if len(present) < 2:
        return False
    first = present[0]
    # Only worth flagging when rotation is the layer's dominant change.
    if max(abs(p.rot - first.rot) for p in present) < math.radians(20.0):
        return False
    moved = max(
        math.hypot(p.cx - first.cx, p.cy - first.cy) + abs(p.sx - first.sx) + abs(p.sy - first.sy)
        for p in present
    )
    if moved > 0.02:
        return False
    base = first.shape.outline
    if base is None:
        return False
    points = base.sample()
    if not points:
        return False
    span = max(math.hypot(x, y) for x, y in points) or 1.0
    for other in present[1:]:
        delta = other.rot - first.rot
        co, si = math.cos(delta), math.sin(delta)
        spun = [(x * co - y * si, x * si + y * co) for x, y in points]
        # Nearest-neighbour distance from each rotated point back to the original set.
        worst = max(min(math.hypot(px - qx, py - qy) for qx, qy in points) for px, py in spun)
        if worst / span > ROTATION_SYMMETRY_TOL:
            return False
    return True


def time_to_response(frames: list[Frame]) -> float | None:
    """When a one-shot action first moves enough to read as a reaction."""
    keys = {layer.key for frame in frames for layer in frame.layers}
    for frame in frames[1:]:
        travel = max((layer_travel(frames[0], frame, key) for key in keys), default=0.0)
        appeared = any(
            next((l for l in frame.layers if l.key == k), None) is not None
            and (next((l for l in frames[0].layers if l.key == k), None) is None
                 or next(l.alpha for l in frames[0].layers if l.key == k) < 0.05)
            and next(l.alpha for l in frame.layers if l.key == k) > 0.5
            for k in keys
        )
        if travel >= RESPONSE_TRAVEL or appeared:
            return frame.t
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    args = parser.parse_args()

    try:
        raw = json.loads(args.spec.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read spec: {exc}")
    if not isinstance(raw, dict):
        fail("spec root must be an object")
    base = args.spec.parent

    skin_id = raw.get("skin_id")
    if not isinstance(skin_id, str) or not ID_RE.fullmatch(skin_id):
        fail("skin_id must match ^[a-z][a-z0-9_-]{0,63}$")
    ink = resolve_paint(parse_paint(raw.get("color"), "color", "#0a0a0c") or DEFAULT_INK, DEFAULT_INK)[0]
    if raw.get("background") is not None:
        fail("background was removed: draw the backdrop as the bottom-most layer instead")
    background = PAPER

    raw_actions = raw.get("actions")
    if not isinstance(raw_actions, list) or not raw_actions:
        fail("actions must be a non-empty array")
    radial = all("layers" not in action_frame for action in raw_actions if isinstance(action, dict)
                 for action_frame in action.get("frames", []) if isinstance(action_frame, dict))

    raw_shapes = raw.get("shapes")
    if not isinstance(raw_shapes, dict) or not raw_shapes:
        fail("shapes must be a non-empty object")
    shapes: dict[str, Shape] = {}
    for name, shape in raw_shapes.items():
        if not isinstance(name, str) or not ID_RE.fullmatch(name):
            fail(f"invalid shape id: {name!r}")
        shapes[name] = parse_radial_shape(shape, name) if radial else parse_free_shape(shape, name, base)

    actions: list[tuple[str, float, bool, str | None, list[Frame]]] = []
    seen: set[str] = set()
    for action_index, action in enumerate(raw_actions):
        field = f"actions[{action_index}]"
        if not isinstance(action, dict):
            fail(f"{field} must be an object")
        action_id = action.get("id")
        if not isinstance(action_id, str) or not ID_RE.fullmatch(action_id):
            fail(f"{field}.id is invalid")
        if action_id in seen:
            fail(f"duplicate action id: {action_id}")
        seen.add(action_id)
        duration = positive(action.get("duration"), f"{field}.duration")
        loop = bool(action.get("loop", False))
        return_to = action.get("return_to")
        if return_to is not None and (not isinstance(return_to, str) or return_to not in shapes):
            fail(f"{field}.return_to must name a declared shape")
        raw_frames = action.get("frames")
        if not isinstance(raw_frames, list) or len(raw_frames) < 2:
            fail(f"{field}.frames must contain at least two frames")
        frames = [parse_frame(frame, shapes, f"{field}.frames[{index}]", radial) for index, frame in enumerate(raw_frames)]
        if abs(frames[0].t) > 1e-9:
            fail(f"{field} must begin at t = 0")
        if any(b.t <= a.t for a, b in zip(frames, frames[1:])):
            fail(f"{field} frame times must be strictly increasing")
        if frames[-1].t > duration + 1e-9:
            fail(f"{field} ends after its duration")
        if loop and abs(frames[-1].t - duration) > 1e-9:
            fail(f"{field} loop must include a closing frame at duration")
        if all(not frame.layers for frame in frames):
            fail(f"{field} has nothing to draw")
        actions.append((action_id, duration, loop, return_to, frames))

    action_by_id = {item[0]: item for item in actions}
    raw_transitions = raw.get("transitions", [])
    if not isinstance(raw_transitions, list):
        fail("transitions must be an array when provided")
    transitions: list[tuple[str, str, float, str]] = []
    for index, transition in enumerate(raw_transitions):
        field = f"transitions[{index}]"
        if not isinstance(transition, dict):
            fail(f"{field} must be an object")
        source, target = transition.get("from"), transition.get("to")
        if source not in action_by_id or target not in action_by_id:
            fail(f"{field} must reference declared action ids")
        transition_duration = positive(transition.get("duration"), f"{field}.duration")
        transition_ease = transition.get("ease", "ease-out")
        if transition_ease not in EASING:
            fail(f"{field}.ease must be one of {', '.join(EASING)}")
        transitions.append((source, target, transition_duration, transition_ease))

    args.out_dir.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {"skin_id": skin_id, "format": "radial" if radial else "free", "actions": [], "transitions": []}
    lines = [f"Skin: {skin_id}", f"Format: {'radial (legacy)' if radial else 'free'}", ""]
    sheet: list[tuple[str, list[Frame]]] = []

    for action_id, duration, loop, return_to, frames in actions:
        steps = []
        for first, second in zip(frames, frames[1:]):
            delta, crossfades = layer_delta(first, second)
            steps.append({
                "from": first.t, "to": second.t,
                "max_point_delta": delta,
                "max_speed": delta / (second.t - first.t),
                "crossfade_layers": crossfades,
            })
        item: dict[str, Any] = {"id": action_id, "loop": loop, "duration": duration, "transitions": steps}
        lines.append(f"[{action_id}] duration={duration:.3f}s loop={str(loop).lower()} layers={len({l.key for f in frames for l in f.layers})}")
        for step in steps:
            note = f", crossfade={','.join(step['crossfade_layers'])}" if step["crossfade_layers"] else ""
            lines.append(
                f"  {step['from']:.3f}s -> {step['to']:.3f}s: delta={step['max_point_delta']:.4f},"
                f" avg-bound={step['max_speed']:.4f}/s{note}"
            )

        # --- readability at navigation-icon size ---------------------------
        travel = action_travel(frames)
        item["peak_travel"] = travel
        lines.append(f"  peak travel: {travel:.3f} body radii")
        if loop and travel < IDLE_MIN_TRAVEL:
            lines.append(
                f"  REVIEW: a looping action moving only {travel:.3f} radii reads as static at 46 px"
                f" (aim for >= {IDLE_MIN_TRAVEL})"
            )
        invisible = [
            key for key in sorted({layer.key for frame in frames for layer in frame.layers})
            if rotation_is_invisible(frames, key)
        ]
        if invisible:
            item["invisible_rotation"] = invisible
            lines.append(
                f"  REVIEW: {', '.join(invisible)} only rotate, and their shape maps onto itself"
                " — spinning them shows nothing. Break the symmetry or move them."
            )
        if not loop:
            response = time_to_response(frames)
            item["time_to_response"] = response
            if response is None:
                lines.append("  REVIEW: nothing in this one-shot action ever reads as a reaction")
            else:
                lines.append(f"  first readable motion: {response:.3f}s")
                if response > RESPONSE_DEADLINE:
                    lines.append(
                        f"  REVIEW: {response:.3f}s before anything happens; a tap should react"
                        f" within {RESPONSE_DEADLINE}s"
                    )

        keys_first = {layer.key for layer in frames[0].layers}
        keys_last = {layer.key for layer in frames[-1].layers}
        if loop:
            seam_delta, seam_crossfades = layer_delta(frames[-1], frames[0])
            velocity = seam_velocity(frames[-2], frames[-1], frames[0], frames[1])
            # Compare the seam against the action's own keyframes. A circular orbit
            # approximated by N keyframes turns by the same amount at every vertex;
            # only a seam that is worse than the rest is a real cusp.
            interior = [
                seam_velocity(frames[i - 1], frames[i], frames[i + 1], frames[i + 2])
                for i in range(1, len(frames) - 2)
            ]
            interior = [v for v in interior if math.isfinite(v)]
            typical = sorted(interior)[len(interior) // 2] if interior else 0.0
            item["loop_seam"] = {
                "position_delta": seam_delta,
                "velocity_delta": velocity,
                "typical_keyframe_velocity_delta": typical,
                "crossfade_layers": seam_crossfades,
            }
            lines.append(
                f"  loop seam: position={seam_delta:.6f}, velocity={velocity:.4f}/s"
                f" (typical keyframe {typical:.4f}/s)"
            )
            if seam_delta > 0.001:
                lines.append("  REVIEW: loop positions differ; keep only if the jump is intentional")
            if velocity > 0.25 and velocity > typical * 2.0 + 0.1:
                lines.append("  REVIEW: motion direction or speed changes visibly at the seam")
            if keys_first != keys_last:
                lines.append(f"  REVIEW: loop adds/drops layers at the seam: {sorted(keys_first ^ keys_last)}")

        if return_to is not None:
            # Compare the main layer only: "body" for radial skins, otherwise the
            # bottom-most layer of the final keyframe.
            keys_present = [layer.key for layer in frames[-1].layers]
            settle_key = "body" if "body" in keys_present else (keys_present[0] if keys_present else "body")
            neutral = Frame(duration, "linear", [Layer(key=settle_key, shape=shapes[return_to], radial=radial)])
            settle, _ = layer_delta(frames[-1], neutral)
            item["return_to"] = {"shape": return_to, "position_delta": settle}
            lines.append(f"  return to {return_to}: position={settle:.6f}")
            if settle > 0.001:
                lines.append("  REVIEW: final frame differs from the declared return shape")

        lines.append("")
        report["actions"].append(item)
        sheet.append((action_id, frames))
        (args.out_dir / f"{action_id}.svg").write_text(
            action_svg(skin_id, action_id, duration, frames, ink, background, base), encoding="utf-8"
        )

    for source, target, transition_duration, transition_ease in transitions:
        start_frame = action_by_id[source][4][-1]
        end_frame = action_by_id[target][4][0]
        start = Frame(0.0, transition_ease, start_frame.layers)
        end = Frame(transition_duration, "linear", end_frame.layers)
        delta, crossfades = layer_delta(start, end)
        transition_id = f"{source}-to-{target}"
        report["transitions"].append({
            "from": source, "to": target, "duration": transition_duration,
            "max_point_delta": delta, "crossfade_layers": crossfades,
        })
        note = f", crossfade={','.join(crossfades)}" if crossfades else ""
        lines.append(f"[{transition_id}] duration={transition_duration:.3f}s delta={delta:.4f}{note}")
        (args.out_dir / f"{transition_id}.svg").write_text(
            action_svg(skin_id, transition_id, transition_duration, [start, end], ink, background, base), encoding="utf-8"
        )

    if transitions:
        lines.append("")

    (args.out_dir / "keyframes.svg").write_text(sheet_svg(sheet, ink, background, base), encoding="utf-8")
    (args.out_dir / "continuity.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out_dir / "continuity.txt").write_text("\n".join(lines), encoding="utf-8")

    print(f"generated {len(actions)} action preview(s) in {args.out_dir}")
    print(args.out_dir / "keyframes.svg")
    print(args.out_dir / "continuity.txt")


if __name__ == "__main__":
    main()
