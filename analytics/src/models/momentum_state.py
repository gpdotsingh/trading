from collections import deque
from dataclasses import dataclass, field
from typing import Deque

import numpy as np


@dataclass
class MomentumState:
    """Per-symbol mutable state kept by the momentum calculator."""

    symbol: str
    window_size: int
    decay_min: float = 0.80
    decay_max: float = 0.98

    # updated at every tick
    current_momentum: float = field(default=0.0, init=False)
    current_levy_alpha: float = field(default=1.5, init=False)
    current_decay: float = field(default=0.95, init=False)

    price_history: Deque[float] = field(default_factory=deque, init=False)
    return_history: Deque[float] = field(default_factory=deque, init=False)
    momentum_history: Deque[float] = field(default_factory=deque, init=False)

    # ── lifecycle ────────────────────────────────────────────────────────────────

    def __post_init__(self) -> None:
        max_returns = self.window_size
        self.price_history = deque(maxlen=self.window_size * 2)
        self.return_history = deque(maxlen=max_returns)
        self.momentum_history = deque(maxlen=max_returns)

    # ── mutation ─────────────────────────────────────────────────────────────────

    def ingest_price(self, price: float) -> None:
        """Append a new price and compute the corresponding log-return."""
        if self.price_history:
            log_return = float(np.log(price / self.price_history[-1]))
            self.return_history.append(log_return)
        self.price_history.append(price)

    # ── queries ──────────────────────────────────────────────────────────────────

    def has_sufficient_data(self, min_points: int) -> bool:
        return len(self.return_history) >= min_points

    def get_returns_array(self) -> np.ndarray:
        return np.array(list(self.return_history))

    def get_momentum_array(self) -> np.ndarray:
        return np.array(list(self.momentum_history))

    def realized_volatility(self, lookback: int = 20) -> float:
        returns = self.get_returns_array()
        if len(returns) < 2:
            return 0.01
        tail = returns[-min(lookback, len(returns)):]
        return float(np.std(tail)) or 0.01
