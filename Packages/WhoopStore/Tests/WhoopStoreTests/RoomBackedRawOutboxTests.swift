import XCTest
import WhoopProtocol
@testable import WhoopStore

/// Phase 2c-2 Task 4 — the Room-backed outbox/cursor suite. (GRDB-removal Task 4: the old GRDB
/// `RawOutboxTests` twin is deleted — `inMemory()` itself is Room-backed now — and its two
/// codec-stress cases live at the bottom of this file.) Uses
/// `WhoopStore.roomBackedForTest()` (a fresh install, no ETL) to exercise the SAME public
/// `WhoopStore` outbox/cursor methods through the `.room` branch added in this task, proving the
/// repointed Collector/Backfiller/diagnostics call sites actually reach Room's `outboxBatch` /
/// `outboxCursor` tables end to end, not just that `OutboxBridge` itself works (already covered by
/// `OutboxBridgeTests`, Task 3).
final class RoomBackedRawOutboxTests: XCTestCase {
    private let frames: [[UInt8]] = [
        [0xAA, 0x18, 0x00, 0xFF, 0x28, 0x02, 0x0F, 0x01, 0x02, 0x03],
        [0xAA, 0x0C, 0x00, 0xFC, 0x24, 0x24, 0x03, 0x0A],
        [],                                   // empty frame must survive the round-trip
    ]
    private func meta(_ id: String, capturedAt: Int = 5000) -> RawBatchMeta {
        RawBatchMeta(batchId: id, deviceId: "dev1",
                     clockRef: ClockRef(device: 31538447, wall: 1736365593),
                     capturedAt: capturedAt, startTs: 1736365593, endTs: 1736365600,
                     frameCount: frames.count, byteSize: frames.reduce(0) { $0 + $1.count })
    }

