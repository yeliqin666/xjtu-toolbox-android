#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 Qwen3Guard-Gen-0.6B 给反馈正文打安全标签（Safe / Controversial / Unsafe）。

只看用户写的反馈，不看崩溃日志。模型加载失败或推理出错时返回 None，调用方按「未审核」处理，
绝不因为审核挂了就拦下反馈。
"""

from __future__ import annotations

import re
from typing import Optional

MODEL = "Qwen/Qwen3Guard-Gen-0.6B"
MAX_CHARS = 2000

_SAFETY_RE = re.compile(r"Safety:\s*(Safe|Unsafe|Controversial)", re.IGNORECASE)
_CATEGORY_RE = re.compile(r"Categories:\s*(.+)", re.IGNORECASE)

_model = None
_tokenizer = None
_broken = False


def _load() -> bool:
    global _model, _tokenizer, _broken
    if _model is not None:
        return True
    if _broken:
        return False
    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer

        _tokenizer = AutoTokenizer.from_pretrained(MODEL)
        _model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype="auto")
        _model.eval()
        return True
    except Exception as e:  # noqa: BLE001 —— 任何加载失败都降级为不审核
        print(f"::warning::审核模型加载失败，本次不审核：{e}", flush=True)
        _broken = True
        return False


def classify(text: str) -> Optional[tuple[str, list[str]]]:
    """返回 (Safe|Controversial|Unsafe, 类别列表)；不可用时返回 None。"""
    if not text.strip() or not _load():
        return None
    try:
        import torch

        messages = [{"role": "user", "content": text[:MAX_CHARS]}]
        prompt = _tokenizer.apply_chat_template(messages, tokenize=False)
        inputs = _tokenizer([prompt], return_tensors="pt")
        with torch.no_grad():
            out = _model.generate(**inputs, max_new_tokens=64, do_sample=False)
        reply = _tokenizer.decode(out[0][len(inputs.input_ids[0]):], skip_special_tokens=True)
    except Exception as e:  # noqa: BLE001
        print(f"::warning::审核推理失败：{e}", flush=True)
        return None
    m = _SAFETY_RE.search(reply)
    if not m:
        return None
    label = m.group(1).capitalize()
    cats: list[str] = []
    c = _CATEGORY_RE.search(reply)
    if c:
        cats = [x.strip() for x in c.group(1).split(",") if x.strip() and x.strip() != "None"]
    return label, cats
