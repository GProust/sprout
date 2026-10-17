import Foundation

// Three columns hold a list inside a TEXT value, because Room's converters put
// them there. The encodings are Android's, reproduced exactly: the database is
// local, but a phone restored from a backup and a replica read by the sync
// engine both expect these bytes, and "nearly the same" is a column that parses
// to nothing.

/// Reminder times, as Room stores them: minutes since midnight, comma-separated.
public enum IntListCoding {

    public static func encode(_ values: [Int]) -> String {
        values.map(String.init).joined(separator: ",")
    }

    /// Blank entries are dropped rather than read as zero — Kotlin's
    /// `filter { it.isNotBlank() }`. A trailing comma is a formatting artefact,
    /// not a reminder at midnight.
    public static func decode(_ text: String?) -> [Int] {
        guard let text else { return [] }
        return text
            .split(separator: ",", omittingEmptySubsequences: true)
            .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }
}

/// Breastfeeding segments, as Room stores them: `SIDE,start,end` triples joined
/// by `;`.
public enum NursingSegmentCoding {

    public static func encode(_ segments: [NursingSegment]) -> String {
        segments
            .map { "\($0.side.rawValue),\($0.startTime),\($0.endTime)" }
            .joined(separator: ";")
    }

    /// A malformed triple is skipped, not fatal.
    ///
    /// Kotlin's destructuring would throw on a triple with the wrong number of
    /// parts, and this column can arrive from a merge with whatever the other
    /// phone wrote. Losing one segment of one feed is a worse outcome than
    /// nothing only if the alternative is losing the whole session, which
    /// throwing here would do.
    public static func decode(_ text: String?) -> [NursingSegment] {
        guard let text, !text.isEmpty else { return [] }
        return text.split(separator: ";", omittingEmptySubsequences: true).compactMap { triple in
            let parts = triple.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 3,
                  let side = BreastSide(rawValue: String(parts[0])),
                  let start = Int64(parts[1]),
                  let end = Int64(parts[2])
            else { return nil }
            return NursingSegment(side: side, startTime: start, endTime: end)
        }
    }
}

/// A solid meal's foods (BDR-20), as Room stores them: a JSON array of
/// `{"name", "portion", "reception"}` objects, and an empty list as an empty
/// string — so every row from before the column existed already reads as a meal
/// that named nothing.
///
/// JSON rather than a packed string like the segments, because a food is the
/// parent's own words and any separator a packing chose is a character somebody
/// will type. The same objects are what travel in a replica (spec §7).
public enum MealFoodCoding {

    public static func encode(_ foods: [MealFood]) -> String {
        guard !foods.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: jsonArray(foods), options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else { return "" }
        return text
    }

    /// A column that does not parse reads as a meal that named nothing, and a
    /// food that does not is skipped: a list from a merge is not a reason to
    /// lose the meal it belongs to.
    public static func decode(_ text: String?) -> [MealFood] {
        guard let text, !text.isEmpty,
              let data = text.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return array.compactMap { try? food(from: $0) }
    }

    /// The objects a replica carries. `reception` is left out rather than
    /// written as null: "not noted" is no key at all.
    static func jsonArray(_ foods: [MealFood]) -> [[String: Any]] {
        foods.map { food in
            var json: [String: Any] = ["name": food.name, "portion": food.portion.rawValue]
            if let reception = food.reception { json["reception"] = reception.rawValue }
            return json
        }
    }

    /// One food from a replica, read as strictly as Android reads it: a missing
    /// name or a portion this app does not know is a damaged document.
    static func food(from value: Any) throws -> MealFood {
        guard let json = value as? [String: Any],
              let name = json["name"] as? String,
              let portionName = json["portion"] as? String,
              let portion = FoodPortion(rawValue: portionName)
        else { throw SyncPayloadError.unreadable("damaged replica: a meal's food is malformed") }
        var reception: FoodReception?
        if let raw = json["reception"], !(raw is NSNull) {
            guard let text = raw as? String, let value = FoodReception(rawValue: text)
            else { throw SyncPayloadError.unreadable("damaged replica: unknown reception") }
            reception = value
        }
        return MealFood(name: name, portion: portion, reception: reception)
    }
}
