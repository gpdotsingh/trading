"""
Sends P&L analytics events to Spring Boot /api/analytics/event.

Fire-and-forget: HTTP posts run in a background thread so they never
block the strategy loop. If Spring Boot is unreachable the events are
silently dropped — the strategy keeps running.

HOLD updates are throttled to one post every HOLD_THROTTLE_SECS seconds.
BUY and SELL events are always sent immediately.
"""

import logging
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from ..models.trading_signal import SignalType, TradingSignal

logger = logging.getLogger(__name__)

_HOLD_THROTTLE_SECS = 5.0
_POST_TIMEOUT_SECS  = 3


class AnalyticsReporter:

    def __init__(self, base_url: str, enabled: bool = True) -> None:
        self._url      = base_url.rstrip("/") + "/api/analytics/event"
        self._enabled  = enabled
        self._last_hold_post = 0.0
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="analytics")

    # ── public ──────────────────────────────────────────────────────────────────

    def report(self, signal: TradingSignal, summary: dict) -> None:
        if not self._enabled:
            return
        if signal.signal_type == SignalType.HOLD:
            now = time.monotonic()
            if now - self._last_hold_post < _HOLD_THROTTLE_SECS:
                return
            self._last_hold_post = now

        event = _build_event(signal, summary)
        self._executor.submit(self._post, event)

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False)

    # ── private ─────────────────────────────────────────────────────────────────

    def _post(self, event: dict) -> None:
        try:
            requests.post(self._url, json=event, timeout=_POST_TIMEOUT_SECS)
        except Exception as exc:
            logger.debug("Analytics post failed (non-critical): %s", exc)


# ── helpers ──────────────────────────────────────────────────────────────────────

def _build_event(signal: TradingSignal, summary: dict) -> dict:
    event_type = "PNL_UPDATE" if signal.signal_type == SignalType.HOLD else "TRADE"
    # side: BUY signal = going LONG, SELL signal = going SHORT, HOLD = current side
    side = "LONG" if signal.signal_type == SignalType.BUY else (
           "SHORT" if signal.signal_type == SignalType.SELL else "HOLD")
    return {
        "type":          event_type,
        "symbol":        signal.symbol,
        "action":        signal.signal_type.value,
        "side":          side,
        "price":         round(signal.price, 4),
        "quantity":      summary.get("open_positions", 0),
        "tradePnl":      0.0,
        "cumPnl":        round(summary.get("total_pnl", 0.0), 4),
        "realizedPnl":   round(summary.get("realized_pnl", 0.0), 4),
        "unrealizedPnl": round(summary.get("unrealized_pnl", 0.0), 4),
        "winRate":       round(summary.get("win_rate_pct", 0.0), 2),
        "tradeCount":    summary.get("closed_trades", 0),
        "timestamp":     signal.timestamp.isoformat(),
    }
