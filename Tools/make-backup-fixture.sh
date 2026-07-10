#!/usr/bin/env bash
# Trim a full .noopbak.zip into a small committed test fixture.
#
# Extracts the backup, deletes time-series rows older than KEEP_DAYS from every
# table that carries a recognised epoch-seconds column (ts / timestamp /
# epochSeconds / startTs), VACUUMs, and re-zips with noop-backup.sqlite as the
# FIRST entry (entry order is part of the cross-platform container contract:
# older importers stop at the first .sqlite entry). settings.json rides along
# when the source backup carries one.
#
# Usage: Tools/make-backup-fixture.sh <full-backup.zip> <out.zip> [keep-days]
set -euo pipefail
SRC="$1"; OUT="$2"; KEEP_DAYS="${3:-21}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
unzip -o "$SRC" -d "$WORK" > /dev/null
DB="$WORK/noop-backup.sqlite"
[ -f "$DB" ] || { echo "no noop-backup.sqlite in $SRC"; exit 1; }
CUTOFF=$(( $(date +%s) - KEEP_DAYS*86400 ))
# Trim the biggest time-series tables. Table/column names verified against
# the schema before running; adjust the list if sqlite3 reports a missing table.
for T in $(sqlite3 "$DB" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata';"); do
  HAS_TS=$(sqlite3 "$DB" "SELECT COUNT(*) FROM pragma_table_info('$T') WHERE name IN ('ts','timestamp','epochSeconds','startTs');")
  if [ "$HAS_TS" -gt 0 ]; then
    COL=$(sqlite3 "$DB" "SELECT name FROM pragma_table_info('$T') WHERE name IN ('ts','timestamp','epochSeconds','startTs') LIMIT 1;")
    sqlite3 "$DB" "DELETE FROM \"$T\" WHERE \"$COL\" < $CUTOFF AND \"$COL\" > 100000;" || true
  fi
done
sqlite3 "$DB" "VACUUM;"
( cd "$WORK" && rm -f out.zip && zip -X out.zip noop-backup.sqlite $( [ -f settings.json ] && echo settings.json ) > /dev/null )
mv "$WORK/out.zip" "$OUT"
echo "fixture: $(du -h "$OUT" | cut -f1) -> $OUT"
