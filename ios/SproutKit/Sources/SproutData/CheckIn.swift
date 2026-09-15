import Foundation

/// A single question in the parent's daily check-in, from
/// `ui/common/CheckInQuestions.kt`.
public enum CheckInQuestion: String, CaseIterable, Sendable {
    case mood, healing, bleeding, breasts, notes

    /// Whether the question offers "don't ask me this anymore" — and therefore
    /// has a switch in Settings.
    ///
    /// Mood and notes are the check-in itself; a check-in without them is not a
    /// shorter check-in, it is nothing.
    public var isOptional: Bool {
        switch self {
        case .healing, .bleeding, .breasts: return true
        case .mood, .notes: return false
        }
    }
}

/// The questions this parent is asked, in order.
///
/// **Capability, not role** (BDR-0001). The postpartum questions are asked of
/// someone who gave birth and the breast question of someone breastfeeding, so
/// one flow fits an adoptive father, a birth mother six weeks in and a partner
/// doing the night bottles — without any of them being asked to pick a label
/// first. Each body question can also be retired once it no longer needs a daily
/// look, which is what the `ask*` flags carry.
public func checkInQuestions(
    gaveBirth: Bool,
    breastfeeding: Bool,
    askHealing: Bool = true,
    askBleeding: Bool = true,
    askBreasts: Bool = true
) -> [CheckInQuestion] {
    var questions: [CheckInQuestion] = [.mood]
    if gaveBirth {
        if askHealing { questions.append(.healing) }
        if askBleeding { questions.append(.bleeding) }
    }
    if breastfeeding && askBreasts {
        questions.append(.breasts)
    }
    questions.append(.notes)
    return questions
}

/// True when today's check-in has not happened yet.
public func needsCheckIn(lastCheckIn: Int64?, now: Int64) -> Bool {
    guard let lastCheckIn else { return true }
    return !SproutFormat.isSameDay(lastCheckIn, now)
}

/// Whether the dashboard should offer today's check-in.
///
/// It waits on the dashboard rather than interrupting at launch (BDR-0006) — a
/// 3 a.m. feed is no moment to answer questions about yourself — and goes for
/// the day once the check-in is saved *or* set aside. A parent who has turned
/// their own tracking off is never offered it at all.
public func shouldOfferCheckIn(trackWellbeing: Bool, lastCheckIn: Int64?, now: Int64) -> Bool {
    trackWellbeing && needsCheckIn(lastCheckIn: lastCheckIn, now: now)
}
