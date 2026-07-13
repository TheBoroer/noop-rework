#!/bin/bash
# make-grdb-fixture.sh: builds the SYNTHETIC legacy-GRDB fixture used by GrdbMigratorTest
# (Phase 2c-1 Task 2). Creates shared/src/commonTest/fixtures/grdb-mini.sqlite with the EXACT
# final GRDB schema from Packages/WhoopStore/Sources/WhoopStore/Database.swift (all 23
# migrations applied: v1..v14, v15-device-registry .. v23-daily-spo2-raw), then fills it with
# DETERMINISTIC synthetic rows (recursive CTEs, fixed epoch base, no $RANDOM, no wall clock).
#
# Content summary (3 devices: my-whoop, whoop-beta, oura-demo; 2 days 2026-07-01/02):
#   - hrSample: 1 Hz wear windows over the 2 days (~44k rows, so the migrator's 10k batching
#     is exercised across 5 transactions) + one planted spot-assert row.
#     NOTE: the task brief estimated "~172k hr rows AND a <3 MB file"; those are physically
#     incompatible (a 172.8k-row hrSample alone is ~8 MB on disk with its composite-PK
#     autoindex). The <3 MB repo-size bound wins; 1 Hz wear-gap windows keep the 2-day shape.
#   - rrInterval: 1 Hz over a 2 h sleep window (+ multi-rrMs-per-ts PK case).
#   - ppgHrSample: FRACTIONAL bpm values incl. planted half-up rounding cases (72.5, 68.25,
#     61.75, 62.5, 90.0): GRDB stores bpm as REAL, Room as INTEGER.
#   - stepSample: has NO synced column in GRDB (Room adds synced=0) + NULL/0/1/2 activityClass.
#   - workout: GRDB has NO routePolyline column (Room lands NULL).
#   - rawBatch + cursors rows exist but are SKIPPED by the migrator (stay GRDB-only).
#   - grdb_migrations marker table with all 23 identifiers.
# Every value is synthetic (formulaic); nothing is derived from real user data.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$REPO_ROOT/shared/src/commonTest/fixtures/grdb-mini.sqlite}"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"

# 2026-07-01T00:00:00Z. Fixed forever: determinism, never "now".
BASE=1782864000
DAY2=$((BASE + 86400))

sqlite3 "$OUT" <<SQL
BEGIN;

