import XCTest
@testable import WhoopStore

/// Phase 2c-2 Task 5 (folded in from a Task 4 reviewer Minor): direct coverage for the
/// `RawBatchNotPersistedError` guard inside `RawOutbox.swift`'s `.room` branch of `enqueueRawBatch`,
/// previously unexercised by any test. The guard was extracted, byte-identical, into
/// `WhoopStore.requirePersisted(_:batchId:)` specifically so it could be called directly here:
/// `OutboxBridge` has no protocol seam (concrete Kotlin-bridging class, by design — see its doc
/// comment), and the Kotlin `OutboxStore.enqueue` it wraps always verifies-or-throws in practice, so no
/// real path in this repo can drive `outboxEnqueue` to return `false`. This is the chosen "small clean
/// injection seam": the extraction changes nothing about production behavior (same guard, same call
/// site, same thrown error) — it only gives a test a way to call the guard itself with a forced
/// `true`/`false`, rather than settling for constructing `RawBatchNotPersistedError` in isolation.
final class RequirePersistedGuardTests: XCTestCase {
    func testTrueDoesNotThrow() throws {
        try WhoopStore.requirePersisted(true, batchId: "b1")   // must not throw
    }

    func testFalseThrowsRawBatchNotPersistedErrorCarryingTheBatchId() {
        XCTAssertThrowsError(try WhoopStore.requirePersisted(false, batchId: "b1")) { error in
            guard let notPersisted = error as? RawBatchNotPersistedError else {
                return XCTFail("expected RawBatchNotPersistedError, got \(error)")
            }
            XCTAssertEqual(notPersisted.batchId, "b1")
        }
    }

    /// A `false` must never be coerced into a silent success: this pins that the ONLY outcome of a
    /// `false` is a thrown `RawBatchNotPersistedError`, matching the Hard Invariant contract that
    /// `enqueueRawBatch`'s callers (Backfiller/Collector) gate their trim-cursor ack on.
    func testFalseNeverReturnsNormally() {
        var threw = false
        do {
            try WhoopStore.requirePersisted(false, batchId: "b2")
        } catch is RawBatchNotPersistedError {
            threw = true
        } catch {
            XCTFail("expected RawBatchNotPersistedError, got \(error)")
        }
        XCTAssertTrue(threw, "a false persisted-flag must always surface as a thrown error")
    }

    func testErrorDescriptionNamesTheBatchAndTheAckSafetyReason() {
        let error = RawBatchNotPersistedError(batchId: "hist-dev1-42")
        XCTAssertTrue(error.description.contains("hist-dev1-42"), error.description)
        XCTAssertTrue(error.description.contains("ack must not proceed"), error.description)
    }
}
