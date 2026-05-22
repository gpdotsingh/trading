# ib-cfd-api

Automated CFD trading app built around Spring Boot, Saxo Bank simulation, TrendSpider webhooks, optional Capital.com routing, and an optional Python analytics loop.

## What this project does

- Receives TrendSpider `BUY` and `SELL` alerts over a public ngrok URL.
- Validates the webhook secret and stores recent alerts in memory.
- Maps TrendSpider tickers such as `CL1!`, `XAUUSD`, `GER40`, or `NAS100` to broker-specific instruments.
- Routes orders to Saxo, Capital.com, or both, depending on `trading.broker`.
- Publishes live events over STOMP/SockJS so the local dashboard and Python analytics process can react in real time.
- Tracks a trade journal in memory.
- Monitors Saxo positions opened from TrendSpider alerts with a dynamic stop loss and trailing stop workflow.
- Accepts analytics events from the Python process and can optionally auto-trade those signals too.

## How it works

### TrendSpider flow

1. Spring Boot starts on port `8080`.
2. If `ngrok.enabled=true`, `NgrokService` opens a public HTTPS tunnel and prints a ready-to-use webhook URL.
3. TrendSpider sends a `POST` request to `/api/webhook/trendspider`.
4. `TrendSpiderWebhookService` validates `trendspider.webhook.secret`, stores the alert, and broadcasts it to `/topic/trendspider`.
5. If `trendspider.auto-trade-enabled=true`, `BrokerRouter` sends the signal to the active broker gateway:
   - `saxo`
   - `capital`
   - `both`
6. If the order was placed on Saxo from a TrendSpider alert, the position is registered with `DynamicStopLossService` and checked every 20 seconds.

### Price streaming flow

1. The app subscribes to Saxo streaming when you call `/api/stream/subscribe` or `/api/stream/subscribe/direct`.
2. Live prices are broadcast to `/topic/prices`.
3. The dashboard can display those prices.
4. The Python analytics process can subscribe to the same topic and generate its own signals.

### Python analytics flow

1. `analytics/main.py` connects to Spring WebSocket at `/ws`.
2. It subscribes to:
   - `/topic/prices`
   - `/topic/trendspider`
3. It tracks paper P&L locally.
4. It posts analytics events back to Spring at `/api/analytics/event`.
5. If `analytics.auto-trade-enabled=true`, Spring routes incoming analytics `TRADE` events to the configured broker gateway.

## Architecture

```text
TrendSpider Alert
    |
    | HTTPS webhook
    v
ngrok public URL
    |
    v
Spring Boot API (:8080)
    |
    +--> /api/webhook/trendspider --> BrokerRouter --> Saxo / Capital.com
    |                                  |
    |                                  +--> Trade journal
    |                                  +--> TrendSpider topic
    |                                  +--> Saxo stop-loss monitor
    |
    +--> /api/stream/subscribe -------> Saxo streaming WebSocket
    |                                  |
    |                                  +--> /topic/prices
    |
    +--> /api/analytics/event --------> /topic/analytics
                                          |
                                          +--> optional analytics auto-trade

Browser dashboard <---------------------- STOMP/SockJS (/ws, /topic/*)
Python analytics <----------------------- STOMP/SockJS (/ws, /topic/*)
```

Detailed chart and property map:
[docs/system-flow.md](/Users/gauravsingh/study/stockmarket/ib-cfd-api/docs/system-flow.md:1)

## Important behavior and limits

- The app keeps webhook history, analytics history, the trade journal, and the stop-loss position registry in memory only. They reset when the app restarts.
- `trendspider.auto-trade-enabled` controls only TrendSpider-triggered order placement.
- `analytics.auto-trade-enabled` controls only analytics-triggered order placement.
- `trading.manual-orders-enabled` only guards `POST /api/orders`. It does not guard `POST /api/trade/manual`.
- Dynamic stop-loss registration currently happens only for Saxo orders placed from the TrendSpider webhook path.
- If you use a static ngrok domain, only one ngrok process can own it at a time.
- Saxo simulation tokens expire and must be refreshed manually.

## Main files

- `src/main/resources/application.properties`
  Main Spring Boot configuration, broker credentials, symbol mappings, ngrok settings, presets.
- `analytics/config/strategy.properties`
  Python analytics settings.
- `src/main/resources/static/trading-dashboard.html`
  Main dashboard.
- `src/main/resources/static/signal-hub.html`
  Webhook setup helper and live TrendSpider feed.

## Prerequisites

- Java 21+
- Maven 3.9+
- Saxo Bank simulation account and token
- TrendSpider account
- ngrok account with auth token and, ideally, a static domain
- Python 3.11+ if you want the analytics process
- Capital.com account only if you want Capital routing

## Safe first-time setup

