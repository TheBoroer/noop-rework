#!/bin/bash
# bench-etl.sh: benchmarks the GRDB-to-Room ETL (GrdbMigrator) on a LARGE synthetic legacy fixture
# (Phase 2c-1 Task 8). Generates a multi-million-row `hrSample` legacy GRDB database with sqlite3,
# runs GrdbMigrator over it, and reports wall time + peak memory on this Mac.
#
# The fixture is generated at bench time into a temp directory and is NEVER committed (it is hundreds
# of MB). Target: under 90s for 5M rows on this Mac (extrapolates to under ~15 min for a heavy
# multi-year device). If the target is missed, tune GrdbMigrator.BATCH_SIZE and re-run.
#
# Usage:  Tools/bench-etl.sh [ROWS] [TARGET_SECONDS]
#   ROWS            number of hrSample rows to synthesize (default 5000000)
#   TARGET_SECONDS  wall-time budget to compare against (default 90)
#
# Mechanism: the migration is invoked through the env-gated XCTest `testBenchGrdbMigrator`
# (RoomConcurrencyTests.swift), which links the same Shared.xcframework the app uses and calls the real
# `GrdbMigrator.shared.migrate`. Wall time is measured INSIDE the test around `migrate` only (build /
# XCTest overhead excluded); peak memory is the test process's max RSS via `/usr/bin/time -l` (an
# upper bound on the migrator's own footprint — the migrator streams the source in fixed-size batches,
# so its memory does not scale with row count).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROWS="${1:-5000000}"
TARGET_SECONDS="${2:-90}"
PKG="$REPO_ROOT/Packages/WhoopStore"
MIGRATOR_KT="$REPO_ROOT/shared/src/appleMain/kotlin/com/noop/data/GrdbMigrator.kt"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/noop-bench-etl.XXXXXX")"
FIXTURE="$WORK/legacy-5m.sqlite"
TIME_LOG="$WORK/time.log"
TEST_LOG="$WORK/test.log"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

BASE=1577836800   # 2020-01-01T00:00:00Z; fixed for determinism (never "now").
BATCH_SIZE="$(grep -oE 'BATCH_SIZE *= *[0-9_]+' "$MIGRATOR_KT" | grep -oE '[0-9][0-9_]*' | tr -d '_' | head -1 || echo '?')"

echo "== bench-etl =="
echo "rows=$ROWS target=${TARGET_SECONDS}s batch_size=$BATCH_SIZE"
echo "workdir=$WORK"

