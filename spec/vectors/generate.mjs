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