    func testEnqueueThenRawFramesRoundTripsUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        try await store.enqueueRawBatch(meta("b1"), frames: frames)
        let got = try await store.rawFrames(batchId: "b1")
        XCTAssertEqual(got, frames)
    }

    func testRawFramesUnknownBatchIsEmptyUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        let got = try await store.rawFrames(batchId: "nope")
        XCTAssertEqual(got, [])
    }

    func testPendingExcludesSyncedAndRespectsLimitAndOrderUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        try await store.enqueueRawBatch(meta("old", capturedAt: 100), frames: frames)
        try await store.enqueueRawBatch(meta("mid", capturedAt: 200), frames: frames)
        try await store.enqueueRawBatch(meta("new", capturedAt: 300), frames: frames)
        try await store.markRawBatchSynced(batchId: "mid", at: 999)

        let pending = try await store.pendingRawBatches(limit: 10)
        XCTAssertEqual(pending.map { $0.batchId }, ["old", "new"])   // mid synced; oldest first

        let limited = try await store.pendingRawBatches(limit: 1)
        XCTAssertEqual(limited.map { $0.batchId }, ["old"])
    }

    func testMetaRoundTripsThroughPendingUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        let m = meta("b1")
        try await store.enqueueRawBatch(m, frames: frames)
        let pending = try await store.pendingRawBatches(limit: 10)
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending[0], m)
    }

    /// Hard Invariant surface: `enqueueRawBatch` returning normally (no throw) is the persist-before-
    /// ack durability signal Backfiller/Collector gate their strap ack on. This pins that a SECOND
    /// enqueue with the same `batchId` (the Kotlin `OutboxStore.enqueue` idempotent-retry path) also
    /// returns normally rather than throwing, matching the legacy GRDB `ON CONFLICT DO NOTHING`
    /// behavior `RawOutboxTests` doesn't separately pin but `OutboxBridgeTests
    /// .testIdempotentReEnqueueReturnsTrue` does at the bridge layer.
    func testIdempotentReEnqueueDoesNotThrowUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        let m = meta("dup")
        try await store.enqueueRawBatch(m, frames: frames)
        try await store.enqueueRawBatch(m, frames: frames)   // must not throw
        let pending = try await store.pendingRawBatches(limit: 10)
        XCTAssertEqual(pending.count, 1)
    }

    func testPruneRawDeletesSyncedBatchesPastKeepWindowUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        try await store.enqueueRawBatch(meta("old", capturedAt: 100), frames: frames)
        try await store.markRawBatchSynced(batchId: "old", at: 100)

        let deleted = try await store.pruneRaw(now: 10_000, keepWindowSeconds: 100,
                                                maxUnsyncedBytes: 1_000_000)
        XCTAssertEqual(deleted, 1)
        let got = try await store.rawFrames(batchId: "old")
        XCTAssertEqual(got, [])
    }

    func testCursorRoundTripAndPrefixingUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        let before = try await store.cursor("strap_trim")
        XCTAssertNil(before)
        try await store.setCursor("strap_trim", 42)
        let got = try await store.cursor("strap_trim")
        XCTAssertEqual(got, 42)

        // Prefixing convention (Cursors.swift) is applied caller-side, above setCursor/cursor, and
        // must keep routing correctly once those two are backend-aware.
        try await store.setHighwater("hr", 100)
        let hw1 = try await store.highwater("hr")
        XCTAssertEqual(hw1, 100)
        // "read:" is a DISTINCT prefix from "highwater:" for the same stream.
        let readBefore = try await store.readHighwater("hr")
        XCTAssertNil(readBefore)
        try await store.setReadHighwater("hr", 55)
        let read1 = try await store.readHighwater("hr")
        XCTAssertEqual(read1, 55)
        let hw2 = try await store.highwater("hr")
        XCTAssertEqual(hw2, 100)   // unaffected by the read-cursor write
    }

    func testStorageStatsReadsPendingOutboxUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        try await store.enqueueRawBatch(meta("a", capturedAt: 1), frames: frames)
        try await store.enqueueRawBatch(meta("b", capturedAt: 2), frames: frames)
        try await store.markRawBatchSynced(batchId: "b", at: 999)

        let stats = try await store.storageStats()
        // Task 4 deliberate scoping: the `.room` case reports the PENDING footprint (matching the
        // plan's literal "pending-batch counts/sizes" wording), so the synced batch "b" is excluded
        // even though its row still physically exists until a `pruneRaw` sweep removes it.
        XCTAssertEqual(stats.rawBatches, 1)
        XCTAssertEqual(stats.rawBytes, meta("a").byteSize)
    }

    // MARK: - Codec stress (ported from the deleted GRDB `RawOutboxTests`, GRDB-removal Task 4)

    func testRoundTripLargeBatchUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        // 200 frames x 24 bytes → packed >> (byteSize + 256) hint; exercises the truncation path.
        let manyFrames = (0..<200).map { i in [UInt8](repeating: UInt8(i & 0xFF), count: 24) }
        let byteSize = manyFrames.reduce(0) { $0 + $1.count }
        let m = RawBatchMeta(batchId: "big", deviceId: "dev1",
                             clockRef: ClockRef(device: 0, wall: 0),
                             capturedAt: 1, startTs: 0, endTs: 0,
                             frameCount: manyFrames.count, byteSize: byteSize)
        try await store.enqueueRawBatch(m, frames: manyFrames)
        let gotLarge = try await store.rawFrames(batchId: "big")
        XCTAssertEqual(gotLarge, manyFrames)
    }

    func testRoundTripHighlyCompressibleBatchUnderRoom() async throws {
        let store = try await WhoopStore.roomBackedForTest()
        // All-zero frames compress to a tiny blob but decompress LARGE — the worst case for
        // any fixed-size decode buffer heuristic.
        let zeros = (0..<300).map { _ in [UInt8](repeating: 0, count: 64) }
        let byteSize = zeros.reduce(0) { $0 + $1.count }
        let m = RawBatchMeta(batchId: "z", deviceId: "dev1",
                             clockRef: ClockRef(device: 0, wall: 0),
                             capturedAt: 1, startTs: 0, endTs: 0,
                             frameCount: zeros.count, byteSize: byteSize)
        try await store.enqueueRawBatch(m, frames: zeros)
        let gotZeros = try await store.rawFrames(batchId: "z")
        XCTAssertEqual(gotZeros, zeros)
    }
}
