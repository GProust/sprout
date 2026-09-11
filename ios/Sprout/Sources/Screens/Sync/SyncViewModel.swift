import Foundation
import SproutData
import SproutKit
import SwiftUI

/// The end of an exchange, as something to put in front of the parent.
enum SyncOutcome: Identifiable {
    /// Pairing succeeded; `code` is what both parents should see.
    case paired(partnerName: String, code: String)
    case merged(MergeSummary)
    /// A device was removed, so the household's secret changed. Everyone who
    /// stays needs a fresh invitation — including anyone who was simply away
    /// (ADR-0009).
    case secretRotated(code: String)
    /// Both phones tracked before pairing, so their entries cannot be matched up
    /// (ADR-0008). The parent decides, once, what the first merge should do.
    case askAboutHistories(URL)
    /// A key in the catalog, so the sentence is Android's.
    case failed(messageKey: String)

    var id: String {
        switch self {
        case .paired(_, let code): return "paired-\(code)"
        case .merged: return "merged"
        case .secretRotated(let code): return "rotated-\(code)"
        case .askAboutHistories(let url): return "histories-\(url.path)"
        case .failed(let key): return "failed-\(key)"
        }
    }
}

/// What the detached half of a merge decided, so the main actor can act on it
/// without any of the work having happened there.
///
/// At file scope rather than nested in the view model: this value crosses an
/// actor boundary by design, and a type nested in a `@MainActor` class is one
/// isolation question too many.
private enum ReplicaResult: Sendable {
    case merged(MergeSummary, deviceId: String, deviceName: String)
    case askAboutHistories
    case failed(String)
}

/// What the first merge should do when both phones tracked before pairing.
enum HistoryChoice {
    case keepEverything
    case fromPairing
}

/// Pairing with the household's other phones, and exchanging replicas with them.
/// From `ui/sync/SyncViewModel.kt`.
///
/// **One of Android's two ways in, for now.** By hand — a file is written when
/// the parents ask to send one, and read when they hand one over — works here
/// exactly as it does there. The automatic exchange does not: Android's is
/// RFCOMM, which iOS cannot speak at all, and the transport both platforms *can*
/// speak is decided but written on neither side yet (ADR-0016). The screen says
/// so rather than offering a switch that would do nothing.
///
/// Nothing here touches the network. There is no code in this app that could.
@Observable
@MainActor
final class SyncViewModel {

    private let repository: SproutRepository
    private let engine: SyncEngine
    private let pairingStore: PairingStore
    private let householdDevices: HouseholdDevices
    private let deviceStore: any DeviceLocalStore

    private(set) var pairing: Pairing?
    /// The other phones this one has heard from (ADR-0009).
    private(set) var devices: [HouseholdDevice] = []
    private(set) var busy = false

    /// A file to hand to the share sheet; consumed once shared.
    var share: SharedFile?
    /// The subject line that goes with it — different for the two kinds of file.
    private(set) var shareSubjectKey = "sync_share_data_subject"
    var outcome: SyncOutcome?

    init(
        repository: SproutRepository,
        engine: SyncEngine,
        pairingStore: PairingStore,
        householdDevices: HouseholdDevices,
        deviceStore: any DeviceLocalStore
    ) {
        self.repository = repository
        self.engine = engine
        self.pairingStore = pairingStore
        self.householdDevices = householdDevices
        self.deviceStore = deviceStore
        self.pairing = pairingStore.current()
        self.devices = householdDevices.all()
    }

    // MARK: - Making files

