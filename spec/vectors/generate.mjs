#!/usr/bin/env node
// Generates the cross-platform conformance vectors in this directory.
//
// Deliberately written against Node's own crypto and zlib rather than against
// either app: a vector that Android produced would only ever prove iOS agrees
// with Android, not that both agree with the format. This is the same standard
// EncryptedZipTest already holds itself to — check against an implementation
// that shares no code with the one under test.
//
// Run with:  node spec/vectors/generate.mjs
// It is deterministic: re-running it must leave the working tree clean, and CI
// checks exactly that.

import { createCipheriv, createHmac, createHash } from 'node:crypto'
import { gzipSync } from 'node:zlib'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const write = (name, value) =>
  writeFileSync(join(here, name), JSON.stringify(value, null, 2) + '\n')

// ---------------------------------------------------------------- the secret

// A fixed household secret. Not random: every vector below is derived from it,
// so it has to be the same on every machine that regenerates this file.
const SECRET = Buffer.from(
  '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
  'hex',
)

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
const verificationCode = (secret) => {
  const digest = createHash('sha256').update(secret).digest()
  return Array.from({ length: 6 }, (_, i) => CODE_ALPHABET[digest[i] % CODE_ALPHABET.length]).join('')
}

write('secret.json', {
  note: 'The household secret every other vector in this directory is derived from.',
  secretHex: SECRET.toString('hex'),
  secretBase64: SECRET.toString('base64'),
  sizeBytes: SECRET.length,
  verificationCode: verificationCode(SECRET),
})

// ---------------------------------------------------------------- the beacon

const WINDOW_MS = 30 * 60 * 1000
const VALUE_BYTES = 8

const beaconForWindow = (secret, window) =>
  createHmac('sha256', secret).update(String(window), 'ascii').digest().subarray(0, VALUE_BYTES)

// ADR-0016: iOS cannot advertise service data, so the rotating value moves into
// a service UUID both sides derive. Raw 128 bits, no RFC-4122 version/variant
// bits forced — CBUUID and java.util.UUID both accept an arbitrary 128-bit
// value, and spending 6 bits to look like a v4 buys nothing here.
const advertUuidForWindow = (secret, window) => {
  const bytes = createHmac('sha256', secret).update(`sprout-adv-v1:${window}`, 'ascii').digest().subarray(0, 16)
  const hex = bytes.toString('hex')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20, 32)].join('-')
}

// Timestamps chosen to pin the window arithmetic itself: the epoch, both sides
// of a boundary, and a value far enough out that a 32-bit reader would be wrong.
const AT = [0, 1_799_999, 1_800_000, 1_800_001, 1_757_400_000_000, 4_102_444_800_000]

write('beacon.json', {
  note: 'HMAC-SHA256(secret, ascii(decimal window)) truncated to 8 bytes; window = floor(at / 1800000).',
  windowMs: WINDOW_MS,
  valueBytes: VALUE_BYTES,
  advertUuidLabel: 'sprout-adv-v1:',
  cases: AT.map((at) => {
    const window = Math.floor(at / WINDOW_MS)
    return {
      at,
      window,
      beaconHex: beaconForWindow(SECRET, window).toString('hex'),
      advertUuid: advertUuidForWindow(SECRET, window),
    }
  }),
})

// ------------------------------------------------------------ the sealed blob

// A replica, sealed. The nonce is fixed so the whole thing is reproducible —
// real seals draw it from a CSPRNG, which is why the round-trip is tested
// separately from this vector. What this pins is that both platforms can OPEN
// bytes neither of them wrote.
const MAGIC = Buffer.from('SPRT', 'ascii')
const VERSION = 1
const HEADER = Buffer.concat([MAGIC, Buffer.from([VERSION])])
const NONCE = Buffer.from('0b0a09080706050403020100', 'hex')

const PLAINTEXT = Buffer.from(
  JSON.stringify({
    formatVersion: 1,
    householdId: 'household-vector',
    deviceId: 'device-vector',
    deviceName: "Vector's phone",
    babies: [],
  }),
  'utf8',
)

const seal = (plaintext, secret, nonce) => {
  const cipher = createCipheriv('aes-256-gcm', secret, nonce)
  cipher.setAAD(HEADER)
  // gzip before encrypt, and mtime 0 so the bytes do not depend on the clock.
  const body = Buffer.concat([cipher.update(gzipSync(plaintext, { level: 6, mtime: 0 })), cipher.final()])
  return Buffer.concat([HEADER, nonce, body, cipher.getAuthTag()])
}