# ---------------------------------------------------------------------------
# 1. Synthesize the legacy GRDB fixture: the FULL final GRDB schema (so every mapped table the migrator
#    reads/truncates exists), with $ROWS deterministic rows in `hrSample` (the dominant table for a
#    heavy multi-year device) and the other tables left empty. rawBatch/cursors exist but are skipped
#    by the migrator. Schema mirrors Tools/make-grdb-fixture.sh (Database.swift v1..v23).
# ---------------------------------------------------------------------------
echo "generating fixture ($ROWS hrSample rows)..."
GEN_START=$(date +%s)
sqlite3 "$FIXTURE" >/dev/null <<SQL
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE device (id TEXT PRIMARY KEY, mac TEXT, name TEXT, firstSeen INTEGER, lastSeen INTEGER);
CREATE TABLE hrSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts));
CREATE TABLE rrInterval (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, rrMs INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts, rrMs));
CREATE TABLE event (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, payloadJSON TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts, kind));
CREATE TABLE battery (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, soc DOUBLE, mv INTEGER, synced INTEGER NOT NULL DEFAULT 0, charging BOOLEAN, PRIMARY KEY (deviceId, ts));
CREATE TABLE rawBatch (batchId TEXT PRIMARY KEY, deviceId TEXT NOT NULL, capturedAt INTEGER NOT NULL, deviceClockRef INTEGER NOT NULL, wallClockRef INTEGER NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL, frameCount INTEGER NOT NULL, byteSize INTEGER NOT NULL, framesBlob BLOB NOT NULL, syncedAt INTEGER);
CREATE TABLE cursors (name TEXT PRIMARY KEY, value INTEGER);
CREATE TABLE spo2Sample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, red INTEGER NOT NULL, ir INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts));
CREATE TABLE skinTempSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, raw INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts));
CREATE TABLE respSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, raw INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts));
CREATE TABLE gravitySample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (deviceId, ts));
CREATE TABLE sleepSession (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL, efficiency DOUBLE, restingHr INTEGER, avgHrv DOUBLE, stagesJSON TEXT, userEdited BOOLEAN NOT NULL DEFAULT 0, startTsAdjusted INTEGER, motionJSON TEXT, sleepStateJSON TEXT, PRIMARY KEY (deviceId, startTs));
CREATE TABLE dailyMetric (deviceId TEXT NOT NULL, day TEXT NOT NULL, totalSleepMin DOUBLE, efficiency DOUBLE, deepMin DOUBLE, remMin DOUBLE, lightMin DOUBLE, disturbances INTEGER, restingHr INTEGER, avgHrv DOUBLE, recovery DOUBLE, strain DOUBLE, exerciseCount INTEGER, spo2Pct DOUBLE, skinTempDevC DOUBLE, respRateBpm DOUBLE, steps INTEGER, activeKcalEst DOUBLE, spo2Red INTEGER, spo2Ir INTEGER, PRIMARY KEY (deviceId, day));
CREATE TABLE journal (deviceId TEXT NOT NULL, day TEXT NOT NULL, question TEXT NOT NULL, answeredYes INTEGER NOT NULL, notes TEXT, numericValue DOUBLE, PRIMARY KEY (deviceId, day, question));
CREATE TABLE workout (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL, sport TEXT NOT NULL, source TEXT NOT NULL, durationS DOUBLE, energyKcal DOUBLE, avgHr INTEGER, maxHr INTEGER, strain DOUBLE, distanceM DOUBLE, zonesJSON TEXT, notes TEXT, PRIMARY KEY (deviceId, startTs, sport));
CREATE TABLE appleDaily (deviceId TEXT NOT NULL, day TEXT NOT NULL, steps INTEGER, activeKcal DOUBLE, basalKcal DOUBLE, vo2max DOUBLE, avgHr INTEGER, maxHr INTEGER, walkingHr INTEGER, weightKg DOUBLE, PRIMARY KEY (deviceId, day));
CREATE TABLE metricSeries (deviceId TEXT NOT NULL, day TEXT NOT NULL, key TEXT NOT NULL, value DOUBLE NOT NULL, PRIMARY KEY (deviceId, day, key));
CREATE TABLE stepSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, counter INTEGER NOT NULL, activityClass INTEGER, PRIMARY KEY (deviceId, ts));
CREATE TABLE ppgHrSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm DOUBLE NOT NULL, conf DOUBLE NOT NULL, PRIMARY KEY (deviceId, ts));
CREATE TABLE pairedDevice (id TEXT PRIMARY KEY NOT NULL, brand TEXT NOT NULL, model TEXT NOT NULL, nickname TEXT, sourceKind TEXT NOT NULL, capabilities TEXT NOT NULL, status TEXT NOT NULL, addedAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL, peripheralId TEXT);
CREATE TABLE dayOwnership (day TEXT PRIMARY KEY NOT NULL, deviceId TEXT NOT NULL, locked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE labMarker (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, markerKey TEXT NOT NULL, category TEXT NOT NULL, day TEXT NOT NULL, takenAt INTEGER NOT NULL, value DOUBLE, valueText TEXT, unit TEXT NOT NULL, source TEXT NOT NULL, note TEXT, referenceText TEXT);
CREATE TABLE sleepStateSample (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, state INTEGER NOT NULL, PRIMARY KEY (deviceId, ts));
CREATE TABLE liveSession (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER, chargeAtStart DOUBLE, floorBpm DOUBLE NOT NULL, ceilingBpm DOUBLE NOT NULL, inBandSec DOUBLE NOT NULL DEFAULT 0, belowSec DOUBLE NOT NULL DEFAULT 0, aboveSec DOUBLE NOT NULL DEFAULT 0, pushCount INTEGER NOT NULL DEFAULT 0, easeCount INTEGER NOT NULL DEFAULT 0, hrSource TEXT NOT NULL, PRIMARY KEY (deviceId, startTs));
CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY);

