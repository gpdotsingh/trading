import configparser
import os
from typing import Optional


class ConfigLoader:
    """Reads Java-style .properties files (key=value, no section headers)."""

    _SECTION = "DEFAULT"

    def __init__(self, config_file_path: str) -> None:
        self._config_file_path = config_file_path
        self._parser = configparser.ConfigParser()
        self._load()

    # ── public accessors ────────────────────────────────────────────────────────

    def get_string(self, key: str, fallback: str = "") -> str:
        return self._parser[self._SECTION].get(key, fallback)

    def get_int(self, key: str, fallback: int = 0) -> int:
        return self._parser[self._SECTION].getint(key, fallback)

    def get_float(self, key: str, fallback: float = 0.0) -> float:
        return self._parser[self._SECTION].getfloat(key, fallback)

    def get_bool(self, key: str, fallback: bool = False) -> bool:
        return self._parser[self._SECTION].getboolean(key, fallback)

    def get_min_sizes(self, key: str = "strategy.min_sizes") -> dict:
        """Parse 'Symbol1:qty1,Symbol2:qty2' into {symbol: float}."""
        raw = self.get_string(key, "")
        result = {}
        for pair in raw.split(","):
            pair = pair.strip()
            if ":" in pair:
                sym, qty = pair.rsplit(":", 1)
                try:
                    result[sym.strip()] = float(qty.strip())
                except ValueError:
                    pass
        return result

    def reload(self) -> None:
        self._load()

    # ── private helpers ─────────────────────────────────────────────────────────

    def _load(self) -> None:
        if not os.path.exists(self._config_file_path):
            raise FileNotFoundError(
                f"Config file not found: {self._config_file_path}"
            )
        with open(self._config_file_path, "r") as fh:
            raw = fh.read()
        # configparser requires at least one section header
        self._parser.read_string(f"[{self._SECTION}]\n{raw}")
