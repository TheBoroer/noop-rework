import XCTest
import WhoopProtocol
@testable import WhoopStore

/// Phase 2c-2 Task 6 (drain-once): the one-shot that folds a legacy GRDB `whoop.sqlite` raw outbox
/// (pending `rawBatch` rows + surviving cursors) into the live Room outbox, then archives the GRDB
/// file. The testable core lives here in the WhoopStore package (StrandTests can't run yet — the
/// known project.yml TEST_HOST/PRODUCT_NAME blocker), driving the SAME public store API the app
/// wrapper (`Strand/Data/OutboxDrain.swift`) uses in production.
///
/// Fixtures use tiny synthetic frame bytes (a single repeated byte per frame): the real GRDB
/// pending-batch payloads are opaque blobs, so nothing here decodes or embeds real payload content —
/// it only round-trips synthetic frames through the same `packFrames`+zlib codec the store already
/// uses.
final class OutboxDrainTests: XCTestCase {
    // Synthetic, content-free frame bytes.
    private let framesB1: [[UInt8]] = [[UInt8](repeating: 0x01, count: 4), [UInt8](repeating: 0x02, count: 3)]
    private let framesB2: [[UInt8]] = [[UInt8](repeating: 0x03, count: 5)]

    private func meta(_ id: String, capturedAt: Int, frames: [[UInt8]]) -> RawBatchMeta {
        RawBatchMeta(batchId: id, deviceId: "dev1", clockRef: ClockRef(device: 1, wall: 2),
                     capturedAt: capturedAt, startTs: 10, endTs: 20,
                     frameCount: frames.count, byteSize: frames.reduce(0) { $0 + $1.count })
    }

