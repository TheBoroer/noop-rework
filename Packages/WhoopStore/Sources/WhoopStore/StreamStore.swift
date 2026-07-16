import Foundation
import WhoopProtocol
import Shared

extension WhoopStore {
    /// Deterministic JSON for an event payload (sorted keys so the same payload always
    /// serializes byte-identically, important for the natural-key dedupe and parity).
    static func encodePayload(_ payload: [String: ParsedValue]) throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        let data = try enc.encode(payload)
        return String(decoding: data, as: UTF8.self)
    }

    /// Insert or update a device row (natural key = id).
    public func upsertDevice(id: String, mac: String?, name: String?) async throws {
        let now = Int(Date().timeIntervalSince1970)
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            // Byte-for-byte with the GRDB `ON CONFLICT(id) DO UPDATE SET mac, name, lastSeen`: mirror
            // WhoopRepository.upsertDevice, read the existing row and PRESERVE its firstSeen on update
            // (the @Insert(REPLACE) upsert would otherwise reset it), while mac/name/lastSeen take the
            // new values. A first insert stamps firstSeen = lastSeen = now.
            let dao = roomDb.whoopDao()
            let existing = try await dao.device(id: id)
            let firstSeen = existing?.firstSeen ?? KotlinLong(longLong: Int64(now))
            try await dao.upsertDevice(device: DeviceRow(
                id: id, mac: mac, name: name,
                firstSeen: firstSeen, lastSeen: KotlinLong(longLong: Int64(now))))
            #endif
        }
    }

    /// Idempotent upsert of decoded streams by natural key. Returns the number of rows
    /// ACTUALLY inserted per stream (0 for rows that already existed).
    ///
    /// NOTE: the `synced` column (added by migration v5 for a since-removed server-upload feature)
    /// is intentionally NOT written here, it is unused and defaults to 0. The column is left in the
    /// schema to avoid a DROP COLUMN migration over existing data; nothing reads it.
    // NOTE on qualification: this file imports BOTH WhoopProtocol and Shared, and the Kotlin
    // entities deliberately reuse several Swift row-type names (Streams, BatterySample,
    // SkinTempSample, StepSample, RespSample, GravitySample, PpgHrSample, WhoopEvent). Every
    // ambiguous name in this file is module-qualified so the decoded-row currency stays
    // WhoopProtocol's and the Room entity rows stay Shared's, never mixed up by unqualified lookup.
    @discardableResult
    public func insert(_ streams: WhoopProtocol.Streams, deviceId: String) async throws
        -> (hr: Int, rr: Int, events: Int, battery: Int,
            spo2: Int, skinTemp: Int, resp: Int, gravity: Int) {
        switch backend {
        case .room(let roomDb):
            return try await insertViaRoom(streams, deviceId: deviceId, room: roomDb)
        }
    }

    /// Number of rows Room actually inserted from a `@Insert(onConflict = IGNORE)` result: Room returns
    /// one rowid per input row, `-1` for a row skipped by the natural-key conflict. Counting the non-`-1`
    /// entries reproduces GRDB's per-stream `db.changesCount` tally exactly (an existing row → 0).
    private static func insertedCount(_ rowids: [KotlinLong]) -> Int {
        rowids.reduce(0) { $0 + ($1.int64Value == -1 ? 0 : 1) }
    }

    /// Room write funnel (room backend): the byte-for-byte twin of `insertViaGrdb`. Each stream maps to
    /// its Room entity and goes through the matching `@Insert(onConflict = IGNORE)` DAO method, the same
    /// natural-key dedupe as GRDB's `ON CONFLICT(...) DO NOTHING`. The four counted streams (hr/rr/events/
    /// battery) plus spo2/skinTemp/resp/gravity return their actually-inserted count; steps/sleepState/
    /// ppgHr are persist-only, exactly as in the GRDB path. Empty sub-lists issue no DAO call. `event`
    /// payloads use the SAME deterministic `encodePayload` so the stored JSON is byte-identical.
    private func insertViaRoom(_ streams: WhoopProtocol.Streams, deviceId: String, room: WhoopDatabase)
        async throws -> (hr: Int, rr: Int, events: Int, battery: Int,
                         spo2: Int, skinTemp: Int, resp: Int, gravity: Int) {
        #if targetEnvironment(simulator) && arch(x86_64)
        _ = (streams, deviceId, room); throw WhoopStore.RoomBackendUnavailableError()
        #else
        let dao = room.whoopDao()
        var hr = 0, rr = 0, ev = 0, bat = 0
        var spo2 = 0, skin = 0, resp = 0, grav = 0

        if !streams.hr.isEmpty {
            let rows = streams.hr.map {
                HrSample(deviceId: deviceId, ts: Int64($0.ts), bpm: Int32($0.bpm), synced: 0)
            }
            hr = WhoopStore.insertedCount(try await dao.insertHr(rows: rows))
        }
        if !streams.rr.isEmpty {
            let rows = streams.rr.map {
                RrInterval(deviceId: deviceId, ts: Int64($0.ts), rrMs: Int32($0.rrMs), synced: 0)
            }
            rr = WhoopStore.insertedCount(try await dao.insertRr(rows: rows))
        }
        if !streams.events.isEmpty {
            var rows: [EventRow] = []
            rows.reserveCapacity(streams.events.count)
            for e in streams.events {
                let json = try WhoopStore.encodePayload(e.payload)
                rows.append(EventRow(deviceId: deviceId, ts: Int64(e.ts), kind: e.kind,
                                     payloadJSON: json, synced: 0))
            }
            ev = WhoopStore.insertedCount(try await dao.insertEvents(rows: rows))
        }
        if !streams.battery.isEmpty {
            let rows = streams.battery.map { b in
                Shared.BatterySample(deviceId: deviceId, ts: Int64(b.ts),
                              soc: b.soc.map { KotlinDouble(double: $0) },
                              mv: b.mv.map { KotlinInt(int: Int32($0)) },
                              charging: b.charging.map { KotlinBoolean(bool: $0) },
                              synced: 0)
            }
            bat = WhoopStore.insertedCount(try await dao.insertBattery(rows: rows))
        }
        if !streams.spo2.isEmpty {
            let rows = streams.spo2.map {
                Spo2Sample(deviceId: deviceId, ts: Int64($0.ts),
                           red: Int32($0.red), ir: Int32($0.ir), synced: 0)
            }
            spo2 = WhoopStore.insertedCount(try await dao.insertSpo2(rows: rows))
        }
        if !streams.skinTemp.isEmpty {
            let rows = streams.skinTemp.map {
                Shared.SkinTempSample(deviceId: deviceId, ts: Int64($0.ts), raw: Int32($0.raw), synced: 0)
            }
            skin = WhoopStore.insertedCount(try await dao.insertSkinTemp(rows: rows))
        }
        if !streams.resp.isEmpty {
            let rows = streams.resp.map {
                Shared.RespSample(deviceId: deviceId, ts: Int64($0.ts), raw: Int32($0.raw), synced: 0)
            }
            resp = WhoopStore.insertedCount(try await dao.insertResp(rows: rows))
        }
        if !streams.gravity.isEmpty {
            let rows = streams.gravity.map {
                Shared.GravitySample(deviceId: deviceId, ts: Int64($0.ts),
                              x: $0.x, y: $0.y, z: $0.z, synced: 0)
            }
            grav = WhoopStore.insertedCount(try await dao.insertGravity(rows: rows))
        }
        // Persist-only streams (not surfaced in the 8-field tuple), exactly as the GRDB path leaves them.
        if !streams.steps.isEmpty {
            let rows = streams.steps.map { s in
                Shared.StepSample(deviceId: deviceId, ts: Int64(s.ts), counter: Int32(s.counter),
                           activityClass: s.activityClass.map { KotlinInt(int: Int32($0)) }, synced: 0)
            }
            _ = try await dao.insertSteps(rows: rows)
        }
        if !streams.sleepState.isEmpty {
            let rows = streams.sleepState.map {
                SleepStateSampleEntity(deviceId: deviceId, ts: Int64($0.ts), state: Int32($0.state))
            }
            _ = try await dao.insertSleepState(rows: rows)
        }
        if !streams.ppgHr.isEmpty {
            let rows = streams.ppgHr.map {
                Shared.PpgHrSample(deviceId: deviceId, ts: Int64($0.ts),
                            bpm: Int32($0.bpm), conf: $0.conf, synced: 0)
            }
            _ = try await dao.insertPpgHr(rows: rows)
        }
        return (hr, rr, ev, bat, spo2, skin, resp, grav)
        #endif
    }

    // MARK: - Raw sensor CSV export (diagnostic)

    /// Long-format CSV column order. One stream's columns are filled per row; the rest stay blank.
    private static let rawCSVHeader =
        "unix_s,iso_utc,stream,hr_bpm,rr_ms,grav_x,grav_y,grav_z,step_counter," +
        "ppg_bpm,ppg_conf,spo2_red,spo2_ir,skintemp_raw,resp_raw,band_sleep_state,event_kind,event_payload"

    /// One assembled CSV line: the 16 columns AFTER the `unix_s,iso_utc` prefix, joined with commas.
    /// `cols[0]` is the `stream` name; `cols[1...15]` are the per-stream value slots, only the ones
    /// that belong to this row's stream are non-empty.
    private struct RawCSVRow {
        let ts: Int
        var cols: [String]
        init(ts: Int) { self.ts = ts; self.cols = Array(repeating: "", count: 16) }
    }

    /// Export the decoded per-sample sensor streams NOOP already stores to ONE combined long-format CSV
    /// (header + one row per sample, all streams interleaved and sorted by ts ascending). On-device,
    /// plain text, no BLE hex, a diagnostic so power users / external devs can prototype sleep/activity/
    /// VBT algorithms on real data without a BLE stream (#308/#276/#322).
    ///
    /// `since` is a unix-seconds floor (caller passes now-24h); rows with `ts >= since` for `deviceId`
    /// are included. Writes to a temp file and returns its URL (caller hands it to the share/save flow).
    public func exportRawCSV(deviceId: String, since: TimeInterval) async throws -> URL {
        let floor = Int64(since)

        // All ten streams are read through the Room DAO (single-connection discipline -- never
        // side-open the live `noop.db` with a second SQLite handle). Same per-table raw reads the
        // old GRDB block issued: `ts >= floor` for `deviceId`, ORDER BY ts, no upper bound or cap.
        var out: [RawCSVRow] = []
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let dao = roomDb.whoopDao()
            let hi = Int64.max
            let cap = Int32.max

            // hr: stream=hr -> hr_bpm (col 3). rawHrSamples is the measured `hrSample` table ONLY,
            // NOT the ppgHr-COALESCEd `hrSamples` read -- ppghr is exported as its own stream below.
            for r in try await dao.rawHrSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "hr"
                row.cols[1] = WhoopStore.intStr(Int(r.bpm))
                out.append(row)
            }
            // rr: stream=rr -> rr_ms (col 4).
            for r in try await dao.rrIntervals(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "rr"
                row.cols[2] = WhoopStore.intStr(Int(r.rrMs))
                out.append(row)
            }
            // gravity: stream=gravity -> grav_x/y/z (cols 5-7).
            for r in try await dao.gravitySamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "gravity"
                row.cols[3] = WhoopStore.dblStr(r.x)
                row.cols[4] = WhoopStore.dblStr(r.y)
                row.cols[5] = WhoopStore.dblStr(r.z)
                out.append(row)
            }
            // steps: stream=steps -> step_counter (col 8).
            for r in try await dao.stepSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "steps"
                row.cols[6] = WhoopStore.intStr(Int(r.counter))
                out.append(row)
            }
            // ppghr: stream=ppghr -> ppg_bpm/ppg_conf (cols 9-10). Room stores bpm as INTEGER
            // (rounded half-up at write/ETL time); format through Double so the CSV stays
            // byte-compatible with the GRDB-era exports ("72.0", not "72").
            for r in try await dao.ppgHrSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "ppghr"
                row.cols[7] = WhoopStore.dblStr(Double(r.bpm))
                row.cols[8] = WhoopStore.dblStr(r.conf)
                out.append(row)
            }
            // spo2: stream=spo2 -> spo2_red/spo2_ir (cols 11-12).
            for r in try await dao.spo2Samples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "spo2"
                row.cols[9] = WhoopStore.intStr(Int(r.red))
                row.cols[10] = WhoopStore.intStr(Int(r.ir))
                out.append(row)
            }
            // skintemp: stream=skintemp -> skintemp_raw (col 13).
            for r in try await dao.skinTempSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "skintemp"
                row.cols[11] = WhoopStore.intStr(Int(r.raw))
                out.append(row)
            }
            // resp: stream=resp -> resp_raw (col 14).
            for r in try await dao.respSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "resp"
                row.cols[12] = WhoopStore.intStr(Int(r.raw))
                out.append(row)
            }
            // band sleep_state (#175): stream=band_sleep_state -> band_sleep_state (col 15). The
            // strap's OWN @81 high-nibble state (0 wake/1 still/2 asleep/3 up), carried verbatim.
            for r in try await dao.sleepStateSamples(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "band_sleep_state"
                row.cols[13] = WhoopStore.intStr(Int(r.state))
                out.append(row)
            }
            // event: stream=event -> event_kind/event_payload (cols 16-17). Payload is free-form
            // JSON, so it always goes through the CSV-quote escaper (commas/quotes/newlines).
            for r in try await dao.events(deviceId: deviceId, from: floor, to: hi, limit: cap) {
                var row = RawCSVRow(ts: Int(r.ts)); row.cols[0] = "event"
                row.cols[14] = WhoopStore.csvField(r.kind)
                row.cols[15] = WhoopStore.csvField(r.payloadJSON)
                out.append(row)
            }
            #endif
        }

        // Stable sort by ts ascending. `sorted` is not guaranteed stable, but ties only occur across
        // different streams at the same second, any interleaving of those is acceptable here.
        out.sort { $0.ts < $1.ts }
        let rows = out

        // Stream the rows straight to disk through a FileHandle, flushing in ~64 KB chunks, instead of
        // building the whole CSV as one in-memory String: a busy 24 h export otherwise held tens of MB
        // twice, the assembled String plus its UTF-8 Data copy that `write(to:)` makes, and could OOM
        // (#406, parity with the Android exporter's streaming fix).
        let iso = ISO8601DateFormatter()
        iso.timeZone = TimeZone(identifier: "UTC")
        iso.formatOptions = [.withInternetDateTime]

        let stamp = Int(Date().timeIntervalSince1970)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-raw-sensors-\(stamp).csv")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        try handle.write(contentsOf: Data((WhoopStore.rawCSVHeader + "\n").utf8))
        var buf = String()
        buf.reserveCapacity(72 * 1024)
        for row in rows {
            let isoStr = iso.string(from: Date(timeIntervalSince1970: TimeInterval(row.ts)))
            buf += "\(row.ts),\(isoStr),"
            buf += row.cols.joined(separator: ",")
            buf += "\n"
            if buf.utf8.count >= 64 * 1024 {
                try handle.write(contentsOf: Data(buf.utf8))
                buf.removeAll(keepingCapacity: true)
            }
        }
        if !buf.isEmpty { try handle.write(contentsOf: Data(buf.utf8)) }
        return url
    }

    /// Format an Int-valued GRDB column (blank for NULL) without the "Optional(...)" wrapper text.
    private static func intStr(_ v: Int?) -> String { v.map(String.init) ?? "" }

    /// Format a Double-valued GRDB column (blank for NULL). Plain decimal, `String(Double)` is
    /// round-trippable and locale-independent, which the comma-delimited CSV needs.
    private static func dblStr(_ v: Double?) -> String { v.map { String($0) } ?? "" }

    /// RFC-4180 CSV field: wrap in double quotes and double any embedded quote ONLY when the value
    /// contains a comma, quote, or newline. Used for the free-form event columns.
    private static func csvField(_ s: String) -> String {
        guard s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: - Test helpers

    public func storageStats_rowCountsForTest() async throws
        -> (hr: Int, rr: Int, events: Int, battery: Int,
            spo2: Int, skinTemp: Int, resp: Int, gravity: Int) {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let dao = roomDb.whoopDao()
            let hr = Int(truncating: try await dao.countHr())
            let rr = Int(truncating: try await dao.countRr())
            let events = Int(truncating: try await dao.countEvents())
            let battery = Int(truncating: try await dao.countBattery())
            let spo2 = Int(truncating: try await dao.countSpo2())
            let skinTemp = Int(truncating: try await dao.countSkinTemp())
            let resp = Int(truncating: try await dao.countResp())
            let gravity = Int(truncating: try await dao.countGravity())
            return (hr, rr, events, battery, spo2, skinTemp, resp, gravity)
            #endif
        }
    }

    public func stepCountForTest() async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return Int(truncating: try await roomDb.whoopDao().countSteps())
            #endif
        }
    }

    /// The strap's OWN banked band sleep_state samples (#175) in `[from, to]` for one device, ascending by
    /// ts. Each `(ts, state)` is the raw @81 high-nibble code (0 wake/1 still/2 asleep/3 up) carried
    /// verbatim off the offload stream. Empty when the strap never reported it (a WHOOP 4.0, or a not-yet-
    /// offloaded window). Feeds the Deep Timeline band-state track and the per-session grid the H7 guard reads.
    public func sleepStateSamples(deviceId: String, from: Int, to: Int, limit: Int = 200_000) async throws
        -> [SleepStateSample] {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            let rows = try await roomDb.whoopDao().sleepStateSamples(
                deviceId: deviceId, from: Int64(from), to: Int64(to), limit: Int32(limit))
            return rows.map { SleepStateSample(ts: Int($0.ts), state: Int($0.state)) }
            #endif
        }
    }

    public func sleepStateCountForTest() async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return Int(truncating: try await roomDb.whoopDao().countSleepState())
            #endif
        }
    }

    public func ppgHrCountForTest() async throws -> Int {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            return Int(truncating: try await roomDb.whoopDao().countPpgHr())
            #endif
        }
    }

    public func deviceRowForTest(id: String) async throws -> (mac: String?, name: String?)? {
        switch backend {
        case .room(let roomDb):
            #if targetEnvironment(simulator) && arch(x86_64)
            _ = roomDb; throw WhoopStore.RoomBackendUnavailableError()
            #else
            guard let row = try await roomDb.whoopDao().device(id: id) else { return nil }
            return (row.mac, row.name)
            #endif
        }
    }
}
