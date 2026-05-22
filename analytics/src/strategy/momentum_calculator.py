"""
Dynamic Momentum Learning
─────────────────────────
The momentum signal M_t evolves as:

    M_t = λ_t · M_{t-1}  +  (1 - λ_t) · ŵ_t

where
  λ_t  = adaptive EWMA decay (high volatility → low λ → short memory)
  ŵ_t  = Lévy-weighted return:  Σ_i w_i · r_i

The decay λ_t adapts via a tanh-sigmoid of normalised realised volatility so
the strategy shortens its memory during turbulent regimes and lengthens it
during quiet, trending ones — the "dynamic learning" component.
"""

import logging

import numpy as np

from ..models.momentum_state import MomentumState
from .levy_process import compute_levy_weights, fit_levy_parameters

logger = logging.getLogger(__name__)


class MomentumCalculator:

    def __init__(
        self,
        decay_min: float,
        decay_max: float,
        volatility_lookback: int,
    ) -> None:
        self._decay_min = decay_min
        self._decay_max = decay_max
        self._volatility_lookback = volatility_lookback
        self._global_vol_ema: float = 0.01   # seed; updated via EMA
        self._vol_ema_alpha: float = 0.10    # smoothing factor for vol EMA

    # ── public API ───────────────────────────────────────────────────────────────

    def update(self, state: MomentumState, returns: np.ndarray) -> float:
        """Fit Lévy params, compute weighted return, update adaptive momentum."""
        alpha, drift, scale = fit_levy_parameters(returns)
        state.current_levy_alpha = alpha

        levy_weights = compute_levy_weights(returns, alpha, scale)
        weighted_return = float(np.dot(levy_weights, returns))

        current_vol = state.realized_volatility(self._volatility_lookback)
        decay = self._adaptive_decay(current_vol)
        state.current_decay = decay

        new_momentum = decay * state.current_momentum + (1.0 - decay) * weighted_return
        state.current_momentum = new_momentum
        state.momentum_history.append(new_momentum)

        logger.debug(
            "%s  α=%.3f  decay=%.3f  drift=%.6f  M=%.6f",
            state.symbol, alpha, decay, drift, new_momentum,
        )
        return new_momentum

    def momentum_z_score(self, state: MomentumState) -> float:
        """Z-score of current momentum vs. its own recent history."""
        history = state.get_momentum_array()
        if len(history) < 3:
            return 0.0
        std = float(np.std(history))
        if std < 1e-10:
            return 0.0
        return (state.current_momentum - float(np.mean(history))) / std

    # ── private helpers ──────────────────────────────────────────────────────────

    def _adaptive_decay(self, current_vol: float) -> float:
        """
        tanh-sigmoid mapping of normalised volatility → decay in [decay_min, decay_max].
        High vol → normalised_vol > 1 → tanh > 0 → decay < decay_max (short memory).
        """
        self._update_vol_ema(current_vol)
        normalised = current_vol / (self._global_vol_ema + 1e-10)
        decay = self._decay_max - (self._decay_max - self._decay_min) * np.tanh(
            normalised - 1.0
        )
        return float(np.clip(decay, self._decay_min, self._decay_max))

    def _update_vol_ema(self, current_vol: float) -> None:
        self._global_vol_ema = (
            self._vol_ema_alpha * current_vol
            + (1.0 - self._vol_ema_alpha) * self._global_vol_ema
        )