-- ---------------------------------------------------------------------------
-- Exact final GRDB schema (Database.swift v1..v23). Column ORDER matches a
-- really-migrated file: ALTER-added columns (synced, charging, userEdited, ...)
-- come after the original creation columns. GRDB type names kept verbatim
-- (TEXT / INTEGER / DOUBLE / BOOLEAN / BLOB).
-- ---------------------------------------------------------------------------
CREATE TABLE device (
    id TEXT PRIMARY KEY,
    mac TEXT, name TEXT, firstSeen INTEGER, lastSeen INTEGER
);
CREATE TABLE hrSample (
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE rrInterval (
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, rrMs INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts, rrMs)
);
CREATE TABLE event (
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL,
    payloadJSON TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts, kind)
);
CREATE TABLE battery (
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, soc DOUBLE, mv INTEGER,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    charging BOOLEAN,                                  -- v6
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE rawBatch (                                 -- SKIPPED by the migrator
    batchId TEXT PRIMARY KEY, deviceId TEXT NOT NULL, capturedAt INTEGER NOT NULL,
    deviceClockRef INTEGER NOT NULL, wallClockRef INTEGER NOT NULL,
    startTs INTEGER NOT NULL, endTs INTEGER NOT NULL, frameCount INTEGER NOT NULL,
    byteSize INTEGER NOT NULL, framesBlob BLOB NOT NULL, syncedAt INTEGER
);
CREATE TABLE cursors (                                  -- v2, SKIPPED by the migrator
    name TEXT PRIMARY KEY, value INTEGER
);
CREATE TABLE spo2Sample (                               -- v3
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, red INTEGER NOT NULL, ir INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE skinTempSample (                           -- v3
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, raw INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE respSample (                               -- v3
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, raw INTEGER NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE gravitySample (                            -- v3
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL,
    x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0,                 -- v5
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE sleepSession (                             -- v4
    deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL,
    efficiency DOUBLE, restingHr INTEGER, avgHrv DOUBLE, stagesJSON TEXT,
    userEdited BOOLEAN NOT NULL DEFAULT 0,             -- v13
    startTsAdjusted INTEGER,                           -- v14
    motionJSON TEXT, sleepStateJSON TEXT,              -- v18
    PRIMARY KEY (deviceId, startTs)
);
CREATE TABLE dailyMetric (                              -- v4
    deviceId TEXT NOT NULL, day TEXT NOT NULL,
    totalSleepMin DOUBLE, efficiency DOUBLE, deepMin DOUBLE, remMin DOUBLE, lightMin DOUBLE,
    disturbances INTEGER, restingHr INTEGER, avgHrv DOUBLE, recovery DOUBLE, strain DOUBLE,
    exerciseCount INTEGER,
    spo2Pct DOUBLE, skinTempDevC DOUBLE, respRateBpm DOUBLE,  -- v7
    steps INTEGER, activeKcalEst DOUBLE,                       -- v11
    spo2Red INTEGER, spo2Ir INTEGER,                           -- v23
    PRIMARY KEY (deviceId, day)
);
CREATE TABLE journal (                                  -- v8
    deviceId TEXT NOT NULL, day TEXT NOT NULL, question TEXT NOT NULL,
    answeredYes INTEGER NOT NULL, notes TEXT,
    numericValue DOUBLE,                               -- v20
    PRIMARY KEY (deviceId, day, question)
);
CREATE TABLE workout (                                  -- v8 (NO routePolyline in GRDB)
    deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL,
    sport TEXT NOT NULL, source TEXT NOT NULL,
    durationS DOUBLE, energyKcal DOUBLE, avgHr INTEGER, maxHr INTEGER,
    strain DOUBLE, distanceM DOUBLE, zonesJSON TEXT, notes TEXT,
    PRIMARY KEY (deviceId, startTs, sport)
);
CREATE TABLE appleDaily (                               -- v8
    deviceId TEXT NOT NULL, day TEXT NOT NULL,
    steps INTEGER, activeKcal DOUBLE, basalKcal DOUBLE, vo2max DOUBLE,
    avgHr INTEGER, maxHr INTEGER, walkingHr INTEGER, weightKg DOUBLE,
    PRIMARY KEY (deviceId, day)
);
CREATE TABLE metricSeries (                             -- v9
    deviceId TEXT NOT NULL, day TEXT NOT NULL, key TEXT NOT NULL, value DOUBLE NOT NULL,
    PRIMARY KEY (deviceId, day, key)
);
CREATE INDEX idx_metricSeries_device_key_day ON metricSeries (deviceId, key, day);
CREATE TABLE stepSample (                               -- v10 (NO synced column in GRDB)
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, counter INTEGER NOT NULL,
    activityClass INTEGER,                             -- v19
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE ppgHrSample (                              -- v12 (bpm is REAL; NO synced in GRDB)
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, bpm DOUBLE NOT NULL, conf DOUBLE NOT NULL,
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE pairedDevice (                             -- v15
    id TEXT PRIMARY KEY NOT NULL,
    brand TEXT NOT NULL, model TEXT NOT NULL, nickname TEXT,
    sourceKind TEXT NOT NULL, capabilities TEXT NOT NULL,
    status TEXT NOT NULL, addedAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL,
    peripheralId TEXT                                  -- v16
);
CREATE TABLE dayOwnership (                             -- v15
    day TEXT PRIMARY KEY NOT NULL,
    deviceId TEXT NOT NULL,
    locked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE labMarker (                                -- v17
    id TEXT PRIMARY KEY,
    deviceId TEXT NOT NULL, markerKey TEXT NOT NULL, category TEXT NOT NULL,
    day TEXT NOT NULL, takenAt INTEGER NOT NULL,
    value DOUBLE, valueText TEXT, unit TEXT NOT NULL, source TEXT NOT NULL,
    note TEXT, referenceText TEXT
);
CREATE UNIQUE INDEX idx_labMarker_natural ON labMarker (deviceId, markerKey, takenAt, source);
CREATE INDEX idx_labMarker_device_marker_takenAt ON labMarker (deviceId, markerKey, takenAt);
CREATE INDEX idx_labMarker_device_category ON labMarker (deviceId, category);
CREATE TABLE sleepStateSample (                         -- v21
    deviceId TEXT NOT NULL, ts INTEGER NOT NULL, state INTEGER NOT NULL,
    PRIMARY KEY (deviceId, ts)
);
CREATE TABLE liveSession (                              -- v22
    deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER,
    chargeAtStart DOUBLE, floorBpm DOUBLE NOT NULL, ceilingBpm DOUBLE NOT NULL,
    inBandSec DOUBLE NOT NULL DEFAULT 0, belowSec DOUBLE NOT NULL DEFAULT 0,
    aboveSec DOUBLE NOT NULL DEFAULT 0,
    pushCount INTEGER NOT NULL DEFAULT 0, easeCount INTEGER NOT NULL DEFAULT 0,
    hrSource TEXT NOT NULL,
    PRIMARY KEY (deviceId, startTs)
);
-- GRDB's own migration bookkeeping (marker table the migrator must leave behind).
CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY);

-- ---------------------------------------------------------------------------
-- Deterministic synthetic data. BASE = $BASE (2026-07-01T00:00:00Z), DAY2 = BASE+86400.
-- ---------------------------------------------------------------------------
INSERT INTO grdb_migrations VALUES
  ('v1'),('v2'),('v3'),('v4'),('v5'),('v6'),('v7'),('v8'),('v9'),('v10'),
  ('v11'),('v12'),('v13'),('v14'),
  ('v15-device-registry'),('v16-paired-device-peripheral'),('v17-lab-book'),
  ('v18-sleep-motion-state'),('v19-step-activity-class'),('v20-journal-numeric'),
  ('v21-sleep-state-sample'),('v22-live-session'),('v23-daily-spo2-raw');

INSERT INTO device VALUES
  ('my-whoop',   'AA:BB:CC:DD:EE:01', 'WHOOP 4.0', $BASE - 2592000, $DAY2 + 50000),
  ('whoop-beta', 'AA:BB:CC:DD:EE:02', 'WHOOP 5.0', $BASE,           $DAY2 + 58200),
  ('oura-demo',  NULL,                NULL,        $BASE,           $BASE + 72600);

-- hrSample: my-whoop worn 08:00-14:00 both days at 1 Hz (2 x 21600), whoop-beta and
-- oura-demo 600 s each, + 1 planted spot-assert row. Total 44401 -> five 10k batches.
WITH RECURSIVE seq(ts) AS (SELECT $BASE + 28800 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $BASE + 50399)
INSERT INTO hrSample SELECT 'my-whoop', ts, 52 + (ts % 47), ts % 2 FROM seq;
WITH RECURSIVE seq(ts) AS (SELECT $DAY2 + 28800 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $DAY2 + 50399)
INSERT INTO hrSample SELECT 'my-whoop', ts, 52 + (ts % 47), 0 FROM seq;
WITH RECURSIVE seq(ts) AS (SELECT $DAY2 + 57600 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $DAY2 + 58199)
INSERT INTO hrSample SELECT 'whoop-beta', ts, 60 + (ts % 30), 0 FROM seq;
WITH RECURSIVE seq(ts) AS (SELECT $BASE + 72000 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $BASE + 72599)
INSERT INTO hrSample SELECT 'oura-demo', ts, 55 + (ts % 25), 1 FROM seq;
INSERT INTO hrSample VALUES ('whoop-beta', $BASE + 1000, 143, 1);   -- spot-assert row

-- rrInterval: my-whoop 1 Hz over a 2 h sleep window + two extra rrMs at the SAME ts
-- (exercises the 3-column PK), + 10 whoop-beta rows.
WITH RECURSIVE seq(ts) AS (SELECT $BASE + 82800 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $BASE + 89999)
INSERT INTO rrInterval SELECT 'my-whoop', ts, 700 + (ts % 300), ts % 2 FROM seq;
INSERT INTO rrInterval VALUES ('my-whoop', $BASE + 82800, 1500, 0), ('my-whoop', $BASE + 82800, 1600, 0);
WITH RECURSIVE seq(ts) AS (SELECT $DAY2 + 57600 UNION ALL SELECT ts + 1 FROM seq WHERE ts < $DAY2 + 57609)
INSERT INTO rrInterval SELECT 'whoop-beta', ts, 800 + (ts % 100), 0 FROM seq;

-- event: 24 rows, one per hour of day 1.
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 23)
INSERT INTO event
SELECT 'my-whoop', $BASE + i * 3600,
       CASE i % 4 WHEN 0 THEN 'WRIST_ON' WHEN 1 THEN 'WRIST_OFF'
                  WHEN 2 THEN 'BATTERY_LEVEL' ELSE 'DOUBLE_TAP' END,
       '{"seq":' || i || '}', i % 2
FROM n;

-- battery: 240 rows every 12 min across the 2 days; charging NULL / 0 / 1 mix.
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 239)
INSERT INTO battery
SELECT 'my-whoop', $BASE + i * 720, 100.0 - (i % 80) - 0.5, 3700 + (i % 400), i % 2,
       CASE i % 10 WHEN 0 THEN 1 WHEN 5 THEN 0 ELSE NULL END
FROM n;

-- Night-window biometric streams (10 s cadence over 2 h).
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 719)
INSERT INTO spo2Sample SELECT 'my-whoop', $BASE + 82800 + i * 10, 52000 + (i % 1000), 61000 + (i % 1200), CASE i % 3 WHEN 0 THEN 1 ELSE 0 END FROM n;
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 719)
INSERT INTO skinTempSample SELECT 'my-whoop', $BASE + 82800 + i * 10, 21000 + (i % 600), i % 2 FROM n;
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 719)
INSERT INTO respSample SELECT 'my-whoop', $BASE + 82800 + i * 10, 15000 + (i % 400), 0 FROM n;
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 359)
INSERT INTO gravitySample SELECT 'my-whoop', $BASE + 82800 + i * 20, ((i % 21) - 10) / 10.0, ((i % 17) - 8) / 8.0, 0.5 + (i % 10) / 20.0, 0 FROM n;
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 719)
INSERT INTO sleepStateSample SELECT 'my-whoop', $BASE + 82800 + i * 10, i % 4 FROM n;

