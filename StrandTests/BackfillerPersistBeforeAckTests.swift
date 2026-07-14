import XCTest
@testable import Strand
import WhoopProtocol
import WhoopStore

/// Phase 2c-2 Task 5 - the highest-stakes regression test in the phase. Pins the Hard Invariant
/// (persist-before-ack) directly against the REAL `Backfiller.finishChunk` ordering:
///
///   decode known -> await insert (decoded durable)
///   -> rejectedSink hold-ack on failure (#57; not exercised here - rejectedSink is nil and the v25
///      fixture frames all decode cleanly, so `rejected` is always empty)
///   -> await enqueueRawBatch (raw durable, ONLY if enableRawCapture) - hold ack on failure
///   -> await setCursor("strap_trim") - hold ack on failure
///   -> ackTrim (.withResponse)
///
/// A regression here silently and permanently destroys data on the strap: acking trims the strap's
/// own flash copy, so an ack that outraces (or survives) a failed durability step means that data now
/// exists nowhere. Every failure-path assertion below therefore checks the ABSENCE of an ack, not a
/// returned value - `ackRecorder.acks.isEmpty` staying true is the load-bearing assertion.
final class BackfillerPersistBeforeAckTests: XCTestCase {

    // MARK: - Mock store: records ORDER, can be told to fail per-step, and can be told to genuinely
    // SUSPEND inside enqueueRawBatch so a test can prove ackTrim cannot fire while a commit is still
    // in flight (not just that the finished log ended up in the right order).

    private final class OrderingStore: BackfillStoreWriting {
        enum Step: Equatable { case insert, enqueueRawBatch, setCursor }
        struct Boom: Error {}

        private(set) var log: [Step] = []
        var insertShouldThrow = false
        var enqueueShouldThrow = false
        var setCursorShouldThrow = false

        /// When true, `enqueueRawBatch` records its entry (fulfilling `enteredEnqueueExpectation`) and
        /// then genuinely suspends until the test calls `releaseEnqueue()`.
        var holdEnqueue = false
        let enteredEnqueueExpectation = XCTestExpectation(description: "enqueueRawBatch entered")
        private var releaseEnqueueCont: CheckedContinuation<Void, Never>?

        @discardableResult
        func insert(_ streams: Streams, deviceId: String) async throws
            -> (hr: Int, rr: Int, events: Int, battery: Int,
                spo2: Int, skinTemp: Int, resp: Int, gravity: Int) {
            log.append(.insert)
            if insertShouldThrow { throw Boom() }
            return (streams.hr.count, streams.rr.count, 0, 0,
                    streams.spo2.count, streams.skinTemp.count, streams.resp.count, streams.gravity.count)
        }

        func enqueueRawBatch(_ meta: RawBatchMeta, frames: [[UInt8]]) async throws {
            log.append(.enqueueRawBatch)
            if holdEnqueue {
                enteredEnqueueExpectation.fulfill()
                await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                    releaseEnqueueCont = cont
                }
            }
            if enqueueShouldThrow { throw Boom() }
        }

        func setCursor(_ name: String, _ value: Int) async throws {
            log.append(.setCursor)
            if setCursorShouldThrow { throw Boom() }
        }

        func cursor(_ name: String) async throws -> Int? { nil }

