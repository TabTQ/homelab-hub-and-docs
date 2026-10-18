"""Pure skill-matching helpers for the portfolio export tool.

No database access, no I/O, no third-party dependencies. Everything here is
deterministic so the skill mapping review is reproducible.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from difflib import SequenceMatcher

_SEPARATOR = re.compile(r"\s*/\s*|\s*,\s*|\s*&\s*")
_WHITESPACE = re.compile(r"\s+")
_FUZZY_RATIO = 0.80
_MAX_CANDIDATES = 3


@dataclass
class Classification:
    """Result of classifying one free-text skill string against canonical labels."""

    status: str
    proposed: list[str]
    candidates: list[tuple[str, float]]


def normalize(value) -> str:
    """Strip, collapse internal whitespace and casefold a string."""
    if value is None:
        return ""
    return _WHITESPACE.sub(" ", str(value).strip()).casefold()


def _contains_words(first: str, second: str) -> bool:
    """True if every word of `second` appears as a contiguous run in `first`."""
    words_first = first.split()
    words_second = second.split()
    if not words_second:
        return True
    if len(words_second) > len(words_first):
        return False
    for start in range(len(words_first) - len(words_second) + 1):
        if words_first[start : start + len(words_second)] == words_second:
            return True
    return False


def classify(text, canonical_labels) -> Classification:
    """Classify a free-text skill string against canonical skill labels.

    Statuses:
      - "exact":       normalised text equals a canonical label.
      - "split-exact": normalised text contains a separator and every
                       part is an exact match.
      - "fuzzy":       not exact/split-exact but at least one candidate has a
                       SequenceMatcher ratio >= 0.80 on normalised strings or
                       one normalised string fully contains the other as
                       whole words. Candidates are the top 3 by score.
      - "unmatched":   everything else.
    """
    labels: list[tuple[str, str]] = []
    seen_labels: set[str] = set()
    for label in canonical_labels or ():
        if label is None:
            continue
        norm_label = normalize(label)
        if not norm_label or norm_label in seen_labels:
            continue
        seen_labels.add(norm_label)
        labels.append((norm_label, label))

    norm_text = normalize(text)
    if not norm_text:
        return Classification("unmatched", [], [])

    exact_by_norm = {norm_label: label for norm_label, label in labels}
    if norm_text in exact_by_norm:
        return Classification("exact", [exact_by_norm[norm_text]], [])

    if _SEPARATOR.search(norm_text):
        parts = [part.strip() for part in _SEPARATOR.split(norm_text) if part.strip()]
        proposed: list[str] = []
        all_exact = True
        for part in parts:
            if part not in exact_by_norm:
                all_exact = False
                break
            label = exact_by_norm[part]
            if label not in proposed:
                proposed.append(label)
        if all_exact and proposed:
            return Classification("split-exact", proposed, [])

    scored: list[tuple[str, float]] = []
    for norm_label, label in labels:
        ratio = SequenceMatcher(None, norm_text, norm_label).ratio()
        if (
            ratio >= _FUZZY_RATIO
            or _contains_words(norm_text, norm_label)
            or _contains_words(norm_label, norm_text)
        ):
            scored.append((label, round(ratio, 4)))
    if scored:
        scored.sort(key=lambda item: (-item[1], item[0]))
        return Classification("fuzzy", [], scored[:_MAX_CANDIDATES])

    return Classification("unmatched", [], [])