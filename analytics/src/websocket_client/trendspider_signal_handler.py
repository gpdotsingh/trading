"""
Converts TrendSpider webhook alerts (received via /topic/trendspider) into
TradingSignals and forwards them to the PnlTracker.

TrendSpider does the technical analysis. This handler just translates the
alert JSON into the same TradingSignal format the rest of the system uses.

Expected JSON on /topic/trendspider:
  {
    "ticker":    "GER40",
    "price":     18250.0,
    "action":    "BUY",          # or "SELL"
    "alertName": "MA Crossover",
    "interval":  "1h",
    "message":   "50 EMA crossed above 200 EMA",
    "timestamp": "2026-04-29T10:30:00Z"
  }
"""

import json
import logging
from datetime import datetime, timezone
from typing import Callable, Optional

from ..models.trading_signal import SignalType, TradingSignal

logger = logging.getLogger(__name__)


class TrendSpiderSignalHandler:

    def __init__(
        self,
        signal_callback: Callable[[TradingSignal], None],
        default_asset_type: str = "CfdOnIndex",
        symbol_map: Optional[dict] = None,
    ) -> None:
        """
        signal_callback     — called with a TradingSignal on every BUY/SELL alert
        default_asset_type  — Saxo assetType to attach when not in the alert
        symbol_map          — optional dict to remap TrendSpider tickers to Saxo names
                              e.g. {"GER40": "Germany 40", "NAS100": "US 100"}
        """
        self._callback = signal_callback
        self._default_asset_type = default_asset_type
        self._symbol_map = symbol_map or {}
        self._alerts_received = 0
        self._parse_errors = 0

    # ── STOMP subscription callback ──────────────────────────────────────────────

    def handle_alert(self, destination: str, body: str) -> None:
        self._alerts_received += 1
        alert = self._parse(body)
        if alert is None:
            return

        signal = self._to_signal(alert)
        if signal is None:
            return

        logger.info(
            "TrendSpider → %s  %s  price=%.4f  alert='%s'",
            signal.signal_type.value, signal.symbol,
            signal.price, alert.get("alertName", ""),
        )
        try:
            self._callback(signal)
        except Exception as exc:
            logger.error("Signal callback failed for TrendSpider alert: %s", exc)

    def get_stats(self) -> dict:
        return {
            "alerts_received": self._alerts_received,
            "parse_errors":    self._parse_errors,
        }

    # ── private helpers ──────────────────────────────────────────────────────────

    def _parse(self, body: str) -> Optional[dict]:
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            self._parse_errors += 1
            logger.error("TrendSpider JSON parse error: %s | body=%s", exc, body[:120])
            return None

    def _to_signal(self, alert: dict) -> Optional[TradingSignal]:
        raw_action = str(alert.get("action", "")).upper().strip()
        if raw_action not in ("BUY", "SELL"):
            logger.debug("TrendSpider alert ignored — action='%s' is not BUY/SELL", raw_action)
            return None

        ticker = str(alert.get("ticker", "")).strip()
        if not ticker:
            logger.warning("TrendSpider alert missing ticker field")
            return None

        # remap ticker if a symbol_map entry exists (e.g. GER40 → Germany 40)
        symbol = self._symbol_map.get(ticker, ticker)

        try:
            price = float(alert.get("price", 0))
        except (TypeError, ValueError):
            price = 0.0

        signal_type = SignalType.BUY if raw_action == "BUY" else SignalType.SELL
        asset_type  = str(alert.get("assetType", self._default_asset_type))
        reason      = alert.get("message") or alert.get("alertName") or "TrendSpider alert"

        ts_raw = alert.get("timestamp")
        try:
            timestamp = datetime.fromisoformat(str(ts_raw).replace("Z", "+00:00"))
        except Exception:
            timestamp = datetime.now(timezone.utc)

        return TradingSignal(
            symbol=symbol,
            signal_type=signal_type,
            momentum_value=0.0,      # N/A — signal comes from TrendSpider TA, not momentum
            levy_alpha=2.0,          # Gaussian default (not estimated here)
            confidence=1.0,          # TrendSpider fired the alert — treat as full confidence
            timestamp=timestamp,
            price=price,
            asset_type=asset_type,
            reason=f"[TrendSpider] {reason}",
        )