const sealed = seal(PLAINTEXT, SECRET, NONCE)

write('sealed.json', {
  note: 'Open this with the secret and the result must equal plaintextUtf8, byte for byte.',
  magic: 'SPRT',
  version: VERSION,
  nonceBytes: NONCE.length,
  tagBits: 128,
  headerIsAssociatedData: true,
  nonceHex: NONCE.toString('hex'),
  sealedBase64: sealed.toString('base64'),
  plaintextUtf8: PLAINTEXT.toString('utf8'),
  // Flipping one byte of the ciphertext must fail the tag, not decode to
  // something. The offset lands past the header and nonce, inside the body.
  tamperedBase64: (() => {
    const t = Buffer.from(sealed)
    t[HEADER.length + NONCE.length + 2] ^= 0x01
    return t.toString('base64')
  })(),
  // Same bytes, version byte bumped. It is associated data, so this must fail
  // the tag rather than being read as a version this app happens to support.
  rewrittenVersionBase64: (() => {
    const t = Buffer.from(sealed)
    t[MAGIC.length] = 2
    return t.toString('base64')
  })(),
})

// --------------------------------------------------------- the session frame

const SESSION_MAGIC = Buffer.from('SPRTS', 'ascii')
const framePayload = Buffer.from('a replica would be here', 'utf8')
const frame = Buffer.concat([
  SESSION_MAGIC,
  Buffer.from([1]),
  (() => {
    const len = Buffer.alloc(4)
    len.writeInt32BE(framePayload.length) // DataOutputStream.writeInt is big-endian
    return len
  })(),
  framePayload,
])

write('session-frame.json', {
  note: 'What one phone writes to the other: magic, version, big-endian int32 length, payload.',
  magic: 'SPRTS',
  version: 1,
  lengthIsBigEndianInt32: true,
  maxPayloadBytes: 8 * 1024 * 1024,
  payloadUtf8: framePayload.toString('utf8'),
  frameBase64: frame.toString('base64'),
})

// ----------------------------------------------------------- the invitation

const invitation = {
  formatVersion: 1,
  householdId: 'household-vector',
  secret: SECRET.toString('base64'),
  createdAt: 1_757_400_000_000,
  fromName: 'Alex',
}

write('invitation.json', {
  note: 'Field names and types an invitation file carries. Key order is not significant.',
  validForMs: 24 * 60 * 60 * 1000,
  fields: invitation,
  // Base64 here is NO_WRAP on Android: no line breaks, padded.
  secretEncoding: 'base64, unwrapped, padded',
  cases: [
    { name: 'fresh', now: invitation.createdAt + 1000, expect: 'accepted' },
    { name: 'just inside the window', now: invitation.createdAt + 24 * 60 * 60 * 1000, expect: 'accepted' },
    { name: 'just outside it', now: invitation.createdAt + 24 * 60 * 60 * 1000 + 1, expect: 'expired' },
    { name: 'written by a newer Sprout', formatVersion: 2, now: invitation.createdAt + 1000, expect: 'tooNew' },
  ],
})

// --------------------------------------------------------- the replica document