INSERT INTO device VALUES ('my-whoop', 'AA:BB:CC:DD:EE:01', 'WHOOP 4.0', $BASE, $BASE + $ROWS);

-- $ROWS deterministic hrSample rows at 1 Hz for one device (the dominant table for a heavy device).
WITH RECURSIVE seq(ts) AS (
    SELECT $BASE UNION ALL SELECT ts + 1 FROM seq WHERE ts < $BASE + $ROWS - 1
)
INSERT INTO hrSample SELECT 'my-whoop', ts, 52 + (ts % 47), ts % 2 FROM seq;
SQL
GEN_END=$(date +%s)

HR_COUNT="$(sqlite3 "$FIXTURE" 'SELECT count(*) FROM hrSample')"
SIZE_BYTES="$(stat -f%z "$FIXTURE" 2>/dev/null || stat -c%s "$FIXTURE")"
SIZE_MB=$(( SIZE_BYTES / 1048576 ))
echo "fixture: $HR_COUNT hrSample rows, ${SIZE_MB} MB, generated in $((GEN_END - GEN_START))s"
[ "$HR_COUNT" -eq "$ROWS" ] || { echo "FAIL: expected $ROWS hr rows, got $HR_COUNT" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 2. Build the test bundle first (so the compiler's memory does not pollute the timed run), then run
#    the env-gated ETL bench under /usr/bin/time -l for peak RSS.
# ---------------------------------------------------------------------------
echo "building test bundle..."
swift build --package-path "$PKG" --build-tests >/dev/null 2>&1

echo "running migration..."
/usr/bin/time -l env NOOP_BENCH_ETL_FIXTURE="$FIXTURE" \
    swift test --package-path "$PKG" --filter "RoomConcurrencyTests/testBenchGrdbMigrator" \
    >"$TEST_LOG" 2>"$TIME_LOG" || { echo "FAIL: bench test failed" >&2; cat "$TEST_LOG" "$TIME_LOG" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. Parse + report.
# ---------------------------------------------------------------------------
WALL="$(grep -oE 'wall_seconds=[0-9.]+' "$TEST_LOG" | head -1 | cut -d= -f2 || true)"
TOTAL_ROWS="$(grep -oE 'total_rows=[0-9]+' "$TEST_LOG" | head -1 | cut -d= -f2 || true)"
MAXRSS_BYTES="$(grep -E 'maximum resident set size' "$TIME_LOG" | grep -oE '^[[:space:]]*[0-9]+' | tr -d ' ' | head -1 || true)"

if [ -z "${WALL:-}" ]; then
    echo "FAIL: could not parse wall time from bench output" >&2
    cat "$TEST_LOG" >&2
    exit 1
fi
MAXRSS_MB="?"
[ -n "${MAXRSS_BYTES:-}" ] && MAXRSS_MB=$(( MAXRSS_BYTES / 1048576 ))

echo ""
echo "== results =="
echo "rows migrated:   ${TOTAL_ROWS:-?}"
echo "batch size:      $BATCH_SIZE"
echo "wall time:       ${WALL}s   (target ${TARGET_SECONDS}s)"
echo "peak RSS:        ${MAXRSS_MB} MB   (test process; upper bound on migrator footprint)"

# Integer-compare the wall time (bash has no float compare) by rounding up.
WALL_CEIL="$(printf '%.0f' "$WALL")"
if [ "$WALL_CEIL" -le "$TARGET_SECONDS" ]; then
    echo "RESULT: PASS (${WALL}s <= ${TARGET_SECONDS}s)"
else
    echo "RESULT: MISS (${WALL}s > ${TARGET_SECONDS}s) — tune GrdbMigrator.BATCH_SIZE and re-run"
    exit 2
fi
