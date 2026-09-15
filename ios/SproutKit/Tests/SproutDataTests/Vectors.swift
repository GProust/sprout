import Foundation
import XCTest

/// Reads the conformance vectors in `spec/vectors/`.
///
/// A near-copy of the one in `SproutKitTests`, and deliberately so: SwiftPM test
/// targets cannot share a file without a third target to hold it, and twenty
/// lines of path arithmetic is a smaller thing to carry twice than a target is.
/// What must not be duplicated is the *vectors*, and they are not — both loaders
/// walk to the same `spec/vectors/`, which is the whole point of it being outside
/// `ios/`.
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

    static let directory: URL = {
        // …/ios/SproutKit/Tests/SproutDataTests/Vectors.swift → repository root
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("spec/vectors")
    }()

    enum VectorError: Error { case malformed(String) }
}
