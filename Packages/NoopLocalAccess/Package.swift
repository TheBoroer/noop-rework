// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NoopLocalAccess",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "NoopLocalAccessCore", targets: ["NoopLocalAccessCore"]),
        .executable(name: "noop-local-access", targets: ["noop-local-access"]),
    ],
    targets: [
        .target(
            name: "NoopLocalAccessCore"
        ),
        .executableTarget(
            name: "noop-local-access",
            dependencies: ["NoopLocalAccessCore"]
        ),
        .testTarget(
            name: "NoopLocalAccessCoreTests",
            dependencies: ["NoopLocalAccessCore"]
        ),
    ]
)
