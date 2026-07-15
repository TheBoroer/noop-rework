import XCTest
@testable import Strand

/// Pins the BLE scan-family fallback rotation (PR#195): a service-filtered scan that finds nothing
/// rotates to the OTHER WHOOP family in case the persisted preference went stale after an update/restore.
final class WhoopModelFallbackTests: XCTestCase {

    func testFallbackRotatesBetweenFamilies() {
        XCTAssertEqual(WhoopModel.whoop4.fallbackScanModel, .whoop5mg)
        XCTAssertEqual(WhoopModel.whoop5mg.fallbackScanModel, .whoop4)
    }

    // Rotating twice returns to the original: the rotation is a clean two-state cycle.
    func testFallbackIsInvolution() {
        XCTAssertEqual(WhoopModel.whoop4.fallbackScanModel.fallbackScanModel, .whoop4)
        XCTAssertEqual(WhoopModel.whoop5mg.fallbackScanModel.fallbackScanModel, .whoop5mg)
    }
}
