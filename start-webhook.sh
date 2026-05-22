#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# start-webhook.sh
# Starts Spring Boot + ngrok and prints the ready-to-use TrendSpider webhook URL
# ─────────────────────────────────────────────────────────────────────────────

SECRET=$(grep 'trendspider.webhook.secret' src/main/resources/application.properties | cut -d= -f2)
PORT=8080

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  TrendSpider Webhook Launcher"
echo "═══════════════════════════════════════════════════════════"

# ── 1. Check ngrok is installed ───────────────────────────────────────────────
if ! command -v ngrok &>/dev/null; then
  echo ""
  echo "  ✗  ngrok not found."
  echo "     Install it from: https://ngrok.com/download"
  echo "     Mac: brew install ngrok"
  echo ""
  exit 1
fi

# ── 2. Start Spring Boot in background ───────────────────────────────────────
echo ""
echo "  ▶  Starting Spring Boot on port $PORT ..."
mvn spring-boot:run -q &
SPRING_PID=$!

# Wait for Spring Boot to be ready
echo "     Waiting for Spring Boot to start..."
for i in {1..30}; do
  if curl -s http://localhost:$PORT/actuator/health &>/dev/null || \
     curl -s http://localhost:$PORT/ &>/dev/null; then
    echo "  ✓  Spring Boot is up"
    break
  fi
  sleep 2
done

# ── 3. Start ngrok in background ─────────────────────────────────────────────
echo ""
echo "  ▶  Starting ngrok tunnel on port $PORT ..."
ngrok http $PORT --log=stdout > /tmp/ngrok.log 2>&1 &
NGROK_PID=$!
sleep 3

# ── 4. Get the public URL from ngrok API ──────────────────────────────────────
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels \
  | python3 -c "
import sys, json
tunnels = json.load(sys.stdin).get('tunnels', [])
https = [t['public_url'] for t in tunnels if t['proto'] == 'https']
print(https[0] if https else (tunnels[0]['public_url'] if tunnels else ''))
" 2>/dev/null)

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  READY — paste this URL into TrendSpider Webhook"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "  Webhook URL:"
echo "  $NGROK_URL/api/webhook/trendspider?secret=$SECRET"
echo ""
echo "  Dashboard:"
echo "  $NGROK_URL/signal-hub.html"
echo "  (or locally: http://localhost:$PORT/signal-hub.html)"
echo ""
echo "  Security key: $SECRET"
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  TrendSpider Webhook Body — copy into alert settings:"
echo "═══════════════════════════════════════════════════════════"
cat << 'EOF'

{
  "ticker":    "{{ticker}}",
  "price":     {{close}},
  "action":    "BUY",
  "alertName": "{{alert_name}}",
  "interval":  "{{interval}}",
  "message":   "{{alert_message}}",
  "timestamp": "{{time}}"
}

EOF
echo "  (Create a second alert with \"action\": \"SELL\" for sell signals)"
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  Press Ctrl-C to stop everything"
echo "═══════════════════════════════════════════════════════════"
echo ""

# ── 5. Keep running — kill both on exit ──────────────────────────────────────
trap "echo '  Stopping...'; kill $SPRING_PID $NGROK_PID 2>/dev/null; exit 0" INT TERM
wait
