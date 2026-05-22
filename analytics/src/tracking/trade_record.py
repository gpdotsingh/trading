from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional


@dataclass
class TradeRecord:
    """Immutable record of one completed round-trip trade."""

    symbol: str
    entry_price: float
    exit_price: float
    quantity: float
    entry_time: datetime
    exit_time: datetime
    asset_type: str = "CfdOnStock"
    side: str = "LONG"   # "LONG" or "SHORT"

    @property
    def pnl(self) -> float:
        """Profit or loss for this trade (positive = profit)."""
        direction = 1 if self.side == "LONG" else -1
        return (self.exit_price - self.entry_price) * self.quantity * direction

    @property
    def pnl_pct(self) -> float:
        """P&L as percentage of entry cost."""
        if self.entry_price == 0:
            return 0.0
        direction = 1 if self.side == "LONG" else -1
        return (self.exit_price - self.entry_price) / self.entry_price * 100.0 * direction

    @property
    def duration_seconds(self) -> float:
        return (self.exit_time - self.entry_time).total_seconds()

    @property
    def is_winner(self) -> bool:
        return self.pnl > 0

    def to_dict(self) -> dict:
        return {
            "symbol": self.symbol,
            "side": self.side,
            "entry_price": round(self.entry_price, 4),
            "exit_price": round(self.exit_price, 4),
            "quantity": self.quantity,
            "pnl": round(self.pnl, 4),
            "pnl_pct": round(self.pnl_pct, 4),
            "entry_time": self.entry_time.isoformat(),
            "exit_time": self.exit_time.isoformat(),
            "duration_secs": round(self.duration_seconds, 1),
            "result": "WIN" if self.is_winner else "LOSS",
        }


@dataclass
class OpenPosition:
    """A live position that has not yet been closed."""

    symbol: str
    entry_price: float
    quantity: float
    entry_time: datetime
    asset_type: str = "CfdOnStock"
    current_price: float = field(default=0.0)
    side: str = "LONG"   # "LONG" or "SHORT"

    def unrealized_pnl(self) -> float:
        if self.current_price == 0:
            return 0.0
        direction = 1 if self.side == "LONG" else -1
        return (self.current_price - self.entry_price) * self.quantity * direction

    def unrealized_pnl_pct(self) -> float:
        if self.entry_price == 0:
            return 0.0
        direction = 1 if self.side == "LONG" else -1
        return (self.current_price - self.entry_price) / self.entry_price * 100.0 * direction
