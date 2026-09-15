# Wire format

Byte-level description of everything that crosses between two phones. Sizes are
in bytes, integers are **big-endian**, and strings are **UTF-8** unless a section
says otherwise.

Vectors for each section are in [`vectors/`](vectors/).

---

## 1. The household secret

32 random bytes, generated once by the phone that starts the pairing
([ADR-0008](../docs/adr/0008-pairing-by-invitation-and-the-first-merge.md)). It
is the AES key directly — there is no derivation step between the secret and the
key.

### Verification code

Six characters both phones show after pairing, for the parents to read to each
other.

```
digest = SHA-256(secret)
code[i] = ALPHABET[digest[i] mod 32]   for i in 0..5
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
```

`digest[i]` is an **unsigned** byte. Kotlin needs `and 0xFF` for this; Swift's
`UInt8` is already unsigned. Getting it wrong changes the code on roughly half
of all secrets, and the two phones then disagree about a string whose entire job
is to agree.

The alphabet omits `0`/`O` and `1`/`I` deliberately: it gets read aloud at 3 a.m.

**Vector:** `secret.json`

---

## 2. Row identifiers

Every row that syncs carries a `uid` — the name it goes by on every phone, since
the local `id` is a per-device counter.

```
uid = UUID v4, lowercase, hyphenated   e.g. "3f2b9c10-5d7a-4e21-8b6f-0a1c2d3e4f50"
```

**Lowercase is load-bearing.** The merge matches rows by exact string comparison,
so a uid that differs only in case is a different row: every merge would
duplicate instead of update, silently, for as long as the two phones keep
meeting. Android gets this free from `UUID.randomUUID().toString()`; Swift's
`UUID().uuidString` is uppercase and must be lowered.

---

## 3. A sealed replica

What leaves the phone, whatever carries it — a messaging app, Quick Share, or
the direct exchange in §5.

```
"SPRT" | version:1 | nonce:12 | AES-256-GCM(gzip(plaintext)) || tag:16
└──── header, 5 ────┘
```

| Field | Size | Value |
|---|---|---|
| magic | 4 | ASCII `SPRT` |
| version | 1 | `0x01` |
| nonce | 12 | fresh from a CSPRNG on every seal |
| body | rest | ciphertext with the 128-bit tag appended |

- **Key**: the 32-byte secret, used raw.
- **Associated data**: the 5-byte header. So the version byte cannot be rewritten
  to talk an older reader into a different interpretation — altering it fails the
  tag.
- **Order**: gzip *then* encrypt. Compressing afterwards would achieve nothing,
  and doing both in the other order on one platform produces a file the other
  cannot read.
- **Tag placement**: appended to the ciphertext, which is what Java's
  `Cipher.doFinal` returns. Swift's `CryptoKit` keeps `.ciphertext` and `.tag`
  separate — concatenate on seal, split the last 16 bytes on open.

A reader must refuse, distinguishably: bytes too short to hold a header and
nonce, a wrong magic, a version above its own, a tag that does not verify, and
gzip that does not inflate. From outside these are all the same event; they are
told apart for the user's sake, not the attacker's.

**Vector:** `sealed.json` — includes a tampered copy and a copy with the version
byte rewritten. Both must fail the tag.

---

## 4. The invitation

A JSON document, deliberately **not** encrypted: there is no shared key yet, and
pretending otherwise would be theatre. What narrows the window is that it
expires and is accepted once.

```json
{
  "formatVersion": 1,
  "householdId": "…",
  "secret": "base64, unwrapped, padded",
  "createdAt": 1757400000000,
  "fromName": "Alex"
}
```

- `createdAt` is epoch milliseconds. Valid for **24 h**, inclusive at the
  boundary: `now - createdAt > 86400000` is expired.
- `formatVersion` above the reader's own is *tooNew*, and must be reported as
  such rather than as an unreadable file — the user can act on "update Sprout".
- `fromName` is optional and may be empty.
- Key order is not significant.

**Vector:** `invitation.json`

---

## 5. The direct exchange

Once two phones are connected, over any stream (see
[ADR-0016](../docs/adr/0016-a-transport-both-platforms-can-speak.md) for what
carries it):

```
"SPRTS" | version:1 | length:int32 | payload
```

The payload is exactly a sealed replica from §3 — this layer has no crypto of
its own to get wrong.

- `length` is a **big-endian signed** int32. A negative value, or one above
  8 MiB, is refused before a buffer is allocated.
- The exchange is **symmetric**: both ends write their frame and read the
  other's. Both must write before either reads, so an implementation has to
  send on a separate thread, task, or non-blocking write — a straightforward
  write-then-read deadlocks both phones on any link whose buffer is smaller than
  a replica.