-- stepSample: cumulative u16 counter + NULL/0/1/2 activityClass mix, + planted spot row.
-- No synced column here: the Room side must land synced=0.
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 719)
INSERT INTO stepSample SELECT 'my-whoop', $BASE + 28800 + i * 60, (i * 37) % 65536,
       CASE i % 4 WHEN 0 THEN NULL ELSE i % 3 END FROM n;
INSERT INTO stepSample VALUES ('my-whoop', $BASE + 20000, 4242, 2);  -- spot-assert row

-- ppgHrSample: FRACTIONAL REAL bpm (eighth steps) + planted half-up rounding cases.
-- No synced column here either: Room lands synced=0 and bpm rounded half-up to INTEGER.
WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 599)
INSERT INTO ppgHrSample SELECT 'my-whoop', $DAY2 + 82800 + i * 10, 55.0 + (i % 200) / 8.0, 0.5 + (i % 50) / 100.0 FROM n;
INSERT INTO ppgHrSample VALUES
  ('my-whoop', $BASE + 40000, 72.5,  0.9),   -- rounds half-up to 73
  ('my-whoop', $BASE + 40001, 68.25, 0.55),  -- rounds to 68
  ('my-whoop', $BASE + 40002, 61.75, 0.7),   -- rounds to 62
  ('my-whoop', $BASE + 40003, 62.5,  0.8),   -- rounds half-UP to 63 (not banker's 62)
  ('my-whoop', $BASE + 40004, 90.0,  1.0);   -- integral stays 90

INSERT INTO sleepSession VALUES
  ('my-whoop',   $BASE + 79200, $DAY2 + 21600, 92.5, 48, 65.5,
   '[{"stage":"deep","startTs":' || ($BASE + 79200) || ',"endTs":' || ($BASE + 82800) || '}]',
   0, NULL, '[0.12,0.05,0.3]', '[0,2,2,1]'),
  ('my-whoop',   $DAY2 + 79200, $DAY2 + 108000, NULL, NULL, NULL, NULL, 1, $DAY2 + 81000, NULL, NULL),
  ('whoop-beta', $BASE + 80000, $DAY2 + 20000, 88.0, 52, 45.25, NULL, 0, NULL, NULL, NULL),
  ('oura-demo',  $BASE + 81000, $DAY2 + 19000, 79.5, 55, 38.0,  NULL, 0, NULL, NULL, NULL);

INSERT INTO dailyMetric VALUES
  ('my-whoop',   '2026-07-01', 432.0, 92.5, 95.0, 110.0, 200.0, 12, 48, 65.5, 67.0, 11.4, 2,
                 96.5, -0.3, 14.8, 8421, 2210.5, 12345, 23456),
  ('my-whoop',   '2026-07-02', 410.5, 90.0, NULL, NULL, NULL, NULL, 49, 62.25, 71.0, 9.8, 1,
                 NULL, NULL, NULL, 7100, NULL, NULL, NULL),
  ('whoop-beta', '2026-07-01', 395.0, NULL, NULL, NULL, NULL, NULL, 52, 45.25, 55.0, NULL, 0,
                 NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('whoop-beta', '2026-07-02', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                 NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('oura-demo',  '2026-07-01', 388.0, 85.0, NULL, NULL, NULL, 8, 55, 38.0, 61.5, NULL, NULL,
                 95.0, 0.1, 15.2, NULL, NULL, NULL, NULL),
  ('oura-demo',  '2026-07-02', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 58.0, NULL, NULL,
                 NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO journal VALUES
  ('my-whoop',   '2026-07-01', 'Caffeine?',  1, NULL,    180.0),
  ('my-whoop',   '2026-07-01', 'Alcohol?',   0, 'none',  NULL),
  ('my-whoop',   '2026-07-01', 'Late meal?', 1, 'pizza', NULL),
  ('my-whoop',   '2026-07-02', 'Caffeine?',  1, NULL,    95.5),
  ('whoop-beta', '2026-07-01', 'Caffeine?',  0, NULL,    NULL),
  ('whoop-beta', '2026-07-01', 'Stretch?',   1, NULL,    NULL),
  ('oura-demo',  '2026-07-02', 'Caffeine?',  1, NULL,    120.0),
  ('oura-demo',  '2026-07-02', 'Alcohol?',   1, '1 beer', 1.0);

-- workout: NO routePolyline column exists here; the Room copy must land it NULL.
INSERT INTO workout VALUES
  ('my-whoop',   $BASE + 61200, $BASE + 64800, 'running',  'my-whoop',      3600.0, 650.5, 152, 181, 14.2, 10000.0, '[10,20,30,25,15]', NULL),
  ('my-whoop',   $DAY2 + 61200, $DAY2 + 63000, 'detected', 'my-whoop-noop', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('my-whoop',   $DAY2 + 30000, $DAY2 + 31800, 'yoga',     'apple-health',  1800.0, 120.0, 95, 110, NULL, NULL, NULL, 'synthetic note'),
  ('whoop-beta', $BASE + 61200, $BASE + 62000, 'cycling',  'my-whoop',      800.0, 200.0, 130, 150, 8.5, 5000.0, NULL, NULL),
  ('oura-demo',  $BASE + 50000, $BASE + 51000, 'walking',  'fileImport',    1000.0, 80.0, NULL, NULL, NULL, 1200.0, NULL, NULL);

INSERT INTO appleDaily VALUES
  ('my-whoop',   '2026-07-01', 10234, 523.5, 1600.0, 44.2, 72, 165, 98, 78.4),
  ('my-whoop',   '2026-07-02', 8432,  410.0, 1598.5, NULL, 70, 151, 95, NULL),
  ('whoop-beta', '2026-07-01', NULL,  NULL,  NULL,   NULL, NULL, NULL, NULL, 81.2),
  ('oura-demo',  '2026-07-02', 6001,  300.25, NULL,  NULL, 68, NULL, NULL, NULL);

-- metricSeries: 5 keys x 3 devices x 2 days = 30 formulaic rows.
WITH devices(d) AS (VALUES ('my-whoop'), ('whoop-beta'), ('oura-demo')),
     days(day) AS (VALUES ('2026-07-01'), ('2026-07-02')),
     keys(k) AS (VALUES ('recovery'), ('strain'), ('hrv'), ('rhr'), ('sleepMin'))
INSERT INTO metricSeries
SELECT d, day, k,
       10.0 * length(k) + length(d) + (CASE day WHEN '2026-07-01' THEN 0.25 ELSE 0.75 END)
FROM devices, days, keys;

INSERT INTO labMarker VALUES
  ('lab-0001', 'my-whoop', 'ferritin',  'blood',   '2026-07-01', $BASE + 36000, 85.0, NULL,       'ng/mL',  'manual', NULL,      '30-300'),
  ('lab-0002', 'my-whoop', 'crp',       'blood',   '2026-07-01', $BASE + 36000, 1.2,  NULL,       'mg/L',   'manual', 'fasting', NULL),
  ('lab-0003', 'my-whoop', 'rapidTest', 'illness', '2026-07-02', $DAY2 + 40000, NULL, 'negative', 'result', 'manual', NULL,      NULL),
  ('lab-0004', 'my-whoop', 'vitaminD',  'blood',   '2026-07-02', $DAY2 + 41000, 32.5, NULL,       'ng/mL',  'import', NULL,      '30-100'),
  ('lab-0005', 'whoop-beta', 'glucose', 'blood',   '2026-07-01', $BASE + 37000, 5.4,  NULL,       'mmol/L', 'manual', 'morning', '4.0-6.0'),
  ('lab-0006', 'oura-demo', 'hba1c',    'blood',   '2026-07-02', $DAY2 + 42000, 34.0, NULL,       'mmol/mol', 'manual', NULL,    NULL);

INSERT INTO pairedDevice VALUES
  ('my-whoop',   'WHOOP', 'WHOOP',     NULL,         'liveBLE',    'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active',   $BASE,       $DAY2 + 50000, NULL),
  ('whoop-beta', 'WHOOP', 'WHOOP 5.0', 'Beta strap', 'historyBLE', 'hr,hrv,sleep',                          'paired',   $BASE + 100, $DAY2 + 58200, '6BA7B810-9DAD-11D1-80B4-00C04FD430C8'),
  ('oura-demo',  'Oura',  'Gen3',      NULL,         'oura',       'hr,hrv,sleep',                          'archived', $BASE + 200, $BASE + 72600, NULL);

INSERT INTO dayOwnership VALUES
  ('2026-07-01', 'my-whoop', 0),
  ('2026-07-02', 'my-whoop', 1);

INSERT INTO liveSession VALUES
  ('my-whoop',   $BASE + 61200, $BASE + 63000, 82.5,  100.0, 140.0, 1500.0, 200.0, 100.0, 3, 1, 'strap'),
  ('my-whoop',   $DAY2 + 61200, NULL,          NULL,  95.0,  135.0, 0.0,    0.0,   0.0,   0, 0, 'demo'),
  ('whoop-beta', $BASE + 62000, $BASE + 62600, 50.25, 90.0,  120.0, 400.0,  100.0, 100.0, 1, 2, 'strap');

-- SKIPPED tables get real rows so the tests can prove the migrator leaves them behind.
INSERT INTO rawBatch VALUES
  ('batch-0001', 'my-whoop', $BASE + 1000, 123456, $BASE + 900,  $BASE,       $BASE + 900,  42, 16, X'00112233445566778899AABBCCDDEEFF', $BASE + 2000),
  ('batch-0002', 'my-whoop', $BASE + 2000, 123457, $BASE + 1900, $BASE + 900, $BASE + 1900, 17, 8,  X'0102030405060708',                 NULL);
INSERT INTO cursors VALUES ('hr_highwater', 1782950000), ('offload_page', NULL);

COMMIT;
VACUUM;
SQL

# ---------------------------------------------------------------------------
# Verification: tables present, marker table populated, size bound, row counts.
# ---------------------------------------------------------------------------
TABLES=$(sqlite3 "$OUT" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
MIGS=$(sqlite3 "$OUT" "SELECT count(*) FROM grdb_migrations")
SIZE=$(stat -f%z "$OUT" 2>/dev/null || stat -c%s "$OUT")

[ "$TABLES" -eq 25 ] || { echo "FAIL: expected 25 tables (24 GRDB + grdb_migrations), got $TABLES" >&2; exit 1; }
[ "$MIGS" -eq 23 ]   || { echo "FAIL: expected 23 grdb_migrations rows, got $MIGS" >&2; exit 1; }
[ "$SIZE" -lt 3145728 ] || { echo "FAIL: fixture is $SIZE bytes (>= 3 MB)" >&2; exit 1; }

echo "grdb-mini.sqlite: $SIZE bytes, $TABLES tables, $MIGS migration markers"
for t in device hrSample rrInterval event battery spo2Sample skinTempSample respSample gravitySample \
         sleepSession dailyMetric journal workout appleDaily metricSeries stepSample ppgHrSample \
         pairedDevice dayOwnership labMarker sleepStateSample liveSession rawBatch cursors; do
    echo "  $t: $(sqlite3 "$OUT" "SELECT count(*) FROM $t")"
done