    /// Starts a pairing: a fresh household and a fresh secret, written to a file
    /// for the partner to open. This phone is paired the moment it is created —
    /// it already knows the secret — so its own data is ready to travel.
    ///
    /// On the main actor throughout, and deliberately: this writes one 32-byte
    /// key and a name. `exportReplica` is the one that reads a year of rows, and
    /// it says so.
    func createInvitation(shareStash: Bool) {
        beginWork()
        defer { busy = false }

        let pairing: Pairing
        if let existing = pairingStore.current() {
            pairing = existing
        } else {
            pairing = Pairing(
                householdId: newUid(),
                secret: SyncSecret.random(),
                pairedAt: Clock.millis,
                shareStash: shareStash
            )
            pairingStore.save(pairing)
        }

        let invitation = SyncInvitation(
            householdId: pairing.householdId,
            secret: pairing.secret,
            createdAt: Clock.millis,
            // So the other phone can say who it just paired with, rather than
            // "an unknown device".
            fromName: (try? repository.parentProfileOnce()?.name) ?? ""
        )
        do {
            let url = try SyncFiles.stage(
                try SyncInvitationCodec.encode(invitation),
                named: "sprout-invitation"
            )
            self.pairing = pairing
            shareSubjectKey = "sync_share_invitation_subject"
            share = SharedFile(url: url)
        } catch {
            fail("sync_error_unreadable")
        }
    }

    /// This phone's replica, ready to send.
    ///
    /// **Off the main actor**, like the report export and for the same reason: a
    /// year of entries is a few thousand rows to read, serialise, compress and
    /// encrypt. The screen says it is working meanwhile rather than appearing to
    /// have ignored the tap.
    func exportReplica() async {
        beginWork()
        defer { busy = false }

        guard let pairing = pairingStore.current() else {
            return fail("sync_error_not_paired")
        }
        let deviceId = DeviceIdentity.id(in: deviceStore)
        // So the household list on the other phones can name this one.
        let deviceName = (try? repository.parentProfileOnce()?.name) ?? ""
        let engine = self.engine

        do {
            let url = try await Task.detached(priority: .userInitiated) {
                let payload = try engine.buildPayload(
                    householdId: pairing.householdId,
                    deviceId: deviceId,
                    deviceName: deviceName,
                    includePumping: pairing.shareStash
                )
                let sealed = try SyncCrypto.seal(
                    try SyncPayloadCodec.encode(payload),
                    secret: pairing.secret
                )
                return try SyncFiles.stage(sealed, named: "sprout-data")
            }.value

            shareSubjectKey = "sync_share_data_subject"
            share = SharedFile(url: url)
        } catch {
            fail("sync_error_unreadable")
        }
    }

    // MARK: - Taking files in

    /// Takes in whatever file the parent was sent. An invitation and a replica
    /// look different from the first byte, so the parents never have to know
    /// which of the two they are opening.
    func open(_ url: URL, historyChoice: HistoryChoice? = nil) async {
        beginWork()
        defer { busy = false }

        guard let bytes = try? SyncFiles.read(url) else {
            return fail("sync_error_unreadable")
        }
        if Self.looksLikeReplica(bytes) {
            await acceptReplica(url, bytes, historyChoice)
        } else {
            acceptInvitation(bytes)
        }
    }

    private func acceptInvitation(_ bytes: Data) {
        let invitation: SyncInvitation
        do {
            invitation = try SyncInvitationCodec.decode(bytes, now: Clock.millis)
        } catch SyncInvitationError.expired {
            return fail("sync_error_invitation_expired")
        } catch SyncInvitationError.tooNew {
            return fail("sync_error_too_new")
        } catch {
            return fail("sync_error_unreadable")
        }

        let existing = pairingStore.current()
        // Re-invited after a rotation, this is the same pairing with a new key —
        // moving the cut-off would quietly change what "share from the pairing
        // forward" means.
        let rejoined = existing?.householdId == invitation.householdId ? existing : nil
        let pairing = Pairing(
            householdId: invitation.householdId,
            secret: invitation.secret,
            pairedAt: rejoined?.pairedAt ?? Clock.millis,
            shareStash: existing?.shareStash ?? true
        )
        pairingStore.save(pairing)
        self.pairing = pairing
        outcome = .paired(partnerName: invitation.fromName, code: pairing.verificationCode())
    }

