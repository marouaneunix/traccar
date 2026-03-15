#!/bin/bash
# Mock GPS device for Traccar
# Usage: ./mock_device.sh [device_unique_id] [num_points] [delay_seconds]
#
# Prerequisites: server must be running with osmand.port=5055 in debug.xml

API="http://localhost:8082/api"
OSMAND="http://localhost:5055"
UNIQUE_ID="${1:-mock-device-001}"
POINTS="${2:-20}"
DELAY="${3:-2}"

# Default admin credentials (first run creates admin/admin)
USER="admin"
PASS="admin"

echo "==> Creating device '$UNIQUE_ID' via REST API..."
RESPONSE=$(curl -s -u "$USER:$PASS" -X POST "$API/devices" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Mock Device\", \"uniqueId\": \"$UNIQUE_ID\"}")

echo "$RESPONSE" | grep -q '"id"' && echo "    Device created." || echo "    Device may already exist, continuing..."

echo "==> Sending $POINTS positions (delay: ${DELAY}s between each)..."

# Route: rough loop around Paris
LATS=(48.8566 48.8600 48.8650 48.8700 48.8720 48.8700 48.8650 48.8580 48.8500 48.8450 \
      48.8400 48.8380 48.8400 48.8450 48.8500 48.8540 48.8560 48.8570 48.8566 48.8566)
LONS=(2.3522  2.3600  2.3700  2.3800  2.3900  2.4000  2.4050  2.4000  2.3900  2.3800  \
      2.3700  2.3600  2.3500  2.3400  2.3350  2.3400  2.3450  2.3500  2.3522  2.3522)

for i in $(seq 0 $((POINTS - 1))); do
    IDX=$((i % 20))
    LAT="${LATS[$IDX]}"
    LON="${LONS[$IDX]}"
    SPEED=$((30 + RANDOM % 70))   # 30–100 km/h
    BEARING=$((RANDOM % 360))
    TIMESTAMP=$(date +%s)

    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      "$OSMAND/?id=$UNIQUE_ID&lat=$LAT&lon=$LON&timestamp=$TIMESTAMP&speed=$SPEED&bearing=$BEARING&altitude=50&accuracy=5")

    echo "    [$((i+1))/$POINTS] lat=$LAT lon=$LON speed=${SPEED}km/h → HTTP $STATUS"
    sleep "$DELAY"
done

echo "==> Done. Open http://localhost:8082 to see the device on the map."