// The plaintext inside a sealed replica (§7). Written out here rather than by
// either app, for the reason at the top of this file: what has to agree is the
// *document*, and a vector one phone produced would only ever prove the other
// agrees with that phone.
//
// Every row carries one of each awkwardness the format has: a null that must be
// written as an absent key, an enum that travels as its name, and the two fields
// Room packs into strings.
const replica = {
  formatVersion: 1,
  schemaVersion: 16,
  householdId: 'household-vector',
  deviceId: 'device-vector',
  deviceName: "Vector's phone",
  createdAt: 1_757_400_000_000,
  babies: [
    {
      uid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      updatedAt: 1_757_400_000_000,
      name: 'Robin',
      birthDate: 1_755_000_000_000,
      archived: false,
      // feedingReminderEnabled / feedingReminderIntervalMinutes absent: null is
      // written by leaving the key out, never as `null`.
    },
  ],
  feedings: [
    {
      babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      uid: 'c9f0f895-fb98-4b6f-9b0e-1a2b3c4d5e6f',
      updatedAt: 1_757_400_060_000,
      type: 'BREAST',
      side: 'BOTH',
      startTime: 1_757_400_000_000,
      endTime: 1_757_400_780_000,
      leftDurationMs: 540_000,
      rightDurationMs: 240_000,
      // Room packs the segments: SIDE,start,end triples joined by ';'.
      segments: 'LEFT,1757400000000,1757400360000;RIGHT,1757400360000,1757400600000',
    },
  ],
  sleeps: [
    {
      babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      uid: '45c48cce-2e2d-4fbd-aa1f-dd0eaf14a7c9',
      updatedAt: 1_757_403_600_000,
      startTime: 1_757_390_000_000,
      endTime: 1_757_403_600_000,
      position: 'BACK',
      place: 'OTHER',
      placeNote: 'pram',
    },
  ],
  diapers: [
    {
      babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      uid: 'd3d94468-02a4-4cbe-b9dd-e0e5cbb2dbc0',
      updatedAt: 1_757_401_000_000,
      time: 1_757_401_000_000,
      wet: true,
      dirty: true,
      stoolColor: 'YELLOW',
    },
  ],
  growth: [
    {
      babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      uid: '6512bd43-d9ca-4e6f-9b3a-5c8b9f0a1d2e',
      updatedAt: 1_757_402_000_000,
      time: 1_757_402_000_000,
      weightGrams: 4200,
      // heightMm and headMm absent: nobody measured them, which is not zero.
    },
  ],
  treatments: [
    {
      babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
      uid: 'c20ad4d7-6fe9-4779-8c7e-3b1a2f4d5e60',
      updatedAt: 1_757_402_500_000,
      name: 'Vitamin D',
      dose: '400 IU',
      intervalDays: 1,
      // Minutes since midnight, joined by ',' — 09:00 and 21:00.
      timesOfDay: '540,1260',
      startDate: 1_755_000_000_000,
      remindersEnabled: true,
      active: true,
    },
  ],
  pumpings: [
    {
      uid: 'aab32389-8d69-4bc2-9d0c-4e1f2a3b4c5d',
      updatedAt: 1_757_404_000_000,
      time: 1_757_404_000_000,
      amountMl: 120,
      storage: 'FRIDGE',
    },
  ],
  tombstones: [
    {
      uid: '9bf31c7f-f062-4a17-bd23-6a1e7f8c9d0a',
      entity: 'feeding',
      deletedAt: 1_757_405_000_000,
    },
  ],
}

// One row that was soft-deleted, so `deletedAt` is exercised as a value as well
// as an absence.
replica.diapers.push({
  babyUid: '8f14e45f-ea9b-4b3d-9f1a-2c0d3e4f5a6b',
  uid: '1ff1de77-4005-4b9d-b3f1-2c3d4e5f6a7b',
  updatedAt: 1_757_406_000_000,
  deletedAt: 1_757_406_000_000,
  time: 1_757_405_500_000,
  wet: true,
  dirty: false,
})

write('replica.json', {
  note:
    'Decode documentJson and every field must come back as listed. ' +
    'Absent keys are nulls: a writer omits them, a reader treats absent and null alike.',
  formatVersion: 1,
  schemaVersion: 16,
  documentJson: JSON.stringify(replica),
  document: replica,
  rowCount:
    replica.babies.length + replica.feedings.length + replica.sleeps.length +
    replica.diapers.length + replica.growth.length + replica.treatments.length +
    replica.pumpings.length + replica.tombstones.length,
  refuses: [
    { name: 'a newer document format', json: JSON.stringify({ ...replica, formatVersion: 2 }), reason: 'tooNew' },
    { name: 'a newer database schema', json: JSON.stringify({ ...replica, schemaVersion: 99 }), reason: 'tooNew' },
    { name: 'no versions at all', json: JSON.stringify({ householdId: 'x' }), reason: 'unreadable' },
    { name: 'not JSON', json: 'not a replica at all', reason: 'unreadable' },
  ],
})

// ------------------------------------------------------------- nesting depth

const nest = (depth) => '['.repeat(depth) + ']'.repeat(depth)

write('json-depth.json', {
  note: 'Bracket counting, checked before parsing. Brackets inside a double-quoted string do not count.',
  maxJsonDepth: 32,
  cases: [
    { name: 'flat object', text: '{"a":1}', exceeds: false },
    { name: 'exactly at the limit', text: nest(32), exceeds: false },
    { name: 'one past it', text: nest(33), exceeds: true },
    { name: 'brackets inside a string', text: `{"note":"${'['.repeat(64)}"}`, exceeds: false },
    { name: 'escaped quote keeps the string open', text: `{"note":"\\"${'['.repeat(64)}"}`, exceeds: false },
    { name: 'unbalanced closers do not go negative', text: ']'.repeat(64) + nest(33), exceeds: true },
  ],
})

console.log('wrote vectors to', here)