    private func acceptReplica(_ url: URL, _ bytes: Data, _ historyChoice: HistoryChoice?) async {
        guard let pairing = pairingStore.current() else {
            return fail("sync_error_not_paired")
        }
        let firstMergeDone = pairingStore.firstMergeDone()
        let engine = self.engine

        let result = await Task.detached(priority: .userInitiated) { () -> ReplicaResult in
            guard let plain = try? SyncCrypto.open(bytes, secret: pairing.secret) else {
                // Not a decryption detail the parent can act on: the only useful
                // thing to say is that this file was not meant for this phone.
                return .failed("sync_error_wrong_phone")
            }

            let payload: SyncPayload
            do {
                payload = try SyncPayloadCodec.decode(
                    plain,
                    currentSchemaVersion: engine.schemaVersion
                )
            } catch SyncPayloadError.tooNew {
                return .failed("sync_error_too_new")
            } catch {
                return .failed("sync_error_unreadable")
            }
            guard payload.householdId == pairing.householdId else {
                return .failed("sync_error_wrong_phone")
            }

            // The first merge is the only one where the two histories can be
            // irreconcilable, and the only one worth interrupting for (ADR-0008).
            do {
                let bothHaveHistory = try !firstMergeDone
                    && engine.hasOwnHistory()
                    && payload.babies.contains(where: { $0.deletedAt == nil })
                if bothHaveHistory, historyChoice == nil { return .askAboutHistories }

                let since = historyChoice == .fromPairing ? pairing.pairedAt : nil
                let summary = try engine.merge(payload, since: since)
                return .merged(
                    summary,
                    deviceId: payload.deviceId,
                    deviceName: payload.deviceName
                )
            } catch {
                return .failed("sync_error_unreadable")
            }
        }.value

        switch result {
        case .failed(let key):
            fail(key)
        case .askAboutHistories:
            outcome = .askAboutHistories(url)
        case .merged(let summary, let deviceId, let deviceName):
            pairingStore.markFirstMergeDone()
            householdDevices.seen(deviceId: deviceId, name: deviceName, at: Clock.millis)
            try? selectABabyIfNoneActive()
            devices = householdDevices.all()
            outcome = .merged(summary)
        }
    }

    /// A phone that just adopted its partner's baby has rows but no *active*
    /// baby — the merge writes data, it does not choose what the dashboard
    /// shows. Without this, a freshly paired phone opens on an empty screen.
    private func selectABabyIfNoneActive() throws {
        guard try repository.activeBabyIdNow() == nil else { return }
        if let first = try repository.activeBabies().first, let id = first.id {
            try repository.setActiveBaby(id: id)
        }
    }

    /// A replica is framed; an invitation is plain JSON.
    private static func looksLikeReplica(_ bytes: Data) -> Bool {
        bytes.count >= 4 && bytes.prefix(4).elementsEqual(Array("SPRT".utf8))
    }

    // MARK: - Switches and removal

    func setShareStash(_ share: Bool) {
        pairingStore.setShareStash(share)
        pairing = pairingStore.current()
    }

    /// Forget the partner. The data that already merged stays — it is the
    /// household's record, not a lease.
    func unpair() {
        pairingStore.unpair()
        householdDevices.clear()
        pairing = nil
        devices = []
        outcome = nil
    }

    /// Removes a phone from the household (ADR-0009).
    ///
    /// Membership is possession of the secret, so this rotates it: the removed
    /// phone can no longer read what comes next, and **every** other phone needs
    /// a fresh invitation before it can either. The removed phone keeps what it
    /// already has, which no amount of rotation can change.
    func removeDevice(_ deviceId: String) {
        beginWork()
        defer { busy = false }

        householdDevices.forget(deviceId: deviceId)
        guard let rotated = pairingStore.rotateSecret(now: Clock.millis) else {
            return fail("sync_error_not_paired")
        }
        pairing = rotated
        devices = householdDevices.all()
        outcome = .secretRotated(code: rotated.verificationCode())
    }

    // MARK: - Plumbing

    private func beginWork() {
        busy = true
        outcome = nil
    }

    private func fail(_ messageKey: String) {
        outcome = .failed(messageKey: messageKey)
    }
}
