import Foundation
import XCTest

/// Reads the conformance vectors in `spec/vectors/`.
///
/// Found by walking up from this file rather than copied in as a bundle
/// resource: SwiftPM will not take resources from outside the package
/// directory, and duplicating the vectors under `ios/` would defeat the point
/// of having one copy that both platforms check themselves against.
enum Vectors {

    static func load(_ name: String) throws -> [String: Any] {
        let url = directory.appendingPathComponent(name)
        guard let data = try? Data(contentsOf: url) else {
            throw XCTSkip("vectors not found at \(url.path) — run `node spec/vectors/generate.mjs`")
        }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw VectorError.malformed(name)
        }
        return root
    }

    static func cases(in name: String) throws -> [[String: Any]] {
        guard let cases = try load(name)["cases"] as? [[String: Any]] else {
            throw VectorError.malformed(name)
        }
        return cases
    }

    static let directory: URL = {
        // …/ios/SproutKit/Tests/SproutKitTests/Vectors.swift → repository root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("spec/vectors")
    }()

    enum VectorError: Error { case malformed(String) }
}

extension Data {
    init(hex: String) {
        var bytes = [UInt8]()
        var index = hex.startIndex
        while index < hex.endIndex, let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) {
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        self = Data(bytes)
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
