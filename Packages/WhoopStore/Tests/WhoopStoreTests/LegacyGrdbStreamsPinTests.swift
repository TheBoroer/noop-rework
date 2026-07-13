import XCTest
import WhoopProtocol
@testable import WhoopStore

/// Task 4: the PINNED legacy-mode subset. The streams suites (Insert/Read/Biometric/PpgHr/Step/
/// SleepState/LatestSample) now run against the ROOM backend; this file keeps the GRDB fallback
/// branch of every delegated method covered by running the same core assertions against
/// `WhoopStore.inMemory()`, which is always `.legacyGrdb` (a real ETL-failure fallback must stay
/// fully functional). If a refactor ever breaks only the legacy branch, this is the net.
final class LegacyGrdbStreamsPinTests: XCTestCase {

    private func legacyStore() async throws -> WhoopStore {
        let store = try await WhoopStore.inMemory()
        guard case .legacyGrdb = store.storageBackend else {
            XCTFail("inMemory() must be the legacy GRDB backend")
            throw XCTSkip("unreachable")
        }
        return store
    }

    /// Write funnel on GRDB: per-stream inserted counts, then natural-key idempotency on re-insert,
    /// for all 11 stream tables (counted + persist-only).
    func testLegacyInsertCountsAndIdempotency() async throws {
        let store = try await legacyStore()
        try await store.upsertDevice(id: "dev1", mac: "AA:BB", name: "Strap")
        let streams = Streams(
            hr: [HRSample(ts: 1000, bpm: 60), HRSample(ts: 1001, bpm: 61)],
            rr: [RRInterval(ts: 1000, rrMs: 800), RRInterval(ts: 1000, rrMs: 820)],
            spo2: [SpO2Sample(ts: 1000, red: 18000, ir: 17000)],
            skinTemp: [SkinTempSample(ts: 1000, raw: 900)],
            resp: [RespSample(ts: 1000, raw: 3000)],
            gravity: [GravitySample(ts: 1000, x: 0.05, y: 0.10, z: 0.99)],
            steps: [StepSample(ts: 1000, counter: 50, activityClass: 1)],
            sleepState: [SleepStateSample(ts: 1000, state: 2)],
            ppgHr: [PpgHrSample(ts: 1001, bpm: 62, conf: 0.8)],
            events: [WhoopEvent(ts: 1000, kind: "BLE_CONNECTION_DOWN(12)",
                                payload: ["foo": .int(7), "bar": .string("x")])],
            battery: [BatterySample(ts: 1000, soc: 25.5, mv: 3900)])

        let n = try await store.insert(streams, deviceId: "dev1")
        XCTAssertEqual(n.hr, 2)
        XCTAssertEqual(n.rr, 2)
        XCTAssertEqual(n.events, 1)
        XCTAssertEqual(n.battery, 1)
        XCTAssertEqual(n.spo2, 1)
        XCTAssertEqual(n.skinTemp, 1)
        XCTAssertEqual(n.resp, 1)
        XCTAssertEqual(n.gravity, 1)

        let second = try await store.insert(streams, deviceId: "dev1")
        XCTAssertEqual(second.hr, 0)
        XCTAssertEqual(second.rr, 0)
        XCTAssertEqual(second.events, 0)
        XCTAssertEqual(second.battery, 0)
        XCTAssertEqual(second.spo2, 0)
        XCTAssertEqual(second.skinTemp, 0)
        XCTAssertEqual(second.resp, 0)
        XCTAssertEqual(second.gravity, 0)

        // Persist-only streams deduped too.
        let steps = try await store.stepCountForTest()
        XCTAssertEqual(steps, 1)
        let sleepState = try await store.sleepStateCountForTest()
        XCTAssertEqual(sleepState, 1)
        let ppg = try await store.ppgHrCountForTest()
        XCTAssertEqual(ppg, 1)
    }

    /// upsertDevice on GRDB: second call updates mac/name (lastSeen semantics owned by SQL).
    func testLegacyUpsertDeviceUpdatesFields() async throws {
        let store = try await legacyStore()
        try await store.upsertDevice(id: "dev1", mac: "AA", name: "first")
        try await store.upsertDevice(id: "dev1", mac: "BB", name: "second")
        let row = try await store.deviceRowForTest(id: "dev1")
        XCTAssertEqual(row?.mac, "BB")
        XCTAssertEqual(row?.name, "second")
    }

