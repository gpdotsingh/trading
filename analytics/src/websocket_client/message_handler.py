"""
Translates raw STOMP message bodies (JSON) into PriceTick objects and
forwards them to the registered signal callback.

Expected JSON shapes from Spring Boot /topic/prices:

  Single tick:
    {"symbol":"AAPL","bid":182.50,"ask":182.55,"uic":12345,"assetType":"CfdOnStock"}

  Array of ticks:
    [{"symbol":"AAPL",...}, {"symbol":"TSLA",...}]

  Map keyed by symbol (also supported):
    {"AAPL":{"bid":182.50,"ask":182.55,...}, "TSLA":{...}}
"""

import json
import logging
from datetime import datetime
from typing import Callable, List, Optional

from ..models.price_tick import PriceTick

logger = logging.getLogger(__name__)


class PriceMessageHandler:

    def __init__(self, signal_callback: Callable[[PriceTick], None]) -> None:
        self._signal_callback = signal_callback
        self._messages_received = 0
        self._parse_errors = 0

    # ── STOMP subscription callback ──────────────────────────────────────────────

    def handle_price_message(self, destination: str, body: str) -> None:
        self._messages_received += 1
        payload = self._parse_json(body)
        if payload is None:
            return
        ticks = self._extract_ticks(payload)
        for tick in ticks:
            self._forward_tick(tick)

    # ── stats ────────────────────────────────────────────────────────────────────

    def get_stats(self) -> dict:
        received = self._messages_received
        errors = self._parse_errors
        success_rate = (received - errors) / received if received > 0 else 0.0
        return {
            "messages_received": received,
            "parse_errors": errors,
            "success_rate": round(success_rate, 4),
        }

    # ── parsing helpers ──────────────────────────────────────────────────────────

    def _parse_json(self, body: str) -> Optional[object]:
        if not body:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            self._parse_errors += 1
            logger.error("JSON parse error: %s | body=%s", exc, body[:120])
            return None

    def _extract_ticks(self, payload: object) -> List[PriceTick]:
        if isinstance(payload, list):
            return self._ticks_from_list(payload)
        if isinstance(payload, dict):
            return self._ticks_from_dict(payload)
        logger.warning("Unexpected payload type: %s", type(payload))
        return []

    def _ticks_from_list(self, items: list) -> List[PriceTick]:
        ticks = []
        for item in items:
            if isinstance(item, dict):
                tick = self._build_tick(item)
                if tick:
                    ticks.append(tick)
        return ticks

    def _ticks_from_dict(self, data: dict) -> List[PriceTick]:
        # Check if it is a single tick (has 'symbol' key) or a symbol-keyed map
        if "symbol" in data:
            tick = self._build_tick(data)
            return [tick] if tick else []
        # symbol-keyed map: {"AAPL": {"bid":..., "ask":...}, ...}
        ticks = []
        for symbol, item in data.items():
            if isinstance(item, dict):
                item.setdefault("symbol", symbol)
                tick = self._build_tick(item)
                if tick:
                    ticks.append(tick)
        return ticks

    def _build_tick(self, item: dict) -> Optional[PriceTick]:
        try:
            symbol = item.get("symbol")
            bid = float(item.get("bid", 0))
            ask = float(item.get("ask", 0))
            if not symbol or bid <= 0 or ask <= 0:
                return None
            return PriceTick(
                symbol=str(symbol),
                bid=bid,
                ask=ask,
                timestamp=datetime.now(),
                uic=int(item["uic"]) if item.get("uic") else None,
                asset_type=str(item.get("assetType", "CfdOnStock")),
            )
        except (ValueError, TypeError) as exc:
            logger.warning("Failed to build PriceTick: %s | item=%s", exc, item)
            return None

    def _forward_tick(self, tick: PriceTick) -> None:
        try:
            self._signal_callback(tick)
        except Exception as exc:
            logger.error("Signal callback raised for %s: %s", tick.symbol, exc)
