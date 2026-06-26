import XCTest
@testable import StrandAnalytics

/// The Connection & Sync line formatters + readout parsers (Test Centre). Pure - no clock, no BLE - so
/// fixtures pin the exact line shapes the Swift and Kotlin emitters share. Twin of the Android
/// ConnectionReadoutTest.
final class ConnectionTraceTests: XCTestCase {

    // A strap whose newest record sits before wall-now reads clockOk, with the [oldest, newest] span.
    func testClockDriftLineHealthy() {
        // 2026-06-26 12:00:00 UTC newest, oldest two days earlier, wall just after newest.
        let newest = 1_782_475_200            // 2026-06-26 12:00:00 UTC
        let oldest = newest - 2 * 86_400
        let wall = newest + 600               // wall 10 min ahead of the newest record
        let line = ConnectionTrace.clockDriftLine(oldestUnix: oldest, newestUnix: newest, wallNowUnix: wall)
        XCTAssertTrue(line.hasPrefix("clockDrift newest=2026-06-26 12:00:00 "), line)
        XCTAssertTrue(line.contains("newestVsWall=-600s"), line)
        XCTAssertTrue(line.contains("spanDays=2"), line)
        XCTAssertTrue(line.hasSuffix("clockOk"), line)
        XCTAssertFalse(line.contains("FUTURE"), line)
    }

    // A strap whose newest record is dated AHEAD of wall-now beyond the tolerance is FUTURE-DATED.
    func testClockDriftLineFutureDated() {
        let wall = 1_782_475_200
        let newest = wall + 3 * 86_400        // strap thinks it banked 3 days into the future
        let line = ConnectionTrace.clockDriftLine(oldestUnix: nil, newestUnix: newest, wallNowUnix: wall)
        XCTAssertTrue(line.contains("newestVsWall=+\(3 * 86_400)s"), line)
        XCTAssertTrue(line.contains("FUTURE-DATED"), line)
        XCTAssertFalse(line.contains("oldest="), line)   // half range reply: no lower bound
    }

    // A small skew inside the tolerance window must NOT trip the future flag.
    func testClockDriftLineWithinToleranceIsOk() {
        let wall = 1_782_475_200
        let newest = wall + 60                // 1 min ahead, inside the 120s default tolerance
        let line = ConnectionTrace.clockDriftLine(oldestUnix: nil, newestUnix: newest, wallNowUnix: wall)
        XCTAssertTrue(line.hasSuffix("clockOk"), line)
    }

    func testFirmwareLine() {
        XCTAssertEqual(ConnectionTrace.firmwareLine(version: 25, decodable: true), "firmware layout=v25 decodable")
        XCTAssertEqual(ConnectionTrace.firmwareLine(version: 30, decodable: false),
                       "firmware layout=v30 UNMAPPED (no motion/HR decoded)")
    }

    func testNoCursorLine() {
        XCTAssertEqual(ConnectionTrace.noCursorLine(),
                       "offload trim=0xFFFFFFFF noCursor (strap has no banked history to offload)")
    }
}

final class ConnectionReadoutTests: XCTestCase {

    func testUptimeLabelFromConnectMarker() {
        let tail = ["[connection] connect up gen=1 latencyMs=420 uptimeStart=1000"]
        // 3 min 12 s after the connect.
        XCTAssertEqual(ConnectionReadout.uptimeLabel(taggedTail: tail, nowUnix: 1000 + 192), "3m 12s")
    }

    func testUptimeLabelDownAfterDisconnect() {
        let tail = [
            "[connection] connect up gen=1 latencyMs=420 uptimeStart=1000",
            "[connection] connect down (uptime ends)",
        ]
        XCTAssertEqual(ConnectionReadout.uptimeLabel(taggedTail: tail, nowUnix: 5000), "not connected")
    }

    func testUptimeLabelEmptyTail() {
        XCTAssertEqual(ConnectionReadout.uptimeLabel(taggedTail: [], nowUnix: 5000), "not connected")
    }

    func testReconnectCountTakesHighest() {
        let tail = [
            "[connection] reconnect n=1 reason=connectionTimeout",
            "[connection] reconnect n=2 reason=connectionTimeout",
            "[connection] reconnect n=3 failedConnect reason=peerRemovedPairing",
        ]
        XCTAssertEqual(ConnectionReadout.reconnectCount(taggedTail: tail), 3)
    }

    func testReconnectCountZeroWhenNone() {
        XCTAssertEqual(ConnectionReadout.reconnectCount(taggedTail: ["[connection] connect up gen=1 uptimeStart=1"]), 0)
    }

    func testLastOffloadResult() {
        let tail = [
            "[connection] offload progress trim=100 chunkRows=5 sessionRows=5 sessionMotion=2 nights=1",
            "[connection] offload result=complete rows=42 nights=2",
        ]
        XCTAssertEqual(ConnectionReadout.lastOffloadResult(taggedTail: tail), "complete rows=42 nights=2")
    }

    func testLastOffloadResultStalled() {
        let tail = ["[connection] offload result=stalled (idle timeout, rows=12 so far)"]
        XCTAssertEqual(ConnectionReadout.lastOffloadResult(taggedTail: tail), "stalled (idle timeout, rows=12 so far)")
    }

    func testLastOffloadResultNilWhenNone() {
        XCTAssertNil(ConnectionReadout.lastOffloadResult(taggedTail: ["[connection] connect up gen=1 uptimeStart=1"]))
    }
}
