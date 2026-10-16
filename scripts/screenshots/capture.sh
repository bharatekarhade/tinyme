#!/bin/sh
# Retakes the README screenshots in docs/images from sample data.
#
# It runs a throwaway demo server on port 8090 with its own database (tinyme_demo, in the dev Postgres container),
# no Anthropic or Google keys, and the sample data from seed.py. Your own database and .env are never used.
# The app is built in Debug, where ScreenshotMode signs in and opens each screen from launch arguments.
#
# Needs: the dev Postgres container running (see README), JDK 25, Xcode, python3, and one booted iPhone simulator.
# Usage: scripts/screenshots/capture.sh
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
PG=${TINYME_PG_CONTAINER:-tinyme-postgres-1}
PG_USER=${POSTGRES_USER:-tinyme}
PORT=8090
EMAIL=demo@tinyme.app
PASSWORD=demo-password-123
SIM=$(xcrun simctl list devices booted | grep -Eo '[0-9A-F-]{36}' | head -1)
[ -n "$SIM" ] || { echo "Boot an iPhone simulator first." >&2; exit 1; }

echo "Demo database"
docker exec "$PG" psql -q -U "$PG_USER" -d postgres -c "DROP DATABASE IF EXISTS tinyme_demo" \
  -c "DO \$\$ BEGIN CREATE ROLE tinyme_demo LOGIN SUPERUSER PASSWORD 'tinyme_demo'; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$" \
  -c "CREATE DATABASE tinyme_demo OWNER tinyme_demo"

echo "Demo server"
(cd "$ROOT/server" && ./mvnw -q --batch-mode --no-transfer-progress package -DskipTests)
mkdir -p "$WORK/blobs"
# Started from an empty folder, so no .env is picked up; every key is blank on purpose.
(cd "$WORK" && exec env SERVER_PORT=$PORT \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/tinyme_demo \
  SPRING_DATASOURCE_USERNAME=tinyme_demo SPRING_DATASOURCE_PASSWORD=tinyme_demo \
  TINYME_OWNER_EMAIL=$EMAIL TINYME_OWNER_PASSWORD=$PASSWORD \
  TINYME_AGENT_SETUP_ENABLED=false ANTHROPIC_API_KEY=demo-not-a-real-key \
  GOOGLE_CLIENT_ID= GOOGLE_CLIENT_SECRET= GOOGLE_REDIRECT_URI= TINYME_ENCRYPTION_KEY= \
  TINYME_MAILSCAN_INTERVAL=100000d TINYME_BLOBS_PATH=./blobs \
  java -jar "$ROOT"/server/target/tinyme-server-*.jar > "$WORK/server.log" 2>&1) &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; docker exec "$PG" psql -q -U "$PG_USER" -d postgres -c "DROP DATABASE IF EXISTS tinyme_demo" -c "DROP ROLE IF EXISTS tinyme_demo" >/dev/null' EXIT
until grep -q "Started TinymeApplication" "$WORK/server.log" 2>/dev/null; do
  kill -0 $SERVER 2>/dev/null || { cat "$WORK/server.log" >&2; exit 1; }
  sleep 2
done
python3 -I "$ROOT/scripts/screenshots/seed.py" | docker exec -i "$PG" psql -q -v ON_ERROR_STOP=1 -U tinyme_demo -d tinyme_demo

echo "App"
xcodebuild -project "$ROOT/ios/TinyMe.xcodeproj" -scheme TinyMe -configuration Debug \
  -destination "id=$SIM" -derivedDataPath "$WORK/dd" build -quiet
APP="$WORK/dd/Build/Products/Debug-iphonesimulator/TinyMe.app"
BID=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
xcrun simctl uninstall "$SIM" "$BID" || true
xcrun simctl install "$SIM" "$APP"
xcrun simctl status_bar "$SIM" override --time 9:41 --dataNetwork wifi --wifiBars 3 --cellularBars 4 \
  --batteryState charged --batteryLevel 100
xcrun simctl location "$SIM" set 35.6595,139.7005

shot() {
  xcrun simctl terminate "$SIM" "$BID" 2>/dev/null || true
  xcrun simctl launch "$SIM" "$BID" -TinyMeScreenshotScreen "$1" \
    -TinyMeScreenshotServer "http://localhost:$PORT" -TinyMeScreenshotEmail $EMAIL -TinyMeScreenshotPassword $PASSWORD \
    -health.homeCardDismissed YES -home.range week >/dev/null
  sleep 10
  xcrun simctl io "$SIM" screenshot "$WORK/$1.png" >/dev/null 2>&1
  sips -Z 1300 "$WORK/$1.png" --out "$ROOT/docs/images/app-$1.png" >/dev/null
  echo "  $1"
}

echo "Screenshots"
xcrun simctl privacy "$SIM" revoke location "$BID" 2>/dev/null || true   # no location arrow in the status bar
for screen in home upcoming gscan chat history settings; do shot "$screen"; done
xcrun simctl privacy "$SIM" grant location-always "$BID"                  # Places shows distances
shot places
xcrun simctl status_bar "$SIM" clear
echo "Done: docs/images"
