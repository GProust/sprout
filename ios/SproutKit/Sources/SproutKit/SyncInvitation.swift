import Foundation

/// The thing one parent sends the other to pair (ADR-0008).
///
/// It travels the same way the data will — AirDrop, a messaging app, the Files
/// app — which is what keeps pairing free of a camera permission, and is also
/// the one moment where the secret is only as private as the channel the user
/// picked. Two things narrow that window: it **expires**, and it is **accepted
/// once**, after which both phones show a verification code to check.
///
/// Deliberately *not* encrypted: there is no shared key yet — that is the whole
/// point of it — so pretending otherwise would only be theatre.
public struct SyncInvitation: Equatable {

    /// Long enough for "I'll send it when I'm home", short enough that a stale
    /// invitation in a chat history is worthless.
    public static let validForMs: Int64 = 24 * 60 * 60 * 1000
    public static let formatVersion = 1

    public let householdId: String
    public let secret: SyncSecret
    public let createdAt: Int64
    /// Who is inviting, so the other phone can say "paired with Alex's phone".
    public let fromName: String

    public init(householdId: String, secret: SyncSecret, createdAt: Int64, fromName: String) {
        self.householdId = householdId
        self.secret = secret
        self.createdAt = createdAt
        self.fromName = fromName
    }

    public func isExpired(now: Int64) -> Bool {
        now - createdAt > Self.validForMs
    }
}

/// Raised when an invitation cannot be used, with the reason the UI has to
/// explain — "update Sprout" and "ask for a new one" are different sentences.
public enum SyncInvitationError: Error, Equatable {
    case unreadable
    case tooNew(formatVersion: Int)
    case expired
}

public enum SyncInvitationCodec {

    public static func encode(_ invitation: SyncInvitation) throws -> Data {
        let document: [String: Any] = [
            "formatVersion": SyncInvitation.formatVersion,
            "householdId": invitation.householdId,
            "secret": invitation.secret.bytes.base64EncodedString(),
            "createdAt": invitation.createdAt,
            "fromName": invitation.fromName,
        ]
        return try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
    }

    public static func decode(_ bytes: Data, now: Int64) throws -> SyncInvitation {
        guard bytes.count <= SyncLimits.maxFileBytes else { throw SyncInvitationError.unreadable }
        guard let text = String(data: bytes, encoding: .utf8) else {
            throw SyncInvitationError.unreadable
        }
        // Before parsing, not around it — see SyncLimits.exceedsMaxJsonDepth.
        // An invitation is three levels deep.
        guard !SyncLimits.exceedsMaxJsonDepth(text) else { throw SyncInvitationError.unreadable }

        guard let root = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            throw SyncInvitationError.unreadable
        }

        guard let formatVersion = (root["formatVersion"] as? NSNumber)?.intValue, formatVersion >= 0 else {
            throw SyncInvitationError.unreadable
        }
        guard formatVersion <= SyncInvitation.formatVersion else {
            throw SyncInvitationError.tooNew(formatVersion: formatVersion)
        }

        guard
            let householdId = root["householdId"] as? String,
            let encodedSecret = root["secret"] as? String,
            let secretBytes = Data(base64Encoded: encodedSecret),
            let secret = try? SyncSecret(bytes: secretBytes),
            let createdAt = (root["createdAt"] as? NSNumber)?.int64Value
        else {
            throw SyncInvitationError.unreadable
        }

        let invitation = SyncInvitation(
            householdId: householdId,
            secret: secret,
            createdAt: createdAt,
            // Android writes this with optString, so an absent name is "" and
            // not a refusal. A missing name is a nameless phone, not a bad file.
            fromName: root["fromName"] as? String ?? ""
        )
        guard !invitation.isExpired(now: now) else { throw SyncInvitationError.expired }
        return invitation
    }
}
