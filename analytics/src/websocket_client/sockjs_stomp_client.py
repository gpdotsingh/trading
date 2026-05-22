"""
SockJS + STOMP client for Spring Boot WebSocket.

Spring Boot exposes STOMP over SockJS at:
    ws://host/ws/<server-id>/<session-id>/websocket

SockJS framing
──────────────
  o          → connection open
  h          → heartbeat
  a[...]     → array of STOMP frames (JSON-encoded)
  c[...]     → close

STOMP frames inside the array are null-terminated strings.
"""

import json
import logging
import threading
import uuid
from typing import Callable, Dict, Optional

import websocket

logger = logging.getLogger(__name__)

_FRAME_OPEN = "o"
_FRAME_HEARTBEAT = "h"
_FRAME_ARRAY = "a"
_FRAME_CLOSE = "c"
_NULL = "\x00"
_NL = "\n"


class SockJsStompClient:

    def __init__(
        self,
        base_url: str,
        reconnect_delay: int = 5,
        max_reconnect: int = 10,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._reconnect_delay = reconnect_delay
        self._max_reconnect = max_reconnect
        self._ws: Optional[websocket.WebSocketApp] = None
        self._connected = False
        self._reconnect_count = 0
        self._lock = threading.Lock()
        self._on_connected_cb: Optional[Callable] = None
        # destination → (sub_id, callback)
        self._subscriptions: Dict[str, tuple] = {}

    # ── public API ───────────────────────────────────────────────────────────────

    def connect(self, on_connected: Optional[Callable] = None) -> None:
        self._on_connected_cb = on_connected
        self._open_websocket()

    def subscribe(self, destination: str, callback: Callable[[str, str], None]) -> None:
        sub_id = f"sub-{len(self._subscriptions)}"
        self._subscriptions[destination] = (sub_id, callback)
        if self._connected:
            self._send_subscribe_frame(destination, sub_id)

    def disconnect(self) -> None:
        self._reconnect_count = self._max_reconnect  # prevent auto-reconnect
        self._connected = False
        if self._ws:
            self._ws.close()

    @property
    def is_connected(self) -> bool:
        return self._connected

    # ── WebSocket lifecycle ──────────────────────────────────────────────────────

    def _open_websocket(self) -> None:
        url = self._build_ws_url()
        logger.info("Connecting → %s", url)
        self._ws = websocket.WebSocketApp(
            url,
            on_open=self._on_ws_open,
            on_message=self._on_ws_message,
            on_error=self._on_ws_error,
            on_close=self._on_ws_close,
        )
        t = threading.Thread(target=self._ws.run_forever, daemon=True)
        t.start()

    def _build_ws_url(self) -> str:
        server_id = str(uuid.uuid4().int % 999).zfill(3)
        session_id = uuid.uuid4().hex[:8]
        return f"{self._base_url}/{server_id}/{session_id}/websocket"

    def _on_ws_open(self, ws) -> None:
        logger.debug("WebSocket transport opened")

    def _on_ws_message(self, ws, raw: str) -> None:
        if not raw:
            return
        if raw[0] == _FRAME_OPEN:
            self._handle_sockjs_open()
        elif raw[0] == _FRAME_ARRAY:
            self._handle_sockjs_array(raw[1:])
        elif raw[0] == _FRAME_HEARTBEAT:
            logger.debug("SockJS heartbeat")

    def _on_ws_error(self, ws, error) -> None:
        logger.error("WebSocket error: %s", error)

    def _on_ws_close(self, ws, code, reason) -> None:
        self._connected = False
        logger.warning("WebSocket closed: %s %s", code, reason)
        self._schedule_reconnect()

    # ── SockJS frame handling ────────────────────────────────────────────────────

    def _handle_sockjs_open(self) -> None:
        logger.debug("SockJS open → sending STOMP CONNECT")
        frame = f"CONNECT{_NL}accept-version:1.2{_NL}heart-beat:0,0{_NL}{_NL}{_NULL}"
        self._send_raw(frame)

    def _handle_sockjs_array(self, payload: str) -> None:
        try:
            messages = json.loads(payload)
        except json.JSONDecodeError:
            logger.error("Bad SockJS array payload: %s", payload[:80])
            return
        for msg in messages:
            self._dispatch_stomp_frame(msg)

    # ── STOMP frame parsing ──────────────────────────────────────────────────────

    def _dispatch_stomp_frame(self, frame_str: str) -> None:
        lines = frame_str.split(_NL)
        command = lines[0].strip(_NULL).strip()
        headers, body = self._split_headers_body(lines[1:])
        if command == "CONNECTED":
            self._on_stomp_connected()
        elif command == "MESSAGE":
            self._on_stomp_message(headers, body)
        elif command == "ERROR":
            logger.error("STOMP ERROR frame: %s", body[:200])

    def _split_headers_body(self, lines: list) -> tuple:
        headers: Dict[str, str] = {}
        body_parts = []
        in_body = False
        for line in lines:
            if in_body:
                body_parts.append(line.strip(_NULL))
            elif line == "":
                in_body = True
            elif ":" in line:
                key, val = line.split(":", 1)
                headers[key.strip()] = val.strip()
        return headers, _NL.join(body_parts).strip(_NULL)

    # ── STOMP command handlers ───────────────────────────────────────────────────

    def _on_stomp_connected(self) -> None:
        self._connected = True
        self._reconnect_count = 0
        logger.info("STOMP connected")
        for destination, (sub_id, _) in self._subscriptions.items():
            self._send_subscribe_frame(destination, sub_id)
        if self._on_connected_cb:
            self._on_connected_cb()

    def _on_stomp_message(self, headers: dict, body: str) -> None:
        destination = headers.get("destination", "")
        entry = self._subscriptions.get(destination)
        if entry:
            _, callback = entry
            callback(destination, body)
        else:
            logger.warning("Unhandled destination: %s", destination)

    # ── STOMP frame senders ──────────────────────────────────────────────────────

    def _send_subscribe_frame(self, destination: str, sub_id: str) -> None:
        frame = (
            f"SUBSCRIBE{_NL}id:{sub_id}{_NL}"
            f"destination:{destination}{_NL}{_NL}{_NULL}"
        )
        self._send_raw(frame)
        logger.info("Subscribed → %s", destination)

    def _send_raw(self, stomp_frame: str) -> None:
        wrapped = json.dumps([stomp_frame])
        with self._lock:
            if self._ws and self._ws.sock:
                self._ws.send(wrapped)

    # ── reconnect ────────────────────────────────────────────────────────────────

    def _schedule_reconnect(self) -> None:
        if self._reconnect_count >= self._max_reconnect:
            logger.error("Max reconnect attempts (%d) reached", self._max_reconnect)
            return
        self._reconnect_count += 1
        logger.info(
            "Reconnecting (%d/%d) in %ds …",
            self._reconnect_count, self._max_reconnect, self._reconnect_delay,
        )
        timer = threading.Timer(self._reconnect_delay, self._open_websocket)
        timer.daemon = True
        timer.start()
