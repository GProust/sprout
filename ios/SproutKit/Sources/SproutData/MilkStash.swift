import Foundation

/// How long expressed milk keeps in each place, from `ui/pumping/MilkStash.kt`.
///
/// The figures are the widely published "4 hours – 4 days – 6 months" storage
/// guidance for healthy full-term babies (CDC; Santé publique France gives the
/// same). **Reference data, not tuning** — do not round them for tidiness.
///
/// Sprout only ever *shows* these as a reminder that a batch is getting old. It
/// never deletes anything, and a hospital or a premature baby may be given
/// stricter advice, so nothing here is phrased as a rule the parent has broken.
public extension MilkStorage {
    var keepsForMillis: Int64? {
        switch self {
        case .ROOM: return 4 * hourMs
        case .FRIDGE: return 4 * dayMs
        // "About six months" — counted as 180 days, which is all the precision
        // a batch that old is worth.
        case .FREEZER: return 180 * dayMs
        // Gone, so nothing is counted or dated.
        case .USED: return nil
        }
    }
}

private let hourMs: Int64 = 3_600_000
private let dayMs: Int64 = 24 * hourMs

/// When a batch stops being within its storage guidance; `nil` once used.
public func bestBefore(_ entry: Pumping) -> Int64? {
    entry.storage.keepsForMillis.map { entry.time + $0 }
}

/// Whether `entry` has been kept longer than its guidance allows.
public func isPastBestBefore(_ entry: Pumping, now: Int64) -> Bool {
    guard let limit = bestBefore(entry) else { return false }
    return now > limit
}

/// What is in the stash right now, per place, in millilitres.
public struct MilkStash: Equatable, Sendable {
    public var fridgeMl: Int = 0
    public var freezerMl: Int = 0
    public var roomMl: Int = 0

    public var totalMl: Int { fridgeMl + freezerMl + roomMl }
    public var isEmpty: Bool { totalMl == 0 }
}

/// The stash as it stands at `now`.
///
/// Milk that was used — and milk kept past its guidance — is left out, so the
/// totals are **what can actually be given today** rather than everything ever
/// expressed. A number that counted spoiled milk would be worse than no number.
public func milkStash(entries: [Pumping], now: Int64) -> MilkStash {
    let available = entries.filter { $0.storage != .USED && !isPastBestBefore($0, now: now) }
    func total(_ storage: MilkStorage) -> Int {
        available.filter { $0.storage == storage }.reduce(0) { $0 + $1.amountMl }
    }
    return MilkStash(
        fridgeMl: total(.FRIDGE),
        freezerMl: total(.FREEZER),
        roomMl: total(.ROOM)
    )
}

/// Everything expressed since `from` (inclusive), wherever it ended up.
public func pumpedSince(_ entries: [Pumping], from: Int64) -> Int {
    entries.filter { $0.time >= from }.reduce(0) { $0 + $1.amountMl }
}