Replace the values in `src/main/resources/application.properties` with your own. Do not rely on old local values already present in the file.

If you want to start in a safe test mode, use settings like this:

```properties
# Saxo
saxo.token=YOUR_SAXO_SIM_TOKEN
saxo.base-url=https://gateway.saxobank.com/sim/openapi
saxo.account-key=YOUR_SAXO_ACCOUNT_KEY

# Routing and safety
trading.broker=saxo
trading.manual-orders-enabled=false
trendspider.auto-trade-enabled=false
analytics.auto-trade-enabled=false

# TrendSpider webhook
trendspider.webhook.secret=CHANGE_ME

# ngrok
ngrok.enabled=true
ngrok.auth-token=YOUR_NGROK_AUTH_TOKEN
ngrok.domain=YOUR_STATIC_DOMAIN.ngrok-free.app

# Optional: disable Capital.com entirely if you are not using it
capital.enabled=false
```

## Spring configuration reference

### Saxo

| Key | Required | What it does |
|---|---|---|
| `saxo.token` | yes | Bearer token used for Saxo REST and streaming calls |
| `saxo.base-url` | yes | Saxo REST base URL. Current sim value is `https://gateway.saxobank.com/sim/openapi` |
| `saxo.account-key` | yes | Account key used when placing and querying Saxo orders |

### Trading and safety switches

| Key | Values | What it does |
|---|---|---|
| `trading.broker` | `saxo`, `capital`, `both` | Chooses which broker gateway `BrokerRouter` uses |
| `trading.manual-orders-enabled` | `true`, `false` | Only enables or blocks `POST /api/orders` |
| `trendspider.auto-trade-enabled` | `true`, `false` | Lets TrendSpider webhooks place broker orders |
| `analytics.auto-trade-enabled` | `true`, `false` | Lets Python analytics trade events place broker orders |

### TrendSpider webhook

| Key | Required | What it does |
|---|---|---|
| `trendspider.webhook.secret` | recommended | Secret checked by `/api/webhook/trendspider` |
| `trendspider.symbols.<TICKER>.saxo-symbol` | yes for Saxo routing | Maps incoming TrendSpider ticker to Saxo instrument name |
| `trendspider.symbols.<TICKER>.asset-type` | yes for Saxo routing | Saxo asset type such as `CfdOnIndex`, `CfdOnFutures`, `FxSpot` |
| `trendspider.symbols.<TICKER>.quantity` | yes for Saxo routing | Order size sent to Saxo |

Examples:

```properties
trendspider.symbols.XAUUSD.saxo-symbol=Gold
trendspider.symbols.XAUUSD.asset-type=FxSpot
trendspider.symbols.XAUUSD.quantity=1

trendspider.symbols.[CL1!].saxo-symbol=Crude Oil WTI
trendspider.symbols.[CL1!].asset-type=CfdOnFutures
trendspider.symbols.[CL1!].quantity=10
```

The repo already contains mappings for:

- Gold and Silver
- WTI and Brent
- Germany 40
- US Tech 100 / Nasdaq
- Netherlands 25 / AEX

### Capital.com

Capital.com endpoints and the Capital broker gateway are active only when `capital.enabled=true`.

| Key | Required when enabled | What it does |
|---|---|---|
| `capital.enabled` | yes | Enables Capital.com integration |
| `capital.demo` | yes | Chooses demo vs live mode |
| `capital.base-url` | yes | Demo or live Capital.com API base URL |
| `capital.api-key` | yes | Capital.com API key |
| `capital.identifier` | yes | Capital.com login identifier |
| `capital.password` | yes | Capital.com password |
| `capital.symbols.<TICKER>.epic` | yes | Maps ticker to Capital epic |
| `capital.symbols.<TICKER>.size` | yes | Trade size used for Capital orders |

Examples:

```properties
capital.enabled=true
capital.demo=true
capital.base-url=https://demo-api-capital.backend-capital.com
capital.api-key=YOUR_API_KEY
capital.identifier=YOUR_LOGIN
capital.password=YOUR_PASSWORD

capital.symbols.XAUUSD.epic=GOLD
capital.symbols.XAUUSD.size=1
capital.symbols.[CL1!].epic=OIL_CRUDE
capital.symbols.[CL1!].size=1
```

### ngrok

| Key | What it does |
|---|---|
| `ngrok.enabled` | Turns auto-tunnel startup on or off |
| `ngrok.auth-token` | Auth token used by `NgrokService` |
| `ngrok.domain` | Static domain to claim and reuse across restarts |

When `ngrok.enabled=true`, the app already starts ngrok itself. Do not start another `ngrok http 8080` process for the same static domain.

### Dashboard presets

These are used by `/api/config/presets` and the dashboard UI.

