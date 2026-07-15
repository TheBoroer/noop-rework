import XCTest
import Shared
@testable import Strand

/// T15c-3b (#78 hole-4): pins the shim-local half of the bond-loop salvage probe, ported from
/// `BLEManager.salvageProbeIfBondLoopPaused` (BLEManager.swift:959-968). The Kotlin-side pure gate
/// (`shouldSalvageProbe`) is pinned by `BleSessionPolicyTest` in the `shared` module; this suite
/// pins the piece that lives in the shim: the pause latch state and the RE-STAMP-before-connect
/// contract. `shouldFireSalvageProbe` never touches the Kotlin client or CoreBluetooth (see its
/// doc comment in WhoopBleShim+SalvageProbe.swift), so it is exercised directly with no seam.
@MainActor
final class WhoopBleShimSalvageProbeTests: XCTestCase {

    private func makeShim() -> WhoopBleShim {
        WhoopBleShim(
            state: LiveState(),
            client: WhoopBleClient(
                parentScope: nil,
                deepDataEnabled: false,
                broadcastHrEnabled: false,
                nowUnixSeconds: { KotlinLong(value: Int64(Date().timeIntervalSince1970)) },
                log: { _ in }
            )
        )
    }

    func testFiresOnlyAfterTheFloorAndRestampsBeforeReportingFire() {
        let shim = makeShim()
        let tripped = Date(timeIntervalSince1970: 1_700_000_000)
        shim.tripBondLoopPause(now: tripped)

        // Below the floor: does not fire, and does NOT re-stamp (nothing to re-stamp yet).
        let justBelowFloor = tripped.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS) - 1)
        XCTAssertFalse(shim.shouldFireSalvageProbe(now: justBelowFloor))
        XCTAssertEqual(shim.bondLoopPausedAt, tripped, "a blocked check must not move the timestamp")

        // At/above the floor: fires, and re-stamps to `now` as a side effect of firing.
        let atFloor = tripped.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS))
        XCTAssertTrue(shim.shouldFireSalvageProbe(now: atFloor))
        XCTAssertEqual(shim.bondLoopPausedAt, atFloor, "a fresh fire must re-stamp to `now`")
    }

    func testRestampBeforeConnectBlocksAnImmediateSecondForeground() {
        let shim = makeShim()
        let tripped = Date(timeIntervalSince1970: 1_700_000_000)
        shim.tripBondLoopPause(now: tripped)

        let firstForeground = tripped.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS) + 60)
        XCTAssertTrue(shim.shouldFireSalvageProbe(now: firstForeground))

        // Back-to-back foreground a second later: the re-stamp from the first call floors this one
        // out again, so it must NOT chain a second probe even though the latch is still set.
        let secondForegroundMomentsLater = firstForeground.addingTimeInterval(1)
        XCTAssertFalse(shim.shouldFireSalvageProbe(now: secondForegroundMomentsLater))

        // Only after another full floor beyond the RE-STAMP does it fire again.
        let thirdForeground = firstForeground.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS))
        XCTAssertTrue(shim.shouldFireSalvageProbe(now: thirdForeground))
    }

    func testNeverFiresWhileNotPaused() {
        let shim = makeShim()
        XCTAssertNil(shim.bondLoopPausedAt)
        XCTAssertFalse(shim.shouldFireSalvageProbe(now: Date(timeIntervalSince1970: 2_000_000_000)))
    }

    func testClearBondLoopPauseDropsTheLatch() {
        let shim = makeShim()
        let tripped = Date(timeIntervalSince1970: 1_700_000_000)
        shim.tripBondLoopPause(now: tripped)
        shim.clearBondLoopPause()
        XCTAssertNil(shim.bondLoopPausedAt)
        let farFuture = tripped.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS) * 10)
        XCTAssertFalse(shim.shouldFireSalvageProbe(now: farFuture))
    }

    func testIntentionalDisconnectBlocksTheProbe() {
        let shim = makeShim()
        let tripped = Date(timeIntervalSince1970: 1_700_000_000)
        shim.tripBondLoopPause(now: tripped)
        shim.intentionalDisconnect = true
        let farFuture = tripped.addingTimeInterval(Double(Shared.BOND_LOOP_SALVAGE_FLOOR_SECONDS) * 10)
        XCTAssertFalse(shim.shouldFireSalvageProbe(now: farFuture))
    }
}
