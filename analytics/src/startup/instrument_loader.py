"""
Fetches MinimumTradeSize for each configured symbol from Spring Boot
GET /api/instruments/details/bulk before the strategy starts.

This means strategy.quantity_per_trade in strategy.properties is only used
as a fallback. The real minimum is always driven by the Saxo API response.
"""

import logging
import urllib.request
import urllib.parse
import json
from typing import Dict

logger = logging.getLogger(__name__)


class InstrumentLoader:

    def __init__(self, spring_boot_url: str) -> None:
        self._base_url = spring_boot_url.rstrip("/")

    # ── public API ───────────────────────────────────────────────────────────────

    def fetch_minimum_trade_sizes(
        self, symbols: list[str], asset_type: str
    ) -> Dict[str, float]:
        """
        Returns {symbol: minimumTradeSize} for every symbol.
        Falls back to 1.0 if a symbol could not be resolved.
        """
        raw = self._call_bulk_details(symbols, asset_type)
        result = {}
        for symbol, detail in raw.items():
            min_size = self._extract_min_size(symbol, detail)
            result[symbol] = min_size
        return result

    def fetch_single_details(self, symbol: str, asset_type: str) -> dict:
        """Returns the full details dict for one symbol."""
        params = urllib.parse.urlencode({"symbol": symbol, "assetType": asset_type})
        url = f"{self._base_url}/api/instruments/details?{params}"
        return self._get_json(url)

    # ── private helpers ──────────────────────────────────────────────────────────

    def _call_bulk_details(self, symbols: list[str], asset_type: str) -> dict:
        symbols_csv = ",".join(symbols)
        params = urllib.parse.urlencode({"symbols": symbols_csv, "assetType": asset_type})
        url = f"{self._base_url}/api/instruments/details/bulk?{params}"
        logger.info("Fetching instrument details: %s", url)
        return self._get_json(url)

    def _get_json(self, url: str) -> dict:
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                body = resp.read().decode("utf-8")
                return json.loads(body)
        except Exception as exc:
            logger.error("Failed to fetch %s: %s", url, exc)
            return {}

    def _extract_min_size(self, symbol: str, detail: dict) -> float:
        raw = detail.get("minimumTradeSize", -1)
        if isinstance(raw, (int, float)) and raw > 0:
            logger.info("MinimumTradeSize for %-20s = %s", symbol, raw)
            return float(raw)
        logger.warning("Could not read MinimumTradeSize for %s — defaulting to 1.0", symbol)
        return 1.0