    private func uniqueTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("noop-drain-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Seed a legacy `.legacyGrdb` store: two pending batches + one of each keep-set cursor plus a
    /// non-keep cursor. The store's pool is released when this returns (checkpoint folds the WAL first,
    /// so a fresh pool opened afterwards on the same file reads the committed rows).
    private func seedLegacy(at legacyURL: URL) async throws {
        let store = try WhoopStore.openLegacyGrdb(path: legacyURL.path)
        try await store.enqueueRawBatch(meta("b1", capturedAt: 100, frames: framesB1), frames: framesB1)
        try await store.enqueueRawBatch(meta("b2", capturedAt: 200, frames: framesB2), frames: framesB2)
        try await store.setCursor("strap_trim", 100)
        try await store.setCursor("highwater:hr", 200)
        try await store.setCursor("read:hr", 300)
        try await store.setCursor("junk", 400)   // NOT in the keep-set — must never be copied.
        try await store.checkpointWAL()
    }

    func testCopyOutboxFoldsPendingBatchesAndKeepSetCursors() async throws {
        let dir = try uniqueTempDir()
        let legacyURL = dir.appendingPathComponent("whoop.sqlite")
        let source = try WhoopStore.openLegacyGrdb(path: legacyURL.path)
        try await source.enqueueRawBatch(meta("b1", capturedAt: 100, frames: framesB1), frames: framesB1)
        try await source.enqueueRawBatch(meta("b2", capturedAt: 200, frames: framesB2), frames: framesB2)
        try await source.setCursor("strap_trim", 100)
        try await source.setCursor("highwater:hr", 200)
        try await source.setCursor("read:hr", 300)
        try await source.setCursor("junk", 400)
        let room = try await WhoopStore.roomBackedForTest()

        let counts = try await OutboxDrain.copyOutbox(from: source, to: room)

        // Every pending batch folded into Room, none synced, ids preserved.
        let pending = try await room.pendingRawBatches(limit: 100)
        XCTAssertEqual(Set(pending.map(\.batchId)), ["b1", "b2"])
        XCTAssertEqual(counts.batches, 2)

        // Frame payloads survive the unpack/re-pack round-trip.
        let f1 = try await room.rawFrames(batchId: "b1")
        let f2 = try await room.rawFrames(batchId: "b2")
        XCTAssertEqual(f1, framesB1)
        XCTAssertEqual(f2, framesB2)

        // Keep-set cursors copied; the non-keep cursor is NOT.
        let strapTrim = try await room.cursor("strap_trim")
        let highwater = try await room.cursor("highwater:hr")
        let read = try await room.cursor("read:hr")
        let junk = try await room.cursor("junk")
        XCTAssertEqual(strapTrim, 100)
        XCTAssertEqual(highwater, 200)
        XCTAssertEqual(read, 300)
        XCTAssertNil(junk)
        XCTAssertEqual(counts.cursors, 3)
    }

    /// The spec's crash-before-rename idempotency case: drain (copy), simulate a crash before the
    /// rename, drain again — the row set is identical and a newer post-cutover cursor value is never
    /// regressed (unset-only guard) — then the rename finally happens.
    func testCopyOutboxIsIdempotentGuardsCursorsThenArchives() async throws {
        let dir = try uniqueTempDir()
        let legacyURL = dir.appendingPathComponent("whoop.sqlite")
        let source = try WhoopStore.openLegacyGrdb(path: legacyURL.path)
        try await source.enqueueRawBatch(meta("b1", capturedAt: 100, frames: framesB1), frames: framesB1)
        try await source.enqueueRawBatch(meta("b2", capturedAt: 200, frames: framesB2), frames: framesB2)
        try await source.setCursor("strap_trim", 100)
        try await source.setCursor("highwater:hr", 200)
        let room = try await WhoopStore.roomBackedForTest()

        // A NEWER post-cutover write the drain must never clobber.
        try await room.setCursor("strap_trim", 999)

        // First drain (crashes before the rename): data copy only.
        _ = try await OutboxDrain.copyOutbox(from: source, to: room)
        // Re-run after the crash: must be idempotent.
        let secondCounts = try await OutboxDrain.copyOutbox(from: source, to: room)

        // Row set identical — batches not duplicated.
        let pending = try await room.pendingRawBatches(limit: 100)
        XCTAssertEqual(pending.count, 2)
        XCTAssertEqual(Set(pending.map(\.batchId)), ["b1", "b2"])

        // Guarded cursor NOT regressed; the unset one still copied.
        let strapTrim = try await room.cursor("strap_trim")
        let highwater = try await room.cursor("highwater:hr")
        XCTAssertEqual(strapTrim, 999)
        XCTAssertEqual(highwater, 200)
        // strap_trim was already set (skipped) on the re-run, so only 0 fresh copies the second pass.
        XCTAssertEqual(secondCounts.cursors, 0)

        // Now the rename (the done-marker) happens.
        try await source.checkpointWAL()
        let fixedNow = 1_700_000_000
        let dest = try OutboxDrain.archiveLegacyFile(at: legacyURL, now: { fixedNow }, fileManager: .default)
        let legacyGone = !FileManager.default.fileExists(atPath: legacyURL.path)
        let archivedPresent = FileManager.default.fileExists(atPath: dest.path)
        XCTAssertTrue(legacyGone)
        XCTAssertTrue(archivedPresent)
        XCTAssertEqual(dest.lastPathComponent, "noop-drained-\(fixedNow).sqlite")
    }

    func testRunNoOpsWhenLegacyFileAbsent() async throws {
        let dir = try uniqueTempDir()
        let absent = dir.appendingPathComponent("whoop.sqlite")   // deliberately never created
        let room = try await WhoopStore.roomBackedForTest()

        let result = try await OutboxDrain.run(
            legacyURL: absent, room: room,
            openLegacy: { _ in
                XCTFail("openLegacy must not be called when the legacy file is absent")
                throw CocoaError(.fileNoSuchFile)
            })

        XCTAssertFalse(result.existed)
        XCTAssertNil(result.archivedURL)
        XCTAssertEqual(result.batchesDrained, 0)
        XCTAssertEqual(result.cursorsCopied, 0)
    }

    func testRunEndToEndDrainsAndArchives() async throws {
        let dir = try uniqueTempDir()
        let legacyURL = dir.appendingPathComponent("whoop.sqlite")
        try await seedLegacy(at: legacyURL)
        let room = try await WhoopStore.roomBackedForTest()
        let fixedNow = 1_700_000_777

        let result = try await OutboxDrain.run(
            legacyURL: legacyURL, room: room,
            openLegacy: { try WhoopStore.openLegacyGrdb(path: $0.path) },
            now: { fixedNow })

        XCTAssertTrue(result.existed)
        XCTAssertEqual(result.batchesDrained, 2)
        XCTAssertEqual(result.cursorsCopied, 3)
        XCTAssertEqual(result.archivedURL?.lastPathComponent, "noop-drained-\(fixedNow).sqlite")

        let pending = try await room.pendingRawBatches(limit: 100)
        XCTAssertEqual(Set(pending.map(\.batchId)), ["b1", "b2"])
        let legacyGone = !FileManager.default.fileExists(atPath: legacyURL.path)
        XCTAssertTrue(legacyGone)
    }
}
