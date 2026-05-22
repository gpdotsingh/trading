from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional


@dataclass
class PriceTick:
    """Single price snapshot received from the WebSocket stream."""

    symbol: str
    bid: float
    ask: float
    timestamp: datetime = field(default_factory=datetime.now)
    uic: Optional[int] = None
    asset_type: str = "CfdOnStock"

    # ── derived properties ──────────────────────────────────────────────────────

    @property
    def mid_price(self) -> float:
        return (self.bid + self.ask) / 2.0

    @property
    def spread(self) -> float:
        return self.ask - self.bid

    # ── validation ──────────────────────────────────────────────────────────────

    def __post_init__(self) -> None:
        if self.bid <= 0 or self.ask <= 0:
            raise ValueError(
                f"Invalid price for {self.symbol}: bid={self.bid}, ask={self.ask}"
            )
        if self.ask < self.bid:
            raise ValueError(
                f"Ask ({self.ask}) must be >= bid ({self.bid}) for {self.symbol}"
            )
