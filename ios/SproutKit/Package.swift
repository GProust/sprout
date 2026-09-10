// swift-tools-version: 5.10
import PackageDescription

// Two libraries, and the split matters.
//
// **SproutKit** is the part with an opposite number on Android: the formats two
// phones exchange, and nothing else. It has no dependencies at all — crypto from
// CryptoKit, compression from the Compression framework, both system frameworks
// — so the whole of it can be tested on a CI runner with no simulator, no radio
// and no signing.
//
// **SproutData** is the local database: the schema Room defines on the other
// side, the records, and the repository every screen reads through. It depends
// on GRDB (ADR-0017) and on SproutKit for the uid the sync layer stamps. It is
// still UI-free, so its tests run on the same runner without a simulator.
//
// The app target on top of these is SwiftUI and nothing else.
let package = Package(
    name: "SproutKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "SproutKit", targets: ["SproutKit"]),
        .library(name: "SproutData", targets: ["SproutData"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        .target(name: "SproutKit"),
        .target(
            name: "SproutData",
            dependencies: [
                "SproutKit",
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
        .testTarget(name: "SproutKitTests", dependencies: ["SproutKit"]),
        .testTarget(name: "SproutDataTests", dependencies: ["SproutData"]),
    ]
)