        func releaseEnqueue() {
            releaseEnqueueCont?.resume()
            releaseEnqueueCont = nil
        }
    }

    /// Stand-in for the strap link layer: records every `ackTrim` call plus a snapshot of the store's
    /// log length AT THE MOMENT the ack fires, so "ack only after all durability steps completed" is
    /// checkable by more than sequencing luck.
    private final class AckRecorder {
        private(set) var acks: [(trim: UInt32, endData: [UInt8])] = []
        private(set) var logLengthAtAck: [Int] = []
        func ack(_ trim: UInt32, _ endData: [UInt8], logLengthNow: Int) {
            acks.append((trim, endData))
            logLengthAtAck.append(logLengthNow)
        }
    }

    private func hexBytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }

    /// Three REAL WHOOP 4.0 v25 records (84 B, 1 Hz) that decode cleanly to gravity samples - reused
    /// from `RawHistoryArchiveReplayTests`/`BackfillerSessionTallyTests` so `insert` genuinely sees
    /// non-empty decoded streams and `enqueueRawBatch` genuinely sees non-empty frames (an empty chunk
    /// would skip both calls entirely, which is not what these tests need to exercise).
    private var v25RecordFrames: [[UInt8]] {
        [
            "aa50000c2f190013390000140d2b6a4075010068a2010032fdbcfd98fdd3fdccfd47ffb00366064f073e06c103d3016cffa2fc87fa2ffae5fdbe03140675060c0510012dff1bfec0018f3c500500010068dc8f44",
            "aa50000c2f190014390000150d2b6a487001003ab301008dfd6afdaffda9fdaffd68fddbfb0dfc09fd77fe89fe62febffec9fe91ff0bff81ff5fff3e00d600790078ff3dff4bff801d553c5005010000d7c016b3",
            "aa50000c2f190015390000160d2b6a586b01006d8f0100a3ff94ffc4ffbcffbeff22004a009400cb0048005d006b004400d700130115013301f20088001d0031ffd9fe5eff75ff0048933c50050001008bdf2c2c",
        ].map(hexBytes)
    }

    /// A real WHOOP4 HISTORY_END frame (type 49, cmd 2) carrying the given trim. Payload layout is
    /// unix(4) + subsec(2) + unk0(4) + trim(4) - matches `BackfillerSessionTallyTests.historyEndFrame`.
    private func historyEndFrame(trim: UInt32, unix: UInt32 = 1_700_000_000) -> [UInt8] {
        func le32(_ v: UInt32) -> [UInt8] {
            [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
        }
        let payload = le32(unix) + [0, 0] + le32(0) + le32(trim)
        return frameFromPayload(payload, type: 49, seq: 0, cmd: 2)
    }

    // MARK: - (a) raw capture ON: ack only fires once insert -> enqueueRawBatch -> setCursor ALL
    // completed (log-order form).

    @MainActor func testHappyPathAcksOnlyAfterAllThreeDurabilityStepsComplete() async {
        let store = OrderingStore()
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: true)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }
        await backfiller.ingest(historyEndFrame(trim: 42))

        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch, .setCursor],
                        "the three durability steps must run in Hard Invariant order")
        XCTAssertEqual(ackRecorder.acks.count, 1)
        XCTAssertEqual(ackRecorder.acks.first?.trim, 42)
        XCTAssertEqual(ackRecorder.logLengthAtAck, [3],
                        "ackTrim must fire only once insert+enqueueRawBatch+setCursor have ALL completed")
        XCTAssertFalse(backfiller.persistStalled)
    }

    // MARK: - (a, race form) raw capture ON: ackTrim genuinely cannot fire while the raw-batch commit
    // is still SUSPENDED (not merely "happens to run first" per the log). This is the strongest form
    // of "no trim ack before the enqueue commit callback returns success."

    @MainActor func testAckDoesNotFireWhileEnqueueCommitIsStillInFlight() async {
        let store = OrderingStore()
        store.holdEnqueue = true
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: true)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }

        let endTask = Task { @MainActor in await backfiller.ingest(historyEndFrame(trim: 47)) }

        let waited = await XCTWaiter.fulfillment(of: [store.enteredEnqueueExpectation], timeout: 2.0)
        XCTAssertEqual(waited, .completed, "enqueueRawBatch must have been entered within the timeout")
        XCTAssertTrue(ackRecorder.acks.isEmpty,
                      "ackTrim must not fire while the raw-batch commit is still suspended in flight")
        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch],
                       "setCursor must not run until the suspended enqueue commit resolves")

        store.releaseEnqueue()
        await endTask.value

        XCTAssertEqual(ackRecorder.acks.count, 1, "the held ack must fire once the commit resolves")
        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch, .setCursor])
    }

    // MARK: - (b) raw capture ON: enqueue failure -> no ack, persistStalled set, and a LATER empty END
    // still does not ack (#57 twin).

    @MainActor func testEnqueueRawBatchFailureHoldsAckAndStallsSession() async {
        let store = OrderingStore()
        store.enqueueShouldThrow = true
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: true)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }
        await backfiller.ingest(historyEndFrame(trim: 42))

        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch],
                        "setCursor must never run when the raw enqueue commit fails")
        XCTAssertTrue(ackRecorder.acks.isEmpty, "no ack may follow a failed raw-batch commit")
        XCTAssertTrue(backfiller.persistStalled, "#57: the session must be marked stalled")

        // #57 twin: a LATER empty END (no frames accumulated since the failed chunk) must ALSO not
        // ack - acking it would trim the strap past the un-stored records-carrying chunk. An empty END
        // skips the insert entirely and never throws, so this is the case a naive "only guard around
        // the throwing call" fix would miss.
        await backfiller.ingest(historyEndFrame(trim: 43))
        XCTAssertTrue(ackRecorder.acks.isEmpty, "a stalled session must not ack a later empty END either")
        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch],
                        "the stalled-session empty END must not even attempt another durability step")
    }

    // MARK: - (c) raw capture ON: cursor-write failure -> no ack (decoded AND raw already durable).

    @MainActor func testCursorWriteFailureHoldsAck() async {
        let store = OrderingStore()
        store.setCursorShouldThrow = true
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: true)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }
        await backfiller.ingest(historyEndFrame(trim: 44))

        XCTAssertEqual(store.log, [.insert, .enqueueRawBatch, .setCursor],
                        "the cursor write must still be attempted after a successful raw enqueue")
        XCTAssertTrue(ackRecorder.acks.isEmpty, "no ack may follow a failed cursor write")
        XCTAssertTrue(backfiller.persistStalled)
    }

    // MARK: - (d) raw capture OFF: decoded-insert failure still holds the ack (the raw step is simply
    // never reached, but that must not be mistaken for "nothing to guard").

    @MainActor func testDecodedInsertFailureHoldsAckWithRawCaptureOff() async {
        let store = OrderingStore()
        store.insertShouldThrow = true
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: false)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }
        await backfiller.ingest(historyEndFrame(trim: 45))

        XCTAssertEqual(store.log, [.insert],
                        "raw capture is off AND insert failed - enqueueRawBatch/setCursor must never run")
        XCTAssertTrue(ackRecorder.acks.isEmpty,
                      "no ack may follow a failed decoded insert, even with raw capture off")
        XCTAssertTrue(backfiller.persistStalled)
    }

    // MARK: - Baseline: raw capture OFF, no failures. Confirms the OFF toggle SKIPS enqueueRawBatch
    // (not fails it) and the ack still fires - so the ON-path failure tests above are pinning a real
    // guard, not one that would also block the OFF happy path.

    @MainActor func testHappyPathWithRawCaptureOffSkipsEnqueueButStillAcks() async {
        let store = OrderingStore()
        let ackRecorder = AckRecorder()
        let backfiller = Backfiller(
            store: store, deviceId: "test",
            ackTrim: { trim, endData in ackRecorder.ack(trim, endData, logLengthNow: store.log.count) },
            enableRawCapture: false)
        backfiller.begin(family: .whoop4)
        for f in v25RecordFrames { await backfiller.ingest(f) }
        await backfiller.ingest(historyEndFrame(trim: 46))

        XCTAssertEqual(store.log, [.insert, .setCursor],
                        "the research toggle being OFF must skip enqueueRawBatch entirely, not fail it")
        XCTAssertEqual(ackRecorder.acks.count, 1)
        XCTAssertFalse(backfiller.persistStalled)
    }
}
