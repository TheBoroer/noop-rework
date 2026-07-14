import XCTest
import WhoopProtocol
import Shared
@testable import WhoopStore

/// Phase 2c-2 Task 3 — the Swift bridge over the Kotlin `OutboxStore` (Tasks 1-2), the Room
/// replacement for the GRDB outbox in `RawOutboxTests.swift`. Coverage deliberately mirrors that
/// sibling suite's shape (round-trip, idempotent re-enqueue, unknown-batch, synced exclusion) so the
/// two backends are pinned to the same observable behavior, plus a cursor round-trip and a minimal
/// prune check since Task 4 wires ALL of these onto Collector/Backfiller/diagnostics.
///
/// The frame-blob codec (`packFrames`/`unpackFrames`, `zlibCompressWithLength`/
/// `zlibDecompressWithLength`) stays Swift-side per the plan: these tests pack+compress before
/// `outboxEnqueue` and decompress+unpack after `outboxFrames`, exactly like a real caller would, so a
/// byte-equal round trip here also pins that the bridge hands the blob through unmodified.
final class OutboxBridgeTests: XCTestCase {
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

    /// A fresh Room `WhoopDatabase` in its own temp dir, wrapped in a bridge. Mirrors
    /// `RoomConcurrencyTests.freshRoomDir()` / `whoopDatabase(path:)` exactly: this bridge takes a
    /// `WhoopDatabase` handle directly (it does not go through `WhoopStore`'s GRDB/Room backend
    /// switch), so there is no fresh-install detection to wait on.
    private func freshBridge() throws -> OutboxBridge {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-outbox-bridge-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let db = whoopDatabase(path: dir.appendingPathComponent("whoop.sqlite").path)
        return OutboxBridge(db: db)
    }

    func testEnqueueThenPendingThenFramesBlobRoundTrips() async throws {
        let bridge = try freshBridge()
        let packed = WhoopStore.packFrames(frames)
        let blob = try WhoopStore.zlibCompressWithLength(packed)

        let persisted = try await bridge.outboxEnqueue(meta: meta("b1"), framesBlob: blob)
        XCTAssertTrue(persisted)

        let pending = try await bridge.outboxPending(limit: 10)
        XCTAssertEqual(pending.map { $0.batchId }, ["b1"])
        XCTAssertEqual(pending[0], meta("b1"))

        let gotBlob = try await bridge.outboxFrames(batchId: "b1")
        XCTAssertNotNil(gotBlob)
        let raw = try WhoopStore.zlibDecompressWithLength(gotBlob!)
        XCTAssertEqual(WhoopStore.unpackFrames(raw), frames)
    }

    func testIdempotentReEnqueueReturnsTrue() async throws {
        let bridge = try freshBridge()
        let packed = WhoopStore.packFrames(frames)
        let blob = try WhoopStore.zlibCompressWithLength(packed)

        let first = try await bridge.outboxEnqueue(meta: meta("dup"), framesBlob: blob)
        XCTAssertTrue(first)
        // A retried capture with the SAME batchId is a no-op that still reports true (Hard Invariant:
        // the row IS durably present, whether or not THIS call performed the insert).
        let second = try await bridge.outboxEnqueue(meta: meta("dup"), framesBlob: blob)
        XCTAssertTrue(second)

        let pending = try await bridge.outboxPending(limit: 10)
        XCTAssertEqual(pending.count, 1)
    }

    func testFramesForUnknownBatchIsNil() async throws {
        let bridge = try freshBridge()
        let got = try await bridge.outboxFrames(batchId: "nope")
        XCTAssertNil(got)
    }

    func testMarkSyncedExcludesFromPending() async throws {
        let bridge = try freshBridge()
        let packed = WhoopStore.packFrames(frames)
        let blob = try WhoopStore.zlibCompressWithLength(packed)
        _ = try await bridge.outboxEnqueue(meta: meta("s1"), framesBlob: blob)
        try await bridge.outboxMarkSynced(batchId: "s1", at: 999)
        let pending = try await bridge.outboxPending(limit: 10)
        XCTAssertTrue(pending.isEmpty)
    }

    func testPruneDeletesSyncedBatchesPastKeepWindow() async throws {
        let bridge = try freshBridge()
        let packed = WhoopStore.packFrames(frames)
        let blob = try WhoopStore.zlibCompressWithLength(packed)
        _ = try await bridge.outboxEnqueue(meta: meta("old", capturedAt: 100), framesBlob: blob)
        try await bridge.outboxMarkSynced(batchId: "old", at: 100)

        let deleted = try await bridge.outboxPrune(now: 10_000, keepWindowSeconds: 100,
                                                    maxUnsyncedBytes: 1_000_000)
        XCTAssertEqual(deleted, 1)
        let got = try await bridge.outboxFrames(batchId: "old")
        XCTAssertNil(got)
    }

    func testCursorRoundTrip() async throws {
        let bridge = try freshBridge()
        let missing = try await bridge.outboxCursor("strap_trim")
        XCTAssertNil(missing)

        try await bridge.outboxSetCursor("strap_trim", 42)
        let got = try await bridge.outboxCursor("strap_trim")
        XCTAssertEqual(got, 42)

        // Cursor names must pass through untouched: the "highwater:"/"read:" prefixing convention
        // (Cursors.swift) is the CALLER's job, not this bridge's.
        try await bridge.outboxSetCursor("highwater:hr", 100)
        let highwater = try await bridge.outboxCursor("highwater:hr")
        XCTAssertEqual(highwater, 100)
        // A distinct, never-written cursor stays untouched by the writes above.
        let readCursor = try await bridge.outboxCursor("read:hr")
        XCTAssertNil(readCursor)
    }
}
