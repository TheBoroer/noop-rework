// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WhoopProtocol",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "WhoopProtocol", targets: ["WhoopProtocol"]),
        .library(name: "Shared", targets: ["Shared"]),
        .executable(name: "whoop-decode", targets: ["whoop-decode"]),
    ],
    targets: [
        .target(
            name: "WhoopProtocol",
            dependencies: ["Shared"],
            resources: [.process("Resources/whoop_protocol.json")]
        ),
        .binaryTarget(
            name: "Shared",
            path: "../../shared/build/XCFrameworks/release/Shared.xcframework"
        ),
        .executableTarget(
            name: "whoop-decode",
            dependencies: ["WhoopProtocol"]
        ),
        .testTarget(
            name: "WhoopProtocolTests",
            dependencies: ["WhoopProtocol"],
            resources: [.process("Resources")]
        ),
    ]
)
