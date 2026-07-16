// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WhoopStore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "WhoopStore", targets: ["WhoopStore"])],
    dependencies: [
        .package(path: "../WhoopProtocol"),
        .package(path: "../OuraProtocol"),
    ],
    targets: [
        .target(
            name: "WhoopStore",
            dependencies: [
                "WhoopProtocol",
                "OuraProtocol",
                .product(name: "Shared", package: "WhoopProtocol"),
            ]
        ),
        .testTarget(
            name: "WhoopStoreTests",
            dependencies: ["WhoopStore", "WhoopProtocol", "OuraProtocol", .product(name: "Shared", package: "WhoopProtocol")],
            // Frozen legacy-GRDB schema (#65 T1): lets ETL/backup tests build a real legacy
            // store after the GRDB dependency is gone. See Fixtures/legacy-grdb-full-schema.sql.
            resources: [.copy("Fixtures")]
        ),
    ]
)