```properties
preset.indices.label=Indices
preset.indices.symbols=Germany 40,Netherlands 25
preset.indices.assetType=CfdOnIndex
```

## Python analytics configuration reference

Edit `analytics/config/strategy.properties`.

| Key | What it does |
|---|---|
| `spring.base_url` | Base URL for Spring REST calls such as analytics event reporting |
| `analytics.reporter.enabled` | Turns posting to `/api/analytics/event` on or off |
| `websocket.base_url` | SockJS/STOMP endpoint base, usually `ws://localhost:8080/ws` |
| `websocket.topic` | Price topic, normally `/topic/prices` |
| `watched.symbols` | Human-readable symbols you want the analytics side to treat as its universe |
| `watched.assetType` | Asset type associated with the watched symbols |
| `strategy.window_size` | Rolling window used by the strategy |
| `strategy.signal_threshold_buy` | Momentum threshold for BUY |
| `strategy.signal_threshold_sell` | Momentum threshold for SELL |
| `strategy.min_data_points` | Minimum number of ticks before signals are emitted |
| `strategy.quantity_per_trade` | Default fallback quantity |
| `strategy.min_sizes` | Per-symbol quantity overrides |
| `logging.level` | Python log level |
| `logging.file` | Python log file path |

Optional keys supported by the code but not required in the sample file:

- `trendspider.topic`
- `trendspider.default_asset_type`
- `trendspider.symbol_map`
- `websocket.reconnect_delay_seconds`
- `websocket.max_reconnect_attempts`

## Running the Spring Boot app

Start the backend:

```bash
mvn spring-boot:run
```

What should happen:

1. Spring Boot starts on `http://localhost:8080`
2. STOMP/SockJS endpoint becomes available at `/ws`
3. If ngrok is enabled, the app prints:
   - public URL
   - Signal Hub URL
   - full TrendSpider webhook URL

Expected log block:

```text
ngrok tunnel active
Public URL  : https://your-domain.ngrok-free.app
Signal Hub  : https://your-domain.ngrok-free.app/signal-hub.html
Webhook URL : https://your-domain.ngrok-free.app/api/webhook/trendspider?secret=YOUR_SECRET
```

Open these local pages:

| URL | Purpose |
|---|---|
| `http://localhost:8080/trading-dashboard.html` | Main dashboard for prices, journal, positions, analytics, and mappings |
| `http://localhost:8080/signal-hub.html` | Webhook setup guide and live TrendSpider alert feed |
| `http://localhost:8080/swagger-ui.html` | REST API explorer |

## TrendSpider setup

You create the alert in TrendSpider, not in this repo.

### Step 1

Start Spring Boot and copy the full webhook URL printed in the logs or returned by:

```text
GET /api/ngrok/url
```

### Step 2

In TrendSpider:

1. Open your chart
2. Create an alert
3. Open the `Webhook` tab
4. Paste the full URL into the `URL` field

Recommended URL format:

```text
https://YOUR_DOMAIN.ngrok-free.app/api/webhook/trendspider?secret=YOUR_SECRET
```

The controller also supports the `X-TrendSpider-Secret` header, but the query-string secret is the path already used by the app UI and ngrok log output.

### Step 3

Use a body like this for a BUY alert:

```json
{
  "ticker": "{{ticker}}",
  "price": {{close}},
  "action": "BUY",
  "alertName": "{{alert_name}}",
  "interval": "{{interval}}",
  "message": "{{alert_message}}",
  "timestamp": "{{time}}"
}
```

For a SELL alert, create a second alert and change only:

```json
"action": "SELL"
```

Optional extra fields supported by the model:

```json
"mfi": {{MFI(14)}},
"rsi": {{RSI(14)}},
"volume": {{volume}}
```

If a TrendSpider indicator is not on the chart, remove that field from the body.

### Step 4

Click `Test Webhook` in TrendSpider.

Verify the result in one of these places:

- `http://localhost:8080/signal-hub.html`
- `GET /api/webhook/trendspider/history`
- Spring Boot logs

## Starting Saxo price streaming

The Python analytics process does not open Saxo subscriptions by itself. Spring must already be streaming prices.

You can start streaming with:

```text
POST /api/stream/subscribe?symbols=Germany 40,Netherlands 25&assetType=CfdOnIndex
```

Or stream known UICs directly:

```text
POST /api/stream/subscribe/direct?uics=123,456&symbolNames=Germany 40,Netherlands 25&assetType=CfdOnIndex
```

Live prices are then published to `/topic/prices`.

## Running the Python analytics process

```bash
cd analytics
pip install -r requirements.txt
python main.py
```

What it does:

- Connects to Spring WebSocket at `/ws`
- Subscribes to `/topic/prices`
- Subscribes to `/topic/trendspider`
- Tracks paper-trading P&L locally
- Sends analytics events back to Spring
- Can trigger real broker orders only if `analytics.auto-trade-enabled=true`

