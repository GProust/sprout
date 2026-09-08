# BDR-13. Protecting the file that leaves, and refusing the encryption that doesn't

Date: 2026-09-08
Type: Product / Domain rule

## Status

Accepted.

Extends [BDR-0012](0012-a-record-for-the-doctor.md), which decided what the
report says and left it as a file in the clear.

## Context

BDR-0012 was careful about everything up to the moment the parent taps *share*,
and had nothing to say about what happens afterwards. Afterwards is where a
health record actually lives: in a WhatsApp thread that is still there two years
later, in a mail client's sent folder, in a downloads directory on a computer
somebody else also uses, on whatever server the messaging app kept a copy on.

The app cannot control any of that. It can make the bytes useless to whoever
ends up holding them.

There is a trap in doing it, and it is the reason this record exists rather than
just a commit. Zip encryption comes in two kinds, and the *convenient* one is
worthless:

- **ZipCrypto**, the legacy PKWARE scheme, is what Windows Explorer and macOS
  Archive Utility open by double-click. It falls to a known-plaintext attack in
  seconds, and our archive would supply the plaintext: a PDF starts with `%PDF-`
  and an `.xlsx` starts with a zip header. Choosing it would produce a file that
  *feels* protected and is not — the worst outcome available, because it changes
  what the parent believes they can safely send.
- **AES-256**, the WinZip AE-2 scheme, is sound. It is also the one that some
  recipients cannot open without installing something.

So the choice is not "encryption or no encryption". It is "real encryption with
a cost that lands on the doctor" against "the appearance of encryption with no
cost at all", and the second one is a lie told to a parent about their baby's
medical data.

## Decision

**The report can go out as a single AES-256 encrypted zip containing both files,
under a password the parent types.**

**ZipCrypto is not implemented and will not be.** Not as a fallback, not as an
option, not for compatibility. If a recipient cannot open AES, the answer is a
different way of sending the file, not a weaker archive.

**One archive, both documents, one password.** A password is something a person
has to pass on by another route, and nobody wants to do that twice. The PDF and
the workbook travel together.

**The password is the parent's, and Sprout keeps no copy.** Not in preferences,
not in the export options, not in the file name, nowhere. It exists in memory for
as long as the screen is open and is wiped from the character array once the
archive is written. The consequence is stated plainly in the app: a forgotten
password cannot be recovered, by us or by anyone.

**A minimum of six characters, and the ability to show it.** The minimum is a
floor against a two-character password, not a policy: rules that demand a symbol
and a capital produce passwords people write on the file they are protecting. It
can be revealed while typing, because this password has to be read out to another
human being to be worth anything, and one typed blind is one dictated wrongly.

**The app says what the recipient will need,** on the screen, before the file is
made: AES-256, opens with 7-Zip, WinZip, Keka or most Android file managers, and
*not* with the unzip built into Windows or macOS. A parent who sends a file their
doctor cannot open has been failed by us, not by the doctor.

**The app says how to send the password:** in person or by phone, not in the same
message as the file. A password in the same thread protects against a casual
scroll and nothing else, and this is the one part of the threat the parent
controls entirely.

**Protection is off by default.** Most reports are handed over in person or
printed, and the extra step should be there for whoever wants it rather than in
everybody's way.

## Consequences

- Sprout now carries hand-written cryptography, which is a thing to be nervous
  about. The mitigation is that none of the primitives are ours — AES and HMAC
  come from `javax.crypto`, as they already do for partner sync (ADR-0007) — and
  that the two places where hand-written code goes wrong are tested against
  outside references: the key derivation against the RFC 6070 vectors, and a full
  encrypted entry against a vector produced by an independent implementation and
  confirmed by having 7-Zip extract it.
- One deliberate exception to "no dependency" reasoning cuts the other way here:
  the key derivation is written out rather than taken from
  `PBKDF2WithHmacSHA1`, because JCE providers have historically disagreed about
  how a password's characters become bytes. A disagreement there yields an
  archive nobody can open, discovered by the recipient rather than by us.
- The encryption is only as good as the password, and the password is chosen by
  a tired parent. That is the right trade — the alternative is a key Sprout
  keeps, which is a key Sprout can lose — but it means the archive's real
  strength is not something the app can claim a number for.
- `PRIVACY.md` gains what this does and does not protect. It does not protect
  the copy the recipient decrypts, and it never protected the parent from the
  app they choose to send it with.
