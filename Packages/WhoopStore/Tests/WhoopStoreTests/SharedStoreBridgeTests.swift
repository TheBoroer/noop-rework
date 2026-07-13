import XCTest
@testable import WhoopStore

final class SharedStoreBridgeTests: XCTestCase {
    func testKotlinFrameworkLinks() {
        XCTAssertEqual(SharedStoreBridge.kotlinLinkProbe(), "shared")
    }
}