**Vector:** `session-frame.json`

---

## 6. The advertisement

How a phone recognises its own household over the air, without broadcasting
anything an onlooker could follow from one week to the next
([ADR-0010](../docs/adr/0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md),
amended by [ADR-0016](../docs/adr/0016-a-transport-both-platforms-can-speak.md)).

```
window = floor(epochMillis / 1800000)          // 30 minutes
beacon = HMAC-SHA256(secret, ascii(decimal window))[0..8]
advertUuid = HMAC-SHA256(secret, ascii("sprout-adv-v1:" + window))[0..16]
```

The message is the window number written as a **decimal ASCII string**, not its
bytes — `"1"`, not `0x0000000000000001`.

- `beacon` is the 8-byte value Android advertises today as BLE service data.
- `advertUuid` is the 128-bit service UUID that replaces it, because iOS cannot
  advertise service data at all. Raw bits, with no RFC-4122 version or variant
  forced: `CBUUID` and `java.util.UUID` both take an arbitrary 128-bit value, and
  spending six bits to look like a v4 buys nothing.

A listener accepts **the current window and the previous one**, so two phones a
few minutes apart, or meeting either side of a boundary, still recognise each
other. Comparison is constant-time; a timing oracle here would leak whether a
guess is close.

**Vector:** `beacon.json`

---

## 7. The replica document

The plaintext inside §3 — what one phone actually sends the other. UTF-8 JSON.

```json
{
  "formatVersion": 1,
  "schemaVersion": 16,
  "householdId": "…", "deviceId": "…", "deviceName": "…",
  "createdAt": 1757400000000,
  "babies": [], "feedings": [], "sleeps": [], "diapers": [],
  "growth": [], "treatments": [], "pumpings": [], "tombstones": []
}
```

- **`formatVersion` is this document's, `schemaVersion` is the sender's
  database.** A reader refuses either being above its own — distinguishably,
  because "your partner's phone is newer than yours" is a different sentence from
  "this file is damaged". Both missing is unreadable, not version 0.
- **Local ids never travel.** `id` is a per-device counter and would mean
  something else on the other phone. Rows are named by `uid` and nothing else,
  and a baby-scoped row carries `babyUid` — resolved to a local id at merge time.
- **The parent's own data never travels.** No `wellbeing`, no `parent_profile`.
  `pumpings` is present only when the sender's stash switch is on.
- **Soft-deleted rows are included, flagged.** That is how a deletion reaches the
  other phone instead of being undone by it. `tombstones` carries `uid`, `entity`
  and `deletedAt` for rows that were erased outright.
- **A null is an absent key.** `org.json`'s `put(key, null)` removes the key, so
  a writer must omit rather than write `null`, and a reader must treat absent and
  null alike. An implementation that writes `"deletedAt": null` produces a
  document Android reads identically — but one that *requires* the key does not.
- **Unknown keys are ignored and absent lists are empty.** That is what lets a
  field be added without a version bump: a replica written before sleeps recorded
  a position merges exactly as it did, as a sleep with nothing noted.
- **Enums travel as their names**, upper-case, exactly as the database stores
  them: `BREAST`, `OWN_BED`, `FRIDGE`. Never an ordinal — a reordered enum would
  silently rewrite history.
- **Two fields are packed strings, not arrays**, because that is how Room stores
  them: a treatment's `timesOfDay` is minutes joined by `,`, and a feeding's
  `segments` is `SIDE,start,end` triples joined by `;`. Empty is `""`.

**Vector:** `replica.json` — a document with one of every row type, and the
absent-versus-null cases spelled out.

---

## 8. What a document may not do

Checked **before** parsing, not around it
([ADR-0014](../docs/adr/0014-the-way-in-is-an-allow-list.md)):

| Limit | Value |
|---|---|
| largest file read into memory | 8 MiB |
| largest frame on the wire | 8 MiB |
| deepest nesting | 32 |

Depth is counted by scanning brackets, not by parsing — brackets inside a
double-quoted string do not count, and a backslash escapes whatever follows.
Everything outside such a string counts, including brackets in the places a
lenient parser also allows. Over-counting refuses a file no Sprout ever wrote;
under-counting hands the parser the input this exists to keep away from it.

The reason this is a pre-check is platform-specific but the rule is not: on
Android, exhausting the stack raises `StackOverflowError`, an `Error` that
`catch (e: Exception)` lets straight through. A Swift parser fails differently
and just as fatally. Neither can catch it after the fact.

**Vector:** `json-depth.json`
