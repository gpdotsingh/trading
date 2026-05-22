"""
SignalGenerator — top-level orchestrator for the Lévy trend-following strategy.

Per-tick flow:
  1. PriceTick arrives from the WebSocket message handler.
  2. Mid-price appended to per-symbol MomentumState.
  3. Once min_data_points are available, MomentumCalculator updates M_t.
  4. TrendDetector classifies direction + confidence.
  5. A TradingSignal is returned.
"""

import logging
from typing import Dict

from ..config.config_loader import ConfigLoader
from ..models.momentum_state import MomentumState
from ..models.price_tick import PriceTick
from ..models.trading_signal import SignalType, TradingSignal
from .momentum_calculator import MomentumCalculator
from .trend_detector import TrendDetector, TrendDirection

logger = logging.getLogger(__name__)


class SignalGenerator:

    def __init__(self, config: ConfigLoader) -> None:
        self._min_data_points = config.get_int("strategy.min_data_points", 10)
        self._window_size = config.get_int("strategy.window_size", 50)
        self._decay_min = config.get_float("strategy.momentum_decay_min", 0.80)
        self._decay_max = config.get_float("strategy.momentum_decay_max", 0.98)

        self._momentum_calculator = MomentumCalculator(
            decay_min=self._decay_min,
            decay_max=self._decay_max,
            volatility_lookback=config.get_int("strategy.volatility_lookback", 20),
        )
        self._trend_detector = TrendDetector(
            buy_threshold=config.get_float("strategy.signal_threshold_buy", 0.0015),
            sell_threshold=config.get_float("strategy.signal_threshold_sell", -0.0015),
        )
        self._states: Dict[str, MomentumState] = {}

    # ── public API ───────────────────────────────────────────────────────────────

    def process_tick(self, tick: PriceTick) -> TradingSignal:
        state = self._get_or_create_state(tick.symbol)
        state.ingest_price(tick.mid_price)

        if not state.has_sufficient_data(self._min_data_points):
            return self._hold_signal(tick, state, "Insufficient data — warming up")

        self._momentum_calculator.update(state, state.get_returns_array())
        return self._build_signal(tick, state)

    def all_states_summary(self) -> dict:
        return {
            sym: {
                "momentum": round(st.current_momentum, 6),
                "levy_alpha": round(st.current_levy_alpha, 4),
                "decay": round(st.current_decay, 4),
                "ticks": len(st.return_history),
            }
            for sym, st in self._states.items()
        }

    # ── private helpers ──────────────────────────────────────────────────────────

    def _get_or_create_state(self, symbol: str) -> MomentumState:
        if symbol not in self._states:
            self._states[symbol] = MomentumState(
                symbol=symbol,
                window_size=self._window_size,
                decay_min=self._decay_min,
                decay_max=self._decay_max,
            )
        return self._states[symbol]

    def _build_signal(self, tick: PriceTick, state: MomentumState) -> TradingSignal:
        direction = self._trend_detector.detect_direction(state)
        confidence = self._trend_detector.compute_confidence(state)
        reason = self._trend_detector.describe(state, direction)
        return TradingSignal(
            symbol=tick.symbol,
            signal_type=self._direction_to_signal_type(direction),
            momentum_value=state.current_momentum,
            levy_alpha=state.current_levy_alpha,
            confidence=confidence,
            timestamp=tick.timestamp,
            price=tick.mid_price,
            asset_type=tick.asset_type,
            reason=reason,
        )

    def _hold_signal(
        self, tick: PriceTick, state: MomentumState, reason: str
    ) -> TradingSignal:
        return TradingSignal(
            symbol=tick.symbol,
            signal_type=SignalType.HOLD,
            momentum_value=state.current_momentum,
            levy_alpha=state.current_levy_alpha,
            confidence=0.0,
            timestamp=tick.timestamp,
            price=tick.mid_price,
            asset_type=tick.asset_type,
            reason=reason,
        )

    @staticmethod
    def _direction_to_signal_type(direction: TrendDirection) -> SignalType:
        return {
            TrendDirection.BULLISH: SignalType.BUY,
            TrendDirection.BEARISH: SignalType.SELL,
            TrendDirection.NEUTRAL: SignalType.HOLD,
        }[direction]
