import XCTest
@testable import WhoopProtocol

final class SharedBridgeTests: XCTestCase {
    func testKotlinFrameworkLinks() {
        XCTAssertEqual(SharedBridge.kotlinLinkProbe(), "shared")
    }
}