    /// Every delegated read on GRDB: range + order + device scope + limit, PPG coalesce, fingerprint,
    /// frontier, buckets, and storageStats totals.
    func testLegacyReadsRoundTrip() async throws {
        let store = try await legacyStore()
        try await store.upsertDevice(id: "dev1", mac: nil, name: nil)
        try await store.upsertDevice(id: "other", mac: nil, name: nil)
        let s = Streams(
            hr: [HRSample(ts: 100, bpm: 60), HRSample(ts: 200, bpm: 61), HRSample(ts: 300, bpm: 62)],
            rr: [RRInterval(ts: 100, rrMs: 800), RRInterval(ts: 100, rrMs: 820)],
            spo2: [SpO2Sample(ts: 400, red: 1, ir: 2)],
            skinTemp: [SkinTempSample(ts: 400, raw: 930)],
            resp: [RespSample(ts: 400, raw: 3073)],
            gravity: [GravitySample(ts: 400, x: 0.1, y: 0.2, z: 0.3)],
            steps: [StepSample(ts: 400, counter: 60, activityClass: nil)],
            sleepState: [SleepStateSample(ts: 400, state: 2)],
            ppgHr: [PpgHrSample(ts: 301, bpm: 71, conf: 0.9)],
            events: [WhoopEvent(ts: 150, kind: "BLE_CONNECTION_DOWN(12)", payload: ["k": .int(9)])],
            battery: [BatterySample(ts: 120, soc: 88.0, mv: 3900)])
        _ = try await store.insert(s, deviceId: "dev1")
        _ = try await store.insert(Streams(hr: [HRSample(ts: 200, bpm: 99)]), deviceId: "other")

        // hrSamples: measured + PPG gap-fill, windowed, device-scoped.
        let hr = try await store.hrSamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(hr, [HRSample(ts: 100, bpm: 60), HRSample(ts: 200, bpm: 61),
                            HRSample(ts: 300, bpm: 62), HRSample(ts: 301, bpm: 71)])
        let hrWindow = try await store.hrSamples(deviceId: "dev1", from: 150, to: 250, limit: 100)
        XCTAssertEqual(hrWindow, [HRSample(ts: 200, bpm: 61)])

        // hrFingerprint: measured-only count/maxTs, empty window is (0,0).
        let fp = try await store.hrFingerprint(deviceId: "dev1", from: 0, to: 1000)
        XCTAssertEqual(fp.count, 3)
        XCTAssertEqual(fp.maxTs, 300)
        let fpEmpty = try await store.hrFingerprint(deviceId: "dev1", from: 5000, to: 6000)
        XCTAssertEqual(fpEmpty.count, 0)
        XCTAssertEqual(fpEmpty.maxTs, 0)

        // hrBuckets: 200s buckets, mean per bucket, PPG fill included where no measured row.
        let buckets = try await store.hrBuckets(deviceId: "dev1", from: 0, to: 1000, bucketSeconds: 200)
        XCTAssertEqual(buckets.count, 2)
        XCTAssertEqual(buckets[0], HRBucket(ts: 0, bpm: 60))
        XCTAssertEqual(buckets[1].ts, 200)
        XCTAssertEqual(buckets[1].bpm, (61.0 + 62.0 + 71.0) / 3.0, accuracy: 0.001)
        XCTAssertEqual(buckets[1].conf, 0.9, accuracy: 0.0001)

        // Remaining sample reads.
        let rr = try await store.rrIntervals(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(rr, [RRInterval(ts: 100, rrMs: 800), RRInterval(ts: 100, rrMs: 820)])
        let evs = try await store.events(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(evs, [WhoopEvent(ts: 150, kind: "BLE_CONNECTION_DOWN(12)", payload: ["k": .int(9)])])
        let bat = try await store.batterySamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(bat, [BatterySample(ts: 120, soc: 88.0, mv: 3900)])
        let spo2 = try await store.spo2Samples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(spo2, [SpO2Sample(ts: 400, red: 1, ir: 2)])
        let skin = try await store.skinTempSamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(skin, [SkinTempSample(ts: 400, raw: 930)])
        let resp = try await store.respSamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(resp, [RespSample(ts: 400, raw: 3073)])
        let grav = try await store.gravitySamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(grav, [GravitySample(ts: 400, x: 0.1, y: 0.2, z: 0.3)])
        let steps = try await store.stepSamples(deviceId: "dev1", from: 0, to: 1000, limit: 100)
        XCTAssertEqual(steps, [StepSample(ts: 400, counter: 60, activityClass: nil)])
        let states = try await store.sleepStateSamples(deviceId: "dev1", from: 0, to: 1000)
        XCTAssertEqual(states, [SleepStateSample(ts: 400, state: 2)])

        // Frontier includes the PPG row (301 > 300).
        let frontier = try await store.latestHRSampleTs(deviceId: "dev1")
        XCTAssertEqual(frontier, 301)

        // storageStats sums the 8 counted tables across BOTH devices.
        let stats = try await store.storageStats()
        // dev1: 3 hr + 2 rr + 1 event + 1 battery + 1 spo2 + 1 skinTemp + 1 resp + 1 gravity = 11; other: 1 hr.
        XCTAssertEqual(stats.decodedRows, 12)
        XCTAssertEqual(stats.rawBatches, 0)
        XCTAssertEqual(stats.rawBytes, 0)
    }
}
