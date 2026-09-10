// swift-tools-version: 5.9
import PackageDescription

// SproutKit is the part of the iOS app that has an opposite number on Android:
// the formats two phones exchange, and nothing else. It deliberately does not
// depend on SwiftUI, CoreBluetooth or the database — everything here is bytes
// in, bytes out, which is what lets the whole of it be tested on a CI runner
// with no simulator, no radio and no signing.
//
// It also has no third-party dependencies. Crypto comes from CryptoKit and
// compression from Accelerate's Compression framework, both system frameworks,
// in the spirit of ADR-0013.
let package = Package(
    name: "SproutKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "SproutKit", targets: ["SproutKit"]),
    ],
    targets: [
        .target(name: "SproutKit"),
        .testTarget(name: "SproutKitTests", dependencies: ["SproutKit"]),
    ]
)
