CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY);
CREATE TABLE IF NOT EXISTS "device" ("id" TEXT PRIMARY KEY, "mac" TEXT, "name" TEXT, "firstSeen" INTEGER, "lastSeen" INTEGER);
CREATE TABLE IF NOT EXISTS "hrSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "bpm" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "rrInterval" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "rrMs" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts", "rrMs"));
CREATE TABLE IF NOT EXISTS "event" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "kind" TEXT NOT NULL, "payloadJSON" TEXT NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts", "kind"));
CREATE TABLE IF NOT EXISTS "battery" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "soc" DOUBLE, "mv" INTEGER, "synced" INTEGER NOT NULL DEFAULT 0, "charging" BOOLEAN, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "rawBatch" ("batchId" TEXT PRIMARY KEY, "deviceId" TEXT NOT NULL, "capturedAt" INTEGER NOT NULL, "deviceClockRef" INTEGER NOT NULL, "wallClockRef" INTEGER NOT NULL, "startTs" INTEGER NOT NULL, "endTs" INTEGER NOT NULL, "frameCount" INTEGER NOT NULL, "byteSize" INTEGER NOT NULL, "framesBlob" BLOB NOT NULL, "syncedAt" INTEGER);
CREATE TABLE IF NOT EXISTS "cursors" ("name" TEXT PRIMARY KEY, "value" INTEGER);
CREATE TABLE IF NOT EXISTS "spo2Sample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "red" INTEGER NOT NULL, "ir" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "skinTempSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "raw" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "respSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "raw" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "gravitySample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "x" DOUBLE NOT NULL, "y" DOUBLE NOT NULL, "z" DOUBLE NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "sleepSession" ("deviceId" TEXT NOT NULL, "startTs" INTEGER NOT NULL, "endTs" INTEGER NOT NULL, "efficiency" DOUBLE, "restingHr" INTEGER, "avgHrv" DOUBLE, "stagesJSON" TEXT, "userEdited" BOOLEAN NOT NULL DEFAULT 0, "startTsAdjusted" INTEGER, "motionJSON" TEXT, "sleepStateJSON" TEXT, PRIMARY KEY ("deviceId", "startTs"));
CREATE TABLE IF NOT EXISTS "dailyMetric" ("deviceId" TEXT NOT NULL, "day" TEXT NOT NULL, "totalSleepMin" DOUBLE, "efficiency" DOUBLE, "deepMin" DOUBLE, "remMin" DOUBLE, "lightMin" DOUBLE, "disturbances" INTEGER, "restingHr" INTEGER, "avgHrv" DOUBLE, "recovery" DOUBLE, "strain" DOUBLE, "exerciseCount" INTEGER, "spo2Pct" DOUBLE, "skinTempDevC" DOUBLE, "respRateBpm" DOUBLE, "steps" INTEGER, "activeKcalEst" DOUBLE, "spo2Red" INTEGER, "spo2Ir" INTEGER, PRIMARY KEY ("deviceId", "day"));
CREATE TABLE IF NOT EXISTS "journal" ("deviceId" TEXT NOT NULL, "day" TEXT NOT NULL, "question" TEXT NOT NULL, "answeredYes" INTEGER NOT NULL, "notes" TEXT, "numericValue" DOUBLE, PRIMARY KEY ("deviceId", "day", "question"));
CREATE TABLE IF NOT EXISTS "workout" ("deviceId" TEXT NOT NULL, "startTs" INTEGER NOT NULL, "endTs" INTEGER NOT NULL, "sport" TEXT NOT NULL, "source" TEXT NOT NULL, "durationS" DOUBLE, "energyKcal" DOUBLE, "avgHr" INTEGER, "maxHr" INTEGER, "strain" DOUBLE, "distanceM" DOUBLE, "zonesJSON" TEXT, "notes" TEXT, PRIMARY KEY ("deviceId", "startTs", "sport"));
CREATE TABLE IF NOT EXISTS "appleDaily" ("deviceId" TEXT NOT NULL, "day" TEXT NOT NULL, "steps" INTEGER, "activeKcal" DOUBLE, "basalKcal" DOUBLE, "vo2max" DOUBLE, "avgHr" INTEGER, "maxHr" INTEGER, "walkingHr" INTEGER, "weightKg" DOUBLE, PRIMARY KEY ("deviceId", "day"));
CREATE TABLE IF NOT EXISTS "metricSeries" ("deviceId" TEXT NOT NULL, "day" TEXT NOT NULL, "key" TEXT NOT NULL, "value" DOUBLE NOT NULL, PRIMARY KEY ("deviceId", "day", "key"));
CREATE INDEX "idx_metricSeries_device_key_day" ON "metricSeries"("deviceId", "key", "day");
CREATE TABLE IF NOT EXISTS "stepSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "counter" INTEGER NOT NULL, "activityClass" INTEGER, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "ppgHrSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "bpm" DOUBLE NOT NULL, "conf" DOUBLE NOT NULL, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE pairedDevice (
        id TEXT PRIMARY KEY NOT NULL,
        brand TEXT NOT NULL, model TEXT NOT NULL, nickname TEXT,
        sourceKind TEXT NOT NULL, capabilities TEXT NOT NULL,  -- comma-joined Metric rawValues
        status TEXT NOT NULL, addedAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL
    , peripheralId TEXT);
CREATE TABLE dayOwnership (
        day TEXT PRIMARY KEY NOT NULL,   -- "YYYY-MM-DD" local day
        deviceId TEXT NOT NULL,          -- which device owns this day's displayed/scored metrics
        locked INTEGER NOT NULL DEFAULT 0 -- 1 = explicit (import-overlap decision / user); 0 = resolver default
    );
CREATE TABLE IF NOT EXISTS "labMarker" ("id" TEXT PRIMARY KEY, "deviceId" TEXT NOT NULL, "markerKey" TEXT NOT NULL, "category" TEXT NOT NULL, "day" TEXT NOT NULL, "takenAt" INTEGER NOT NULL, "value" DOUBLE, "valueText" TEXT, "unit" TEXT NOT NULL, "source" TEXT NOT NULL, "note" TEXT, "referenceText" TEXT);
CREATE UNIQUE INDEX "idx_labMarker_natural" ON "labMarker"("deviceId", "markerKey", "takenAt", "source");
CREATE INDEX "idx_labMarker_device_marker_takenAt" ON "labMarker"("deviceId", "markerKey", "takenAt");
CREATE INDEX "idx_labMarker_device_category" ON "labMarker"("deviceId", "category");
CREATE TABLE IF NOT EXISTS "sleepStateSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL, "state" INTEGER NOT NULL, PRIMARY KEY ("deviceId", "ts"));
CREATE TABLE IF NOT EXISTS "liveSession" ("deviceId" TEXT NOT NULL, "startTs" INTEGER NOT NULL, "endTs" INTEGER, "chargeAtStart" DOUBLE, "floorBpm" DOUBLE NOT NULL, "ceilingBpm" DOUBLE NOT NULL, "inBandSec" DOUBLE NOT NULL DEFAULT 0, "belowSec" DOUBLE NOT NULL DEFAULT 0, "aboveSec" DOUBLE NOT NULL DEFAULT 0, "pushCount" INTEGER NOT NULL DEFAULT 0, "easeCount" INTEGER NOT NULL DEFAULT 0, "hrSource" TEXT NOT NULL, PRIMARY KEY ("deviceId", "startTs"));
