from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from typing import Optional


class SignalType(Enum):
    BUY = "BUY"
    SELL = "SELL"
    HOLD = "HOLD"


@dataclass
class TradingSignal:
    """Output of the Lévy trend-following strategy for one symbol."""

    symbol: str
    signal_type: SignalType
    momentum_value: float
    levy_alpha: float          # estimated stability index (0 < α ≤ 2)
    confidence: float          # [0, 1] — strength of the signal
    timestamp: datetime
    price: float               # mid-price at signal generation time
    asset_type: str = "CfdOnStock"
    reason: Optional[str] = None

    # ── helpers ─────────────────────────────────────────────────────────────────

    @property
    def is_actionable(self) -> bool:
        return self.signal_type != SignalType.HOLD

    def to_dict(self) -> dict:
        return {
            "symbol": self.symbol,
            "signal": self.signal_type.value,
            "momentum": round(self.momentum_value, 6),
            "levy_alpha": round(self.levy_alpha, 4),
            "confidence": round(self.confidence, 4),
            "price": self.price,
            "timestamp": self.timestamp.isoformat(),
            "reason": self.reason,
        }
