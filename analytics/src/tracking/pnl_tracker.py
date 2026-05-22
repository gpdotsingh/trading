"""
Paper-trading P&L tracker — long AND short CFD positions.

Rules
──────
  BUY  signal, no position  → open LONG
  BUY  signal, short open   → close SHORT (take profit / cut loss), then open LONG
  SELL signal, no position  → open SHORT
  SELL signal, long open    → close LONG (take profit / cut loss), then open SHORT
  HOLD                      → mark-to-market open position

No real orders are sent to Saxo — paper trading only.
"""

import logging
from typing import Dict, List

from ..models.trading_signal import SignalType, TradingSignal
from .trade_record import OpenPosition, TradeRecord

logger = logging.getLogger(__name__)


class PnlTracker:

    def __init__(
        self,
        quantity_per_trade: float = 1.0,
        min_sizes: Dict[str, float] | None = None,
    ) -> None:
        self._default_quantity = quantity_per_trade
        self._min_sizes: Dict[str, float] = min_sizes or {}
        self._open_positions: Dict[str, OpenPosition] = {}
        self._closed_trades: List[TradeRecord] = []

    # ── public API ───────────────────────────────────────────────────────────────

    def process_signal(self, signal: TradingSignal) -> None:
        self._mark_to_market(signal.symbol, signal.price)

        if signal.signal_type == SignalType.BUY:
            self._handle_buy(signal)
        elif signal.signal_type == SignalType.SELL:
            self._handle_sell(signal)

    def realized_pnl(self) -> float:
        return sum(t.pnl for t in self._closed_trades)

    def unrealized_pnl(self) -> float:
        return sum(p.unrealized_pnl() for p in self._open_positions.values())

    def total_pnl(self) -> float:
        return self.realized_pnl() + self.unrealized_pnl()

    def win_rate(self) -> float:
        if not self._closed_trades:
            return 0.0
        wins = sum(1 for t in self._closed_trades if t.is_winner)
        return wins / len(self._closed_trades) * 100.0

    def trade_count(self) -> int:
        return len(self._closed_trades)

    def open_position_count(self) -> int:
        return len(self._open_positions)

    def summary(self) -> dict:
        return {
            "realized_pnl": round(self.realized_pnl(), 4),
            "unrealized_pnl": round(self.unrealized_pnl(), 4),
            "total_pnl": round(self.total_pnl(), 4),
            "closed_trades": self.trade_count(),
            "open_positions": self.open_position_count(),
            "win_rate_pct": round(self.win_rate(), 2),
        }

    def print_report(self) -> None:
        _print_divider()
        _print_header("LÉVY STRATEGY — P&L REPORT")
        _print_divider()
        self._print_open_positions()
        self._print_closed_trades()
        self._print_summary_block()
        _print_divider()

    # ── private helpers ──────────────────────────────────────────────────────────

    def _resolve_quantity(self, symbol: str) -> float:
        return self._min_sizes.get(symbol, self._default_quantity)

    def _handle_buy(self, signal: TradingSignal) -> None:
        existing = self._open_positions.get(signal.symbol)
        if existing:
            if existing.side == "LONG":
                logger.debug("Already long %s — skip BUY", signal.symbol)
                return
            # flip: close short, then open long
            self._close_position(signal)
        self._open_position(signal, side="LONG")

    def _handle_sell(self, signal: TradingSignal) -> None:
        existing = self._open_positions.get(signal.symbol)
        if existing:
            if existing.side == "SHORT":
                logger.debug("Already short %s — skip SELL", signal.symbol)
                return
            # flip: close long, then open short
            self._close_position(signal)
        self._open_position(signal, side="SHORT")

    def _open_position(self, signal: TradingSignal, side: str) -> None:
        position = OpenPosition(
            symbol=signal.symbol,
            entry_price=signal.price,
            quantity=self._resolve_quantity(signal.symbol),
            entry_time=signal.timestamp,
            asset_type=signal.asset_type,
            current_price=signal.price,
            side=side,
        )
        self._open_positions[signal.symbol] = position
        logger.info(
            "OPEN  %-6s  %-5s  entry=%.4f  qty=%.4f  confidence=%.2f",
            signal.symbol, side, signal.price,
            self._resolve_quantity(signal.symbol), signal.confidence,
        )

    def _close_position(self, signal: TradingSignal) -> None:
        position = self._open_positions.pop(signal.symbol, None)
        if position is None:
            return
        trade = TradeRecord(
            symbol=signal.symbol,
            entry_price=position.entry_price,
            exit_price=signal.price,
            quantity=position.quantity,
            entry_time=position.entry_time,
            exit_time=signal.timestamp,
            asset_type=signal.asset_type,
            side=position.side,
        )
        self._closed_trades.append(trade)
        result = "WIN  +" if trade.is_winner else "LOSS "
        logger.info(
            "CLOSE %-6s  %-5s  %s%.4f  (%.2f%%)  pnl=%.4f",
            signal.symbol, trade.side, result, trade.pnl, trade.pnl_pct, trade.pnl,
        )

    def _mark_to_market(self, symbol: str, price: float) -> None:
        position = self._open_positions.get(symbol)
        if position:
            position.current_price = price

    def _print_open_positions(self) -> None:
        if not self._open_positions:
            print("  Open positions : none")
            return
        print(f"  {'SYMBOL':<8} {'SIDE':<6} {'ENTRY':>10} {'CURRENT':>10} {'UNREAL PnL':>12}")
        for sym, pos in self._open_positions.items():
            print(
                f"  {sym:<8} {pos.side:<6} {pos.entry_price:>10.4f} "
                f"{pos.current_price:>10.4f} {pos.unrealized_pnl():>+12.4f}"
            )

    def _print_closed_trades(self) -> None:
        print()
        if not self._closed_trades:
            print("  Closed trades  : none")
            return
        print(f"  {'SYMBOL':<8} {'SIDE':<6} {'ENTRY':>10} {'EXIT':>10} {'PnL':>10} {'%':>7} {'RESULT':<6}")
        for t in self._closed_trades:
            print(
                f"  {t.symbol:<8} {t.side:<6} {t.entry_price:>10.4f} {t.exit_price:>10.4f} "
                f"{t.pnl:>+10.4f} {t.pnl_pct:>+6.2f}%  {'WIN' if t.is_winner else 'LOSS'}"
            )

    def _print_summary_block(self) -> None:
        s = self.summary()
        print()
        print(f"  Realized P&L   : {s['realized_pnl']:>+10.4f}")
        print(f"  Unrealized P&L : {s['unrealized_pnl']:>+10.4f}")
        print(f"  Total P&L      : {s['total_pnl']:>+10.4f}")
        print(f"  Closed trades  : {s['closed_trades']}")
        print(f"  Win rate       : {s['win_rate_pct']:.1f}%")


def _print_divider() -> None:
    print("─" * 60)


def _print_header(text: str) -> None:
    print(f"  {text}")