Logs are written to:

```text
analytics/logs/levy_strategy.log
```

## Main REST endpoints

### Webhook and config

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/webhook/trendspider` | Receive TrendSpider alert |
| `GET` | `/api/webhook/trendspider/history` | Recent TrendSpider alerts |
| `GET` | `/api/ngrok/url` | Current public URL and full webhook URL |
| `GET` | `/api/config/model` | Masked config model for UI |
| `GET` | `/api/config/presets` | Dashboard presets |
| `GET` | `/api/config/broker` | Active broker status |
| `GET` | `/api/config/symbol-map` | TrendSpider to Saxo mapping |

### Trading

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/trade/manual` | Manual routed trade through `BrokerRouter` |
| `POST` | `/api/orders` | Direct Saxo order path, blocked unless `trading.manual-orders-enabled=true` |
| `GET` | `/api/orders/{orderId}/status` | Saxo order status |
| `DELETE` | `/api/orders/{orderId}` | Cancel Saxo order |
| `GET` | `/api/trades/journal` | Trade journal |
| `GET` | `/api/trades/summary` | Trade journal summary |

### Saxo market and portfolio

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/market/price/{symbol}` | Current market price |
| `GET` | `/api/market/prices` | Bulk prices |
| `POST` | `/api/stream/subscribe` | Start Saxo streaming by symbol |
| `POST` | `/api/stream/subscribe/direct` | Start Saxo streaming by UIC |
| `GET` | `/api/stream/status` | Saxo streaming status |
| `GET` | `/api/portfolio/positions` | Open Saxo positions |
| `GET` | `/api/portfolio/closed` | Closed Saxo positions |
| `GET` | `/api/portfolio/orders` | Open Saxo orders |
| `GET` | `/api/portfolio/summary` | Saxo account summary |
| `GET` | `/api/positions` | Open positions tracked by the stop-loss monitor |
| `GET` | `/api/positions/all` | All tracked stop-loss positions |

### Instruments

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/instruments/browse` | Full Saxo instrument browse |
| `GET` | `/api/instruments/search` | Search by keyword |
| `GET` | `/api/instruments/details` | Get one instrument's details |
| `GET` | `/api/instruments/details/bulk` | Get multiple instruments' details |

### Analytics

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/analytics/event` | Python posts analytics events here |
| `GET` | `/api/analytics/history` | Recent analytics events |
| `GET` | `/api/analytics/summary` | Latest analytics summary |

### Capital.com

Available only when `capital.enabled=true`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/capital/session` | Create or refresh session |
| `POST` | `/api/capital/positions` | Open a Capital CFD position |
| `DELETE` | `/api/capital/positions/{dealId}` | Close a Capital position |
| `PUT` | `/api/capital/positions/{dealId}` | Update stop/limit |
| `GET` | `/api/capital/positions` | Open positions |
| `GET` | `/api/capital/account` | Account summary |
| `GET` | `/api/capital/activity` | Activity history |
| `GET` | `/api/capital/workingorders` | Working orders |
| `GET` | `/api/capital/markets` | Search for epics |
| `GET` | `/api/capital/symbol-map` | Capital ticker map |

## Troubleshooting

### `ERR_NGROK_334`

Cause: the same static domain is already online.

Fix:

- Do not start a second manual ngrok tunnel for the same domain.
- Keep the Spring Boot app's built-in ngrok tunnel and reuse the printed URL.
- If you really want to restart cleanly, stop the existing Spring/ngrok process first.

### Webhook returns `Invalid webhook secret`

Check that:

- `trendspider.webhook.secret` matches your TrendSpider URL query string or header
- You copied the entire URL, including `?secret=...`

### TrendSpider alert is received but no order is placed

Check:

- `trendspider.auto-trade-enabled=true`
- `trading.broker` is set correctly
- The ticker exists in `trendspider.symbols.*`
- For Capital routing, `capital.enabled=true` and the ticker exists in `capital.symbols.*`

### Python analytics is connected but produces no price-driven signals

Check:

- Spring Boot is running
- Saxo streaming was started with `/api/stream/subscribe` or `/api/stream/subscribe/direct`
- The Python client is connected to `ws://localhost:8080/ws`

### Saxo order or portfolio calls fail

Usually one of these is wrong:

- `saxo.token`
- `saxo.account-key`
- expired Saxo token
- wrong asset type or missing symbol mapping

## Notes on `start-webhook.sh`

The script exists in the repo, but the Spring Boot app already starts ngrok automatically when `ngrok.enabled=true`. In the current codebase, the simplest and least confusing way to run the project is:

```bash
mvn spring-boot:run
```

If you also launch another manual ngrok tunnel, you can conflict with your configured static domain.
