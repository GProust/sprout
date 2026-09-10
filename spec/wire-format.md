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

## 2. A sealed replica

What leaves the phone, whatever carries it — a messaging app, Quick Share, or
the direct exchange in §4.

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

## 3. The invitation

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

## 4. The direct exchange

Once two phones are connected, over any stream (see
[ADR-0016](../docs/adr/0016-a-transport-both-platforms-can-speak.md) for what
carries it):

```
"SPRTS" | version:1 | length:int32 | payload
```

The payload is exactly a sealed replica from §2 — this layer has no crypto of
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

## 5. The advertisement

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

## 6. What a document may not do

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
