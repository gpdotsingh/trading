"""
Lévy Trend-Following Strategy — entry point
────────────────────────────────────────────
Run:
    cd analytics/
    pip install -r requirements.txt
    python main.py                            # uses default config path
    python main.py config/strategy.properties # explicit config

What this does:
  1. Reads configuration from strategy.properties.
  2. Connects to Spring Boot WebSocket (SockJS + STOMP) at ws://localhost:8080/ws.
  3. Subscribes to /topic/prices (live CFD prices pushed by SaxoStreamingService).
  4. Subscribes to /topic/trendspider (BUY/SELL alerts from TrendSpider webhooks).
  5. Runs Lévy α-stable trend-following with dynamic momentum learning.
  6. Also acts on TrendSpider signals directly — they go straight to PnlTracker.
  7. Tracks paper-trading P&L — prints a report every 30 s and on exit.

Stop with Ctrl-C or SIGTERM.
"""

import logging
import logging.handlers
import os
import signal as os_signal
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from src.config.config_loader import ConfigLoader
from src.reporting.analytics_reporter import AnalyticsReporter
from src.strategy.signal_generator import SignalGenerator
from src.tracking.pnl_tracker import PnlTracker
from src.websocket_client.message_handler import PriceMessageHandler
from src.websocket_client.sockjs_stomp_client import SockJsStompClient
from src.websocket_client.trendspider_signal_handler import TrendSpiderSignalHandler

_DEFAULT_CONFIG = Path(__file__).parent / "config" / "strategy.properties"
_REPORT_INTERVAL_SECONDS = 30

logger = logging.getLogger(__name__)


# ── logging ──────────────────────────────────────────────────────────────────────

