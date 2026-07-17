// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "StrandImport",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "StrandImport", targets: ["StrandImport"])],
    dependencies: [
        .package(path: "../WhoopProtocol"),
        .package(path: "../WhoopStore"),
        // Supply-chain: pinned EXACT (not `from:`) so a clean resolve can't auto-pull a newer —
        // potentially compromised — upstream release. The exact versions MUST match the other
        // Packages/*/Package.swift and project.yml or SPM resolution fails. Bump deliberately.
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.20"),
    ],
    targets: [
        .target(name: "StrandImport", dependencies: [
            "WhoopProtocol", "WhoopStore",
            .product(name: "ZIPFoundation", package: "ZIPFoundation"),
            // Read-only access to a *foreign* SQLite file (the Mi Fitness export) goes
            // through RawSQLite.swift on the system sqlite3, no external dependency.
        ]),
        .testTarget(name: "StrandImportTests", dependencies: [
            "StrandImport",
        ], resources: [
            .copy("Resources"),
        ]),
    ]
)
