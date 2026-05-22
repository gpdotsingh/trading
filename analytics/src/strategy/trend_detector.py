"""
Trend detection from the momentum signal.

Trend direction  →  compare M_t against fixed thresholds θ_buy / θ_sell.
Trend strength   →  |z-score| of M_t relative to recent momentum history.
Confidence       →  blends strength + directional consistency + heavy-tail bonus.
"""

import logging
from enum import Enum

import numpy as np

from ..models.momentum_state import MomentumState

logger = logging.getLogger(__name__)


class TrendDirection(Enum):
    BULLISH = "BULLISH"
    BEARISH = "BEARISH"
    NEUTRAL = "NEUTRAL"


class TrendStrength(Enum):
    STRONG = "STRONG"
    MODERATE = "MODERATE"
    WEAK = "WEAK"


class TrendDetector:

    def __init__(self, buy_threshold: float, sell_threshold: float) -> None:
        self._buy_threshold = buy_threshold
        self._sell_threshold = sell_threshold

    # ── direction ────────────────────────────────────────────────────────────────

    def detect_direction(self, state: MomentumState) -> TrendDirection:
        m = state.current_momentum
        if m > self._buy_threshold:
            return TrendDirection.BULLISH
        if m < self._sell_threshold:
            return TrendDirection.BEARISH
        return TrendDirection.NEUTRAL

    # ── strength ─────────────────────────────────────────────────────────────────

    def compute_strength(self, state: MomentumState) -> TrendStrength:
        history = state.get_momentum_array()
        if len(history) < 3:
            return TrendStrength.WEAK
        std = float(np.std(history))
        if std < 1e-10:
            return TrendStrength.WEAK
        abs_z = abs(state.current_momentum) / std
        if abs_z > 2.0:
            return TrendStrength.STRONG
        if abs_z > 1.0:
            return TrendStrength.MODERATE
        return TrendStrength.WEAK

    # ── confidence ───────────────────────────────────────────────────────────────

    def compute_confidence(self, state: MomentumState) -> float:
        direction = self.detect_direction(state)
        if direction == TrendDirection.NEUTRAL:
            return 0.0
        base = self._strength_to_base_confidence(self.compute_strength(state))
        consistency = self._directional_consistency(state)
        # Heavier tails (lower α) ↔ more persistent trends → small bonus
        heavy_tail_bonus = max(0.0, (2.0 - state.current_levy_alpha) / 2.0) * 0.10
        raw = base * consistency + heavy_tail_bonus
        return float(np.clip(raw, 0.0, 1.0))

    # ── human-readable summary ───────────────────────────────────────────────────

    def describe(self, state: MomentumState, direction: TrendDirection) -> str:
        return (
            f"{direction.value}: M={state.current_momentum:.5f}  "
            f"α={state.current_levy_alpha:.3f}  λ={state.current_decay:.3f}"
        )

    # ── private helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _strength_to_base_confidence(strength: TrendStrength) -> float:
        return {
            TrendStrength.STRONG: 0.80,
            TrendStrength.MODERATE: 0.50,
            TrendStrength.WEAK: 0.20,
        }[strength]

    @staticmethod
    def _directional_consistency(state: MomentumState) -> float:
        history = state.get_momentum_array()
        if len(history) < 3:
            return 0.5
        is_positive = state.current_momentum > 0
        agreeing = int(np.sum(history > 0)) if is_positive else int(np.sum(history < 0))
        return float(agreeing / len(history))