def configure_logging(config: ConfigLoader) -> None:
    log_file = config.get_string("logging.file", "logs/levy_strategy.log")
    log_format = config.get_string(
        "logging.format",
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    level = getattr(logging, config.get_string("logging.level", "INFO").upper(), logging.INFO)
    os.makedirs(os.path.dirname(log_file), exist_ok=True)
    fmt = logging.Formatter(log_format)
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    rotfile = logging.handlers.RotatingFileHandler(
        log_file, maxBytes=10 * 1024 * 1024, backupCount=5
    )
    rotfile.setFormatter(fmt)
    root = logging.getLogger()
    root.setLevel(level)
    root.addHandler(console)
    root.addHandler(rotfile)


# ── signal callback ───────────────────────────────────────────────────────────────

def make_signal_callback(
    signal_generator: SignalGenerator,
    pnl_tracker: PnlTracker,
    reporter: AnalyticsReporter,
):
    """Returns a tick callback that runs the strategy, updates P&L, and reports to chart."""
    def callback(tick):
        trading_signal = signal_generator.process_tick(tick)
        pnl_tracker.process_signal(trading_signal)
        reporter.report(trading_signal, pnl_tracker.summary())
        if trading_signal.is_actionable:
            logger.info("SIGNAL  %s", trading_signal.to_dict())
        else:
            logger.debug(
                "HOLD  %-6s  price=%.4f  M=%.6f",
                trading_signal.symbol,
                trading_signal.price,
                trading_signal.momentum_value,
            )
    return callback


# ── component factories ───────────────────────────────────────────────────────────

def build_signal_generator(config: ConfigLoader) -> SignalGenerator:
    return SignalGenerator(config)


def build_pnl_tracker(config: ConfigLoader) -> PnlTracker:
    quantity = config.get_float("strategy.quantity_per_trade", 1.0)
    min_sizes = config.get_min_sizes("strategy.min_sizes")
    return PnlTracker(quantity_per_trade=quantity, min_sizes=min_sizes)


def build_message_handler(tick_callback) -> PriceMessageHandler:
    return PriceMessageHandler(signal_callback=tick_callback)


def build_trendspider_handler(
    config: ConfigLoader,
    pnl_tracker: PnlTracker,
    reporter: AnalyticsReporter,
) -> TrendSpiderSignalHandler:
    # Parse optional ticker→symbol remapping: e.g. "GER40:Germany 40,NAS100:US 100"
    symbol_map = {}
    raw = config.get_string("trendspider.symbol_map", "")
    for pair in raw.split(","):
        pair = pair.strip()
        if ":" in pair:
            k, v = pair.split(":", 1)
            symbol_map[k.strip()] = v.strip()

    def ts_signal_callback(signal):
        pnl_tracker.process_signal(signal)
        reporter.report(signal, pnl_tracker.summary())
        logger.info("TRENDSPIDER SIGNAL  %s", signal.to_dict())

    return TrendSpiderSignalHandler(
        signal_callback=ts_signal_callback,
        default_asset_type=config.get_string("trendspider.default_asset_type", "CfdOnIndex"),
        symbol_map=symbol_map,
    )


def build_stomp_client(config: ConfigLoader) -> SockJsStompClient:
    return SockJsStompClient(
        base_url=config.get_string("websocket.base_url", "ws://localhost:8080/ws"),
        reconnect_delay=config.get_int("websocket.reconnect_delay_seconds", 5),
        max_reconnect=config.get_int("websocket.max_reconnect_attempts", 10),
    )


def build_reporter(config: ConfigLoader) -> AnalyticsReporter:
    return AnalyticsReporter(
        base_url=config.get_string("spring.base_url", "http://localhost:8080"),
        enabled=config.get_string("analytics.reporter.enabled", "true").lower() == "true",
    )


# ── main loop ────────────────────────────────────────────────────────────────────

def run_strategy(
    config: ConfigLoader,
    stomp_client: SockJsStompClient,
    message_handler: PriceMessageHandler,
    trendspider_handler: TrendSpiderSignalHandler,
    pnl_tracker: PnlTracker,
    reporter: AnalyticsReporter,
) -> None:
    prices_topic = config.get_string("websocket.topic", "/topic/prices")
    ts_topic     = config.get_string("trendspider.topic", "/topic/trendspider")

    stomp_client.subscribe(prices_topic, message_handler.handle_price_message)
    stomp_client.subscribe(ts_topic, trendspider_handler.handle_alert)

    def on_connected():
        logger.info("Subscribed → %s  (Lévy momentum)", prices_topic)
        logger.info("Subscribed → %s  (TrendSpider signals)", ts_topic)

    stomp_client.connect(on_connected=on_connected)

    stop_event = threading.Event()

    def handle_shutdown(sig, _frame):
        logger.info("Shutdown signal %d received", sig)
        stop_event.set()

    os_signal.signal(os_signal.SIGINT, handle_shutdown)
    os_signal.signal(os_signal.SIGTERM, handle_shutdown)

    logger.info("Strategy running — Ctrl-C to stop and see final P&L report")
    while not stop_event.is_set():
        stop_event.wait(timeout=_REPORT_INTERVAL_SECONDS)
        logger.info("ws-stats=%s  ts-stats=%s",
                    message_handler.get_stats(), trendspider_handler.get_stats())
        pnl_tracker.print_report()

    stomp_client.disconnect()
    logger.info("Disconnected — final report:")
    pnl_tracker.print_report()
    reporter.shutdown()


# ── entry point ──────────────────────────────────────────────────────────────────

def main() -> None:
    config_path = sys.argv[1] if len(sys.argv) > 1 else str(_DEFAULT_CONFIG)
    config = ConfigLoader(config_path)
    configure_logging(config)

    logger.info("=== Lévy Trend-Following Strategy (dynamic momentum) ===")
    logger.info("Config: %s", config_path)

    signal_generator     = build_signal_generator(config)
    pnl_tracker          = build_pnl_tracker(config)
    reporter             = build_reporter(config)
    tick_callback        = make_signal_callback(signal_generator, pnl_tracker, reporter)
    message_handler      = build_message_handler(tick_callback)
    trendspider_handler  = build_trendspider_handler(config, pnl_tracker, reporter)
    stomp_client         = build_stomp_client(config)

    run_strategy(config, stomp_client, message_handler, trendspider_handler, pnl_tracker, reporter)


if __name__ == "__main__":
    main()
