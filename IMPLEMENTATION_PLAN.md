# TAuth — Implementation Plan

A cross-platform desktop TOTP authenticator built on Kotlin Multiplatform and Compose Multiplatform, targeting Linux, macOS and Windows. The vault is encrypted as a whole with a key derived from a master password, which is the sole unlock factor. The vault is unlocked only while the main window is on screen; hiding the window to the system tray destroys the in-memory key material.

Unlocking through platform authenticators — Touch ID, Windows Hello, the freedesktop Secret Service keyring — is specified in §16, Future Improvements. The key hierarchy accommodates it without a file format change.

---

## 1. Scope

### 1.1 In scope

- TOTP code generation per RFC 6238, with HMAC-SHA1 / SHA-256 / SHA-512, 6–8 digits, configurable period.
- HOTP code generation per RFC 4226, counter-based, with a persisted per-entry counter.
- A single encrypted vault file holding all accounts.
- Master password as the sole unlock factor.
- Compose Desktop UI: unlock, account list with live codes, add/edit/delete, settings.
- System tray with minimise-to-tray, and relock of the vault when the window is not on screen.
- Import from `otpauth://` URIs, including QR images.
- Encrypted export and plaintext export of the vault.
- Native packaging as DMG, MSI and DEB.

### 1.2 Out of scope

- OS keyring storage of the encryption key, and unlock via Touch ID, Windows Hello or the Secret Service keyring. Deferred to §16.
- Cloud sync, multi-device sync, or any network communication.
- Mobile (Android/iOS) targets. Code placement keeps them reachable but no mobile target is configured.
- Steam Guard, Authy proprietary, or other non-standard OTP variants.
- Browser extension or CLI companion.

---

## 2. Threat model

### 2.1 Defended against

| Threat                                                        | Defence                                                                                       |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| Vault file copied from disk, backup, or a synced folder       | Whole-file AES-256-GCM; key derived with Argon2id                                             |
| Offline brute force of the master password                    | Argon2id with memory-hard parameters fixed by the format version                              |
| Tampering with any byte of the vault file, including metadata | GCM authentication tag covers the body; the header is bound as associated data                |
| Editing the header to weaken or redirect the unwrap           | The CRC fails before a key is derived; a repaired CRC fails the unwrap or the body's tag      |
| Another user account on the same machine reading the vault    | POSIX mode `0600` / Windows ACL restricted to the owner                                       |
| Shoulder-surfing of an unattended unlocked window             | Relock on hide-to-tray, on minimise, and on idle timeout                                      |
| Silent weakening of the lock policy by editing a config file  | Lock triggers and timeouts live in the vault body, under the GCM tag                          |
| Casual recovery of secrets from a memory dump after locking   | Key material held in `ByteArray`, zeroed on lock; decoded secrets never converted to `String` |

### 2.2 Not defended against

- An attacker with code execution as the same OS user while the vault is unlocked. Key material is in the process heap by necessity.
- A vault replaced by an older copy of itself. Every file TAuth writes stays authentic, so a rollback is indistinguishable from the current vault. Detecting it needs an anchor the attacker cannot reach, which a local installation does not have; §16 records what that would take.
- Kernel-level keyloggers or screen capture.
- Heap contents paged to swap. The JVM does not expose `mlock`; swap encryption is the operating system's responsibility.
- Physical access with the machine unlocked and TAuth's window open.

### 2.3 Stated consequences of the design

- The vault file's entire contents, including issuer names and account labels, are unreadable without the key. Checking whether an account exists requires unlocking.
- The master password is unrecoverable. There is no reset path, no recovery code, and no escrow. Losing it means losing every stored secret, and the create-vault screen says so.
- Whoever can write to the vault file can destroy it. Integrity protection detects tampering; it does not prevent deletion, and TAuth keeps no spare copy. A second file on the same disk survives none of what destroys a vault — a lost machine, a failed disk, a deleted directory — so the backup path is export (§9.9) and it is the user's to take.
- Every unlock costs one Argon2id derivation, roughly 100–250 ms. No key is cached outside the process.

---

## 3. Module and package layout

Two modules: `:shared` (Kotlin Multiplatform, `jvm()` target only) and `:desktopApp` (Kotlin/JVM, Compose application entry point).

Pure algorithmic and model code lives in `shared/src/commonMain`, with platform primitives behind `expect`/`actual`, which keeps an Android or iOS target reachable without restructuring.

```
shared/src/commonMain/kotlin/com/panda/tauth/
  Outcome.kt                  Outcome<T, E>, the carrier for a typed error
  totp/
    Base32.kt                 RFC 4648 base32 encode/decode, padding-optional
    HashAlgorithm.kt          SHA1 | SHA256 | SHA512 enum
    OtpCore.kt                RFC 4226 §5.2 HMAC and §5.3 truncation, shared by both types
    Hotp.kt                   counter moving factor
    Totp.kt                   RFC 6238 time-step moving factor
    OtpAuthUri.kt             otpauth:// parse and build
    PercentCodec.kt           RFC 3986 percent encode/decode
    EnumParsing.kt            case-insensitive enum lookup
    TotpCode.kt               code string + validity window
  crypto/
    Aead.kt                   expect: AES-256-GCM seal/open
    Kdf.kt                    expect: Argon2id
    Hmac.kt                   expect: HMAC-SHA1/256/512
    SecureRandom.kt           expect: CSPRNG bytes
    SecureBytes.kt            zeroable byte holder, AutoCloseable
    Base64Codec.kt            base64 over kotlin.io.encoding
    Crc32.kt                  expect: CRC32 of the header bytes
  vault/
    VaultEntry.kt             serializable entry model
    VaultBody.kt              serializable decrypted body
    VaultHeader.kt            serializable plaintext header
    VaultFormat.kt            byte-layout constants and the JSON configuration
    VaultCodec.kt             file <-> (header, body) encode/decode
    VaultError.kt             sealed error hierarchy
  session/
    SessionState.kt           Locked | Unlocked
    LockReason.kt             enum of relock triggers
  settings/
    Preferences.kt            plaintext model, readable before unlock
    SecurityPolicy.kt         encrypted model, carried in the vault body

shared/src/jvmMain/kotlin/com/panda/tauth/
  crypto/
    Aead.jvm.kt               javax.crypto AES/GCM/NoPadding
    Kdf.jvm.kt                BouncyCastle Argon2BytesGenerator
    Hmac.jvm.kt               javax.crypto.Mac
    SecureRandom.jvm.kt       java.security.SecureRandom
    Crc32.jvm.kt              java.util.zip.CRC32
  vault/
    VaultPaths.kt             per-OS vault directory resolution
    VaultStore.kt             atomic read/write, file locking, permissions
  settings/
    PreferencesStore.kt       plaintext JSON file; `SecurityPolicy` has no store
                              of its own and is read and written with the vault

shared/src/commonMain/kotlin/com/panda/tauth/ui/
  TAuthApp.kt                 root composable, routes on session state
  theme/                      Material 3 colour scheme, typography
  unlock/UnlockScreen.kt
  list/AccountListScreen.kt
  list/AccountRow.kt          code display, countdown ring, copy affordance
  edit/AddAccountScreen.kt
  edit/EditAccountScreen.kt
  settings/SettingsScreen.kt
  components/                 shared widgets

desktopApp/src/main/kotlin/com/panda/tauth/
  Main.kt                     application scope, window, tray, lifecycle
  TrayAvailability.kt         whether this desktop has a tray
  WindowLifecycle.kt          close and startup behaviour that follows
  TrayHost.kt                 tray construction
  SingleInstance.kt           lock file + local socket
  ClipboardService.kt         copy with timed clear
  QrDecoder.kt                ZXing image decode
  QrEncoder.kt                ZXing BitMatrix generation and PNG export
```

---

## 4. Dependencies

### 4.1 `gradle/libs.versions.toml` additions

```toml
[versions]
bouncycastle = "1.85"
kotlinx-serialization = "1.11.0"
kotlinx-datetime = "0.8.0"
zxing = "3.5.3"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }
bouncycastle-prov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
zxing-javase = { module = "com.google.zxing:javase", version.ref = "zxing" }

[plugins]
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

These versions compile together against Kotlin 2.4.10 (§15). The serialization Gradle plugin takes `version.ref = "kotlin"` because it ships with the compiler; the runtime library versions are independent of it.

From kotlinx-datetime 0.8.0, `Instant` and `Clock` are `kotlin.time` types in the standard library rather than `kotlinx.datetime` types. kotlinx-datetime supplies the calendar types built on them — `LocalDateTime`, `TimeZone`, `LocalDate` — and the plan imports each from the package that owns it.

BouncyCastle is used only for Argon2id via `Argon2BytesGenerator`, which is a lightweight-API class requiring no JCE provider registration. AES-GCM, HMAC and `SecureRandom` come from the JDK.

### 4.2 `shared/build.gradle.kts`

Add the serialization plugin, then:

```kotlin
commonMain.dependencies {
    // Compose dependencies as currently declared
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
}
jvmMain.dependencies {
    implementation(libs.bouncycastle.prov)
}
commonTest.dependencies {
    implementation(libs.kotlin.test)
}
```

### 4.3 `desktopApp/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
}
```

ZXing is used in both directions: `core` provides `MultiFormatReader` for QR import (§9.5) and `QRCodeWriter` for QR display (§9.7); `javase` provides `BufferedImageLuminanceSource` for decoding image files and `MatrixToImageWriter` for PNG export.

`nativeDistributions` sets `includeAllModules = true`, which trades installer size for immunity to jlink stripping failures. Narrowing to an explicit `modules("java.naming", "java.management", "jdk.crypto.ec")` list is listed in §16.8.

---

## 5. OTP core

HOTP and TOTP are two moving factors over one core. `OtpCore` holds the HMAC and truncation of §5.2 and the digit bounds, and both types call it. They differ in where the moving factor comes from — a counter the client holds, or the clock — and in whether generating a code has a side effect.

### 5.1 Base32

RFC 4648 alphabet `ABCDEFGHIJKLMNOPQRSTUVWXYZ234567`. The decoder accepts input with or without `=` padding, ignores ASCII whitespace, and accepts lowercase by upper-casing before lookup. Invalid characters produce `VaultError.InvalidSecret`. The decoder returns `ByteArray`; the encoder is needed only for export and produces unpadded output.

Padding is optional but not free-form: an `otpauth://` secret carries none, and input that does carry padding must carry the right amount. RFC 4648 §6 pads to a multiple of eight characters, so the count follows from the number of data symbols and a group needing none can carry none. A wrong count means characters were lost, which is the case padding exists to reveal.

Secret length is not constrained by the format. RFC 4226 recommends at least 128 bits and 160 bits is the norm; TAuth accepts any non-empty decoded secret and displays a warning in the UI for secrets shorter than 16 bytes.

### 5.2 HOTP truncation (RFC 4226 §5.3)

```
hs      = HMAC(algorithm, key, counterBytes)          // counterBytes = 8-byte big-endian
offset  = hs[hs.length - 1] and 0x0F
binary  = ((hs[offset]     and 0x7F).toInt() shl 24) or
          ((hs[offset + 1] and 0xFF).toInt() shl 16) or
          ((hs[offset + 2] and 0xFF).toInt() shl  8) or
          ((hs[offset + 3] and 0xFF).toInt())
code    = binary % 10^digits
```

The result is left-padded with zeros to `digits` characters.

### 5.3 TOTP time step (RFC 6238 §4.2)

```
T = floor((currentUnixSeconds - T0) / X)
```

with `T0 = 0` and `X` the entry's `period`. `T` is encoded as a **64-bit big-endian** integer. RFC 6238 errata 8672 records that treating `T` as 32-bit introduces a year-2038 defect; the implementation uses `Long` throughout and the test suite includes the `20000000000` vector specifically to exercise values beyond 32 bits.

Time source is the system clock via `kotlin.time.Clock.System`. A `Clock` is injected into `TotpGenerator` so tests can supply fixed instants. No NTP correction is performed; a settings screen note explains that a system clock skewed by more than the period will produce rejected codes.

### 5.4 Test vectors

#### HOTP (RFC 4226 Appendix D)

Secret is the ASCII string `12345678901234567890` (20 bytes), HMAC-SHA1, 6 digits, counter 0 through 9.

| Counter | Code | Counter | Code |
|---|---|---|---|
| 0 | 755224 | 5 | 254676 |
| 1 | 287082 | 6 | 287922 |
| 2 | 359152 | 7 | 162583 |
| 3 | 969429 | 8 | 399871 |
| 4 | 338314 | 9 | 520489 |

Feeding counter `floor(t / 30)` into the same code path must reproduce the TOTP vectors below, which is the check that the two types share one implementation rather than two.

#### TOTP (RFC 6238 Appendix B)

All vectors use `T0 = 0`, `X = 30`, and **8 digits**. RFC 6238 errata 2866 (verified) corrects the specification text, which claims a single shared secret: the reference implementation uses a distinct seed per algorithm. Errata 5132 restates the same defect. The seeds are ASCII strings:

- SHA-1: `12345678901234567890` (20 bytes)
- SHA-256: `12345678901234567890123456789012` (32 bytes)
- SHA-512: `1234567890123456789012345678901234567890123456789012345678901234` (64 bytes)

| Time (s) | UTC | T (hex) | SHA-1 | SHA-256 | SHA-512 |
|---|---|---|---|---|---|
| 59 | 1970-01-01 00:00:59 | 0000000000000001 | 94287082 | 46119246 | 90693936 |
| 1111111109 | 2005-03-18 01:58:29 | 00000000023523EC | 07081804 | 68084774 | 25091201 |
| 1111111111 | 2005-03-18 01:58:31 | 00000000023523ED | 14050471 | 67062674 | 99943326 |
| 1234567890 | 2009-02-13 23:31:30 | 000000000273EF07 | 89005924 | 91819424 | 93441116 |
| 2000000000 | 2033-05-18 03:33:20 | 0000000003F940AA | 69279037 | 90698825 | 38618901 |
| 20000000000 | 2603-10-11 11:33:20 | 0000000027BC86AA | 65353130 | 77737706 | 47863826 |

### 5.5 `otpauth://` URI format

Per the Google Authenticator Key Uri Format specification:

```
otpauth://totp/LABEL?secret=SECRET&issuer=ISSUER&algorithm=ALG&digits=D&period=P
```

Parsing rules:

- **Type** — the authority component. `totp` and `hotp` are accepted. Any other value is `VaultError.MalformedUri`.
- **Label** — percent-decoded path, leading `/` stripped. If it contains a `:` (or its percent-encoded `%3A`), the portion before the first colon is the issuer prefix and the remainder, with surrounding whitespace trimmed, is the account name. Otherwise the whole label is the account name.
- **secret** — required, base32, padding optional. Absent, undecodable, or decoding to no bytes at all produces `VaultError.InvalidSecret`. Whitespace and padding decode to nothing, so a non-empty secret can still carry no key.
- **issuer** — optional. When both the `issuer` parameter and an issuer label prefix are present and differ, the `issuer` parameter wins and the discrepancy is surfaced in the import preview.
- **algorithm** — optional, one of `SHA1`, `SHA256`, `SHA512`, case-insensitive. Default `SHA1`.
- **digits** — optional integer. Accepted range 6–8. Default 6. Values outside the range are rejected rather than clamped.
- **period** — optional integer seconds, `totp` only, at least 1 with no upper bound. Default 30. Present on a `hotp` URI it is ignored.
- **counter** — required for `hotp`, rejected on `totp`. Unsigned 64-bit initial counter value. Absent on a `hotp` URI it is `VaultError.MalformedUri`; unlike every other parameter it has no default, because a wrong starting counter yields codes the server will not accept.

Unknown query parameters are ignored and not preserved.

Space, tab, carriage return and newline are shed from the ends of the input, which is what a paste from a chat window or a wrapped mail carries. One of those four surviving in the query makes the URI `VaultError.MalformedUri`: the query is where a wrapped paste is taken in silently, since base32 skips whitespace inside a secret and a parameter name carrying whitespace is an unknown parameter and ignored. The label carries its own whitespace raw, which is how issuers write it — `otpauth://totp/ACME Corp:alice@acme.com?secret=...` — and it is unambiguous there, since the label runs to the `?` and every character of it is part of a name.

The type and the algorithm are matched by ASCII case alone. Unicode case folding maps U+017F LATIN SMALL LETTER LONG S onto `S`, which would read `algorithm=%C5%BFHA256` as SHA-256 from a character the grammar's VCHAR has no room for.

URI construction for export percent-encodes the label as `issuer:accountName`, emits `issuer` as an explicit parameter, and omits `algorithm`, `digits` and `period` when they hold default values. A `hotp` URI always carries `counter`, holding the entry's current value at the moment the URI is built.

Construction applies the parser's rules to its own arguments, so `parse(build(x)) == x` for every value the constructor accepts: the secret decodes to a key, the issuer and account name are well-formed UTF-16, the account name carries no colon, and an absent issuer is null rather than empty. A lone surrogate has no UTF-8 encoding and so cannot be percent-encoded at all. The last two rules are what the round trip rests on for those values: a colon in the account name builds a label that reads back as a different account under an issuer nobody entered, and an empty issuer builds `&issuer=`, which reads back as no issuer.

### 5.6 HOTP counter semantics

The counter is stored per entry and advances only when the user asks for a code. `TotpGenerator` derives its counter from the clock and holds no state; `HotpGenerator` reads and advances state, which makes code generation a write.

**Ordering.** Generating an HOTP code persists the incremented counter to the vault *before* the code reaches the screen. The reverse order allows a crash between display and write to leave the stored counter behind the code already shown, and reissuing that code trips replay rejection on any server that tracks consumed counters. The cost of the chosen order is a skipped counter value when a write succeeds and the user never uses the code, which the server's look-ahead window absorbs.

**Write volume.** Each HOTP code view rewrites the whole vault: fresh nonce, re-encrypt, atomic rename, fsync, per §6.6. TOTP entries cause no writes at all. A vault of HOTP entries is therefore materially more write-heavy than one of TOTP entries.

**Drift.** RFC 4226 increments the client counter on every code request and the server counter only on successful authentication, so any code generated and not submitted moves the two apart. Servers absorb this with a look-ahead window of `s` values; beyond it, authentication fails until the counter is reset. Two consequences for the UI: the current counter value is visible on the entry (§9.6), and it is editable so a user told "your token is out of sync" can correct it without deleting and re-adding the account.

**Codes do not expire.** An HOTP code stays valid at the server until consumed or superseded, so it carries no countdown and is not recomputed on a timer (§8.5).

---

## 6. Vault file format

### 6.1 Location

| OS | Path |
|---|---|
| Linux | `${XDG_DATA_HOME:-$HOME/.local/share}/tauth/vault.tauth` |
| macOS | `~/Library/Application Support/TAuth/vault.tauth` |
| Windows | `%APPDATA%\TAuth\vault.tauth` |

`XDG_DATA_HOME` and `%APPDATA%` are used only when they name an absolute path; the XDG Base Directory Specification requires a relative value be treated as invalid and ignored, and the same applies to `%APPDATA%` for the same reason. A location that still comes out relative — an empty `user.home` leaves every branch above relative — is refused rather than resolved against the working directory, which would put the vault wherever the application happened to be launched from and leave the next launch unable to find it.

Settings are split by whether the application must read them before the vault is open.

`preferences.json` sits beside the vault in plaintext and holds what is needed to draw the window and build the tray before any password is entered: theme, window geometry and position, start-minimised, minimise-to-tray, list sort order. It contains no secrets and nothing that governs when the vault locks.

Everything that governs locking travels inside the encrypted body as `SecurityPolicy` (§6.4): idle timeout, lock-on-minimise, lock-on-focus-loss, hide grace period and clipboard clear delay. These are read only while the vault is unlocked, so placing them inside creates no chicken-and-egg problem, and it puts them under the GCM tag where an edit is detected rather than obeyed. A plaintext idle timeout is a file an attacker can rewrite to disable the control that §8.3 exists to provide.

Minimise-to-tray stays in plaintext because it is not a security control: with the tray it hides and locks, without the tray it exits and locks.

A `--vault <path>` command-line argument overrides the resolved path, for testing and for users keeping the vault on removable media.

### 6.2 Byte layout

```
offset  size      content
0       5         magic, ASCII "TAUTH"
5       1         format version, currently 0x01
6       4         headerLength, unsigned 32-bit big-endian
10      4         CRC32 of bytes 0-9 and the header JSON, unsigned 32-bit big-endian
14      headerLength
                  header JSON, UTF-8, no trailing newline
14+hl   remainder ciphertext concatenated with the 16-byte GCM tag
```

The associated data for the body's AEAD operation is the **entire prefix**: bytes `0` through `14 + headerLength - 1` inclusive. It binds the body to the header it was written under, so a header lifted from another vault fails the body's authentication even though that header is internally consistent.

The CRC covers the ten bytes ahead of it as well as the header JSON, and is checked before any key is derived, which is what separates a damaged file from a mistyped password (§6.7). Those ten bytes decide what the reader takes the file to be and where it believes the header ends, so they are checked by the same step that checks the header: a damaged version byte is reported as damage rather than as a vault from a TAuth that does not exist, and a corrupted `headerLength` fails whether or not the bytes it slices happen to parse. The CRC is unkeyed and so detects damage rather than tampering; tampering is the GCM tag's job.

The layout of those ten bytes is fixed for every format version. A reader can therefore check the preamble of a file written by a version it does not know before reporting that it cannot read it.

### 6.3 Header JSON

```json
{
  "v": 1,
  "vaultId": "<base64, 16 bytes>",
  "salt": "<base64, 16 bytes>",
  "wrap": {
    "nonce": "<base64, 12 bytes>",
    "ct": "<base64, 48 bytes>"
  },
  "body": {
    "nonce": "<base64, 12 bytes>"
  }
}
```

`wrap.ct` is 48 bytes: the 32-byte DEK encrypted under the KEK plus the 16-byte GCM tag. The wrap operation uses an empty associated data field.

`salt` is the only part of the derivation the file carries, because it is random per vault and cannot be derived from anything else. The function, its version, the lane count and the cost are all fixed by the format version `v` (§6.5), so the header holds no copy of them: a stored copy could only ever disagree with the version that implies it, and a cost read from a plaintext header would be an allocation of the attacker's choosing.

`vaultId` is a random 16-byte identifier generated at vault creation. It binds keyring entries to a specific vault (§16.3); the password-only design does not read it.

The header is serialised with `kotlinx.serialization` using `encodeDefaults = true`, `explicitNulls = false`, and no pretty-printing, so that byte-for-byte reproduction is deterministic. The exact header bytes read from disk are retained in memory and reused as associated data rather than being re-serialised, which removes any dependence on serialiser stability.

Deserialisation must be tolerant of unknown keys (`ignoreUnknownKeys = true`), so that a vault written by a later version carrying additional header fields still fails cleanly on the version check rather than on a parse error.

### 6.4 Body plaintext JSON

```json
{
  "v": 1,
  "policy": {
    "idleTimeoutMinutes": 5,
    "lockOnMinimise": true,
    "lockOnFocusLoss": false,
    "hideGraceSeconds": 0,
    "clipboardClearSeconds": 20
  },
  "entries": [
    {
      "id": "0192f4c1-...",
      "type": "totp",
      "issuer": "GitHub",
      "accountName": "user@example.com",
      "secret": "JBSWY3DPEHPK3PXP",
      "algorithm": "SHA1",
      "digits": 6,
      "period": 30,
      "counter": null,
      "createdAt": "2026-08-13T09:41:12Z",
      "orderIndex": 0
    }
  ]
}
```

`id` is a UUIDv7 rendered in canonical form, giving creation-ordered identifiers. `orderIndex` holds explicit user ordering; it is renumbered densely from zero on every write. `secret` is the base32 string exactly as imported, not the decoded bytes, so that a round-trip export reproduces the original URI.

`type` is `totp` or `hotp`. `period` applies to `totp` and is null on `hotp`; `counter` applies to `hotp` and is null on `totp`. The pairing is enforced on deserialisation, and a `hotp` entry with a null counter is `VaultError.Corrupt` rather than a silent default to zero, which would generate codes from the wrong position.

An entry meets the rules §5.5 places on a URI: `secret` is base32 that decodes to at least one byte, `issuer` and `accountName` are well-formed UTF-16, `accountName` carries no colon, and `issuer` is absent rather than empty. The body is attacker-writable and JSON carries an unpaired surrogate through as readily as any other escape, so a body failing any of them is `VaultError.Corrupt` at the read rather than a failure at the first code the entry is asked for or a throw out of the URI constructor when the entry is exported.

`policy` carries the security settings described in §6.1. Absent fields take the defaults shown, so a body written before a field existed opens unchanged; an absent `policy` object is the full default set. Defaults are the conservative value in every case, so a truncated or partially-understood policy locks sooner rather than later. Changing a policy value is an ordinary vault write and therefore requires an unlocked session.

The three durations are rejected when negative, which makes the body `VaultError.Corrupt`. Zero disables a control and is a choice the user can make; a negative reads as disabled to every check while naming a duration, so it would switch a control off in a body that appears to set it.

### 6.5 Cryptographic parameters

| Purpose | Algorithm | Parameters |
|---|---|---|
| Password → KEK | Argon2id | version 0x13 (19), m = 65536 KiB (64 MiB), t = 3, p = 1, 16-byte random salt, 32-byte output |
| KEK → DEK wrap | AES-256-GCM | 12-byte random nonce, 128-bit tag, empty AAD |
| DEK → body | AES-256-GCM | 12-byte random nonce, 128-bit tag, AAD = file prefix |
| Random material | `java.security.SecureRandom` | default provider, no seeding |

Argon2id parameters exceed the OWASP minimum of m = 19456 KiB, t = 2, p = 1. On the reference machine (§15) the chosen parameters cost about 175 ms:

| Parameters | Median of three runs |
|---|---|
| m = 19456, t = 2, p = 1 — OWASP minimum | 22 ms |
| m = 47104, t = 1, p = 1 — OWASP alternative | 44 ms |
| m = 65536, t = 2, p = 1 | 118 ms |
| **m = 65536, t = 3, p = 1 — chosen** | **175 ms** |
| m = 131072, t = 3, p = 1 | 339 ms |
| m = 262144, t = 4, p = 1 | 928 ms |

The budget is set by §8.3 rather than by the unlock screen: hiding to the tray discards the key, so a derivation is paid every time the window comes back. A low-end laptop runs 2–3× slower than the reference machine, which puts these parameters at 400–500 ms there and 64 MiB at the ceiling of what the lock lifecycle can afford.

The parameters belong to format version 1 rather than to the individual vault, and the file records none of them. Strengthening them is a format version change: each reader uses the parameters its version names, so a vault written under version 1 keeps opening at version 1's cost. Nothing in the derivation is attacker-writable and there is no parameter to validate on read.

The two-level hierarchy — password to KEK to DEK to body — makes password change an O(1) header rewrite rather than a full re-encryption, and is the structure the keyring path in §16 attaches to. Replacing the DEK is a separate operation (§7.1), because a password change alone leaves a leaked DEK working.

**Nonce discipline.** A fresh 12-byte nonce is generated for the body on every write and for the wrap on every KEK change. Reusing a nonce with the same key across two plaintexts breaks GCM completely, so the rule is structural rather than procedural: every nonce is drawn inside `VaultCodec` and no function it calls accepts one from outside it.

### 6.6 Write procedure

1. Serialise the body to JSON bytes.
2. Generate a fresh body nonce.
3. Build the header JSON with the wrap block and the new body nonce.
4. Assemble the prefix (magic, version, length, CRC, header) and use it as AAD.
5. Encrypt the body under the DEK.
6. Take the lock.
7. Write prefix and ciphertext to `vault.tauth.tmp` in the same directory. On POSIX the owner-only mode is a creation attribute and is read back before any ciphertext is written; elsewhere the file is created with no mode of its own and the directory's inheritable access control entry is what restricts it.
8. `FileChannel.force(true)` on the temp file.
9. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
10. Force the parent directory's channel, so the rename itself is durable and not only the bytes it renames.

The rename at step 9 is the commit point: a reader sees the previous file whole or the new one whole, never a mixture, and no spare copy is needed to make that true. Where `ATOMIC_MOVE` is unsupported the move falls back to `REPLACE_EXISTING` and logs a warning.

A save that fails at step 7 or 8 deletes `vault.tauth.tmp`: it holds part of a file nothing can read, and the vault file has not been touched. A save that fails at step 9 leaves it in place and logs its path, because it holds the whole new vault and, where the rename was not atomic, can be the only complete copy of it.

Steps 1 to 5 refuse a body the read procedure would reject: a `v` the reader does not support, a header past the 64 KiB bound of §6.7, or a file past the 16 MiB whole-file ceiling. No version and no size reaching step 7 is one that step 9 would commit as a vault this version cannot open.

Durability across a power cut is best effort and is not reported: on macOS, from JDK 21, `FileChannel.force` is `fcntl(fd, F_FULLFSYNC)` for a local mount and plain `fsync` for any other (JDK-8080589), and `fsync` returns before the drive empties its write cache; a network store can acknowledge a flush its server has not performed; Windows will not open a directory as a channel.

The lock at step 6 is an exclusive `FileLock` on a sibling `vault.lock`. It is advisory across processes on the same machine and stops two instances, or an instance and an export, from interleaving writes. It is a separate file so that locking never opens the vault for writing. `vault.lock` is opened without following links, so a symbolic link left at that name opens nothing and, where it dangles, creates nothing.

Ahead of the lock, the directory holding the vault is restricted to its owner. On POSIX it is created at `0700` and it alone: a creation attribute reaches every parent `createDirectories` makes, and those parents are the data root shared with every other application, so they keep the mode a directory is created with. A directory that is anything other than `0700` is chmodded to it and the mode is then read back, so a mount that accepts a chmod and discards it produces `VaultError.Io` instead of a success that leaves the directory traversable. A directory reached through a symbolic link is left as it is found, because a chmod through the link tightens whatever it points at; the mode read back is that of the directory the link resolves to. A data directory linked onto another disk at `0700` is therefore written to, and one linked onto a directory anyone can traverse produces `VaultError.Io` and no write.

Elsewhere the directory is created with no mode of its own and its access control list is set to a single ALLOW entry for the owner, carrying `FILE_INHERIT` and `DIRECTORY_INHERIT`, which a file created inside the directory inherits. A path with no access control view produces `VaultError.Io`. Nothing is read back, and the entry is written through a symbolic link to whatever the link resolves to, so the mode-discarding guarantee and the symbolic-link exemption above hold on POSIX alone.

Reading is not gated on the directory's mode: a vault already there stays readable.

### 6.7 Read procedure

1. Read the whole file into memory through one open descriptor, whose size decides the ceiling, so that the file measured is the file read. A file past the 16 MiB whole-file ceiling produces `VaultError.Corrupt` before the read is attempted, since a hostile size raises `OutOfMemoryError` rather than an exception that can be caught and converted.
2. Verify the magic. A file that does not carry it produces `VaultError.Corrupt`.
3. Parse `headerLength` and slice the header JSON. A length exceeding the file size, or exceeding a 64 KiB sanity bound, produces `VaultError.Corrupt`.
4. Verify the CRC over bytes `0`–`9` and the header JSON. A mismatch produces `VaultError.Corrupt`, before any key is derived.
5. Verify the format version. An unknown version produces `VaultError.UnsupportedVersion`.
6. Read the header's `v`, then deserialise the header. A `v` other than the supported header version produces `VaultError.UnsupportedVersion`.
7. Derive the KEK from the supplied password and the salt, then unwrap the DEK.
8. Decrypt the body with the retained prefix as AAD. An authentication failure produces `VaultError.IntegrityFailure`, which the UI reports as tampering or corruption rather than as a wrong password.
9. Read the body's `v`, then deserialise the body. A `v` other than the supported body version produces `VaultError.UnsupportedVersion`.

`v` is read out of a document on its own, ahead of the rest of that document, because only the version says what the rest of it means: a later version is free to give a field a type this one never used, and deserialising those fields under this version's model would report a vault from a later TAuth as damage.

The parser takes a quoted integer wherever this format specifies a number, so a `v` of `"1"` reads as version 1 on the header path and on the body path alike, and an entry's `period`, `digits` and `orderIndex` read the same way quoted. The two reads of `v` — the standalone one and the model's — go through the same parser and so agree on the value; the writer emits a number.

A document that will not deserialise produces `VaultError.Corrupt` naming which document it was, and nothing out of the document itself. A parser reports the input it stopped in, and that input is the salt with the wrapped DEK on the header path and every entry's secret on the body path.

The order of steps 4 and 5 is what separates the three failures a user can act on. GCM reports a wrong key and a rewritten ciphertext identically, so without the checksum first, damage to the salt or the wrap block would surface as a wrong password. With it, damage to the header fails at step 4 as `Corrupt`, damage to the body fails at step 8 as `IntegrityFailure`, and a version byte the writer never wrote is damage rather than a release that does not exist.

`WrongPassword` at step 7 therefore means the password is wrong, or the salt or wrap block was rewritten by someone who repaired the unkeyed checksum too. Those two are not separable, so the message says the password did not work and claims nothing about the file.

### 6.8 Vault creation

On first run, or when the resolved path holds no file, the UI presents the create flow. Creation generates a 16-byte salt, a 16-byte vault id, and a 32-byte DEK from `SecureRandom`, derives the KEK from the chosen password, wraps the DEK, and writes an empty entry list. The vault file exists before the user adds any account, so that a failure to write is surfaced immediately rather than after they have entered a secret.

---

## 7. Key hierarchy and vault operations

```
                    ┌──────────────────┐
  master password ──┤ Argon2id + salt  ├──► KEK (32 B)
                    └──────────────────┘        │
                                                │ AES-256-GCM unwrap
                                                ▼
                                            DEK (32 B) ──► AES-256-GCM ──► vault body
```

Argon2id derivation runs off the UI thread on `Dispatchers.Default`. The unlock screen shows an indeterminate progress indicator, since Argon2 exposes no progress callback.

### 7.1 Operations

**Unlock.** Derive KEK, unwrap DEK, decrypt body, decode secrets into `SecureBytes`. The KEK is zeroed immediately after the unwrap; only the DEK is retained for the session.

**Change password.** Requires an unlocked session and re-entry of the current password, which is verified by a fresh derivation and unwrap rather than by comparing against session state. Generates a new 16-byte salt, derives a new KEK from the new password, re-wraps the existing DEK, and rewrites the file with a fresh body nonce. The body plaintext is unchanged; the DEK is unchanged.

**Rotate DEK.** Offered in settings as "re-encrypt vault". Generates a new DEK, re-encrypts the body under it, and re-wraps it under the existing KEK. This is the recovery action after a suspected DEK compromise, and the migration path once §16 lands and a keyring copy of an old DEK must be invalidated.

Strengthening the Argon2 parameters is a format version change (§6.5) rather than an operation, so there is no re-derivation action and no settings control for one.

---

## 8. Session and lock lifecycle

### 8.1 State

```kotlin
sealed interface SessionState {
    data object NoVault : SessionState                    // no file at the resolved path
    data class Locked(val lastReason: LockReason?) : SessionState
    data object Unlocking : SessionState
    data class Unlocked(val entries: List<VaultEntry>) : SessionState
}
```

`VaultSession` owns a `MutableStateFlow<SessionState>` and holds the DEK in a private `SecureBytes`. The DEK is never exposed through the public API; callers request operations, not keys.

### 8.2 Lock

```kotlin
fun lock(reason: LockReason) {
    dek?.destroy()          // fill with zeros, then drop the reference
    dek = null
    decodedSecrets.forEach { it.destroy() }
    decodedSecrets.clear()
    codeTickerJob?.cancel()
    clipboard.clearIfHoldsOwnValue()
    _state.value = SessionState.Locked(reason)
}

fun scheduleLock(reason: LockReason)
fun cancelScheduledLock()
```

`scheduleLock` reads the grace period from the `SecurityPolicy` the session holds, and whether the reason is armed at all; a zero grace period locks at once. It is a no-op against an already-locked vault, so a caller never has to ask what state the session is in. `cancelScheduledLock` drops a pending timer without locking. Both are on the session rather than on the window because the policy lives in the unlocked body and is unreadable exactly when it is irrelevant.

### 8.3 Lock triggers

| Trigger | Source | Default |
|---|---|---|
| Window hidden to tray | `onCloseRequest` setting `visible = false` | always |
| Window minimised | `WindowState.isMinimized` observed via `snapshotFlow` | on |
| Explicit "Lock now" | in-app button and tray menu item | always |
| Idle timeout while visible | no pointer or key input for N minutes | on, 5 min |
| Application exit | shutdown hook | always |
| Window focus lost | `LocalWindowInfo.current.isWindowFocused` | **off** |

Every configurable trigger reads from the `SecurityPolicy` in the unlocked vault body, never from `preferences.json`. The policy is available whenever it is needed, since a trigger can only fire against an unlocked vault, and it is unavailable exactly when it is irrelevant. Editing the plaintext preferences file cannot extend a timeout or disable a trigger.

Focus loss defaults to off: copying a code and switching to a browser is the application's most common interaction, and locking on focus loss makes every such action cost a full Argon2id re-derivation.

Hiding to the tray carries no switch, only the grace period below. It is what §1 means by the vault being unlocked while the window is on screen, and nothing else would catch a window that stayed hidden: the idle timeout runs while the window is visible, so a hidden window that did not lock would hold the key until the process ended.

A configurable grace period delays the hide-triggered and minimise-triggered lock. Default is 0 seconds, meaning immediate. Options are 0 / 30 s / 2 min. The grace timer is cancelled if the window becomes visible again before it fires. The timer runs as a cancellable coroutine on the application scope, not as a `java.util.Timer`, so that shutdown cancels it deterministically.

### 8.4 Handling of decoded secret material

Base32-decoded secrets are held as `SecureBytes` and are never converted to `String`. `SecureBytes.destroy()` fills the backing array with zeros and marks the holder unusable; a later lend refuses to run its block and returns `null`, which the vault path reports as `VaultError.VaultClosed`.

JVM `String` instances are immutable and cannot be wiped, and this boundary is **not** enforced by the API. `VaultEntry.secret` holds the base32 text as a `String`, `OpenVault` retains the whole `VaultBody`, and kotlinx's lexer materialises every string token before any deserialiser sees it, so each stored secret exists as an unwipeable `String` for as long as the vault is open. The rule above forbids a *decoded* secret in a `String`, and the base32 text passes it by the letter while being the credential in full. Closing the gap belongs to the session (§14, M3): decode each secret into `SecureBytes` on unlock and drop the body, so that the text exists only between the parse and that decode. Until then a heap dump of an unlocked vault yields every secret in encoded form.

`SecureBytes.adopt` is the only constructor and it takes ownership of the array, so a caller cannot hold a second reference by accident. The destroyed flag is `@Volatile`, written after the zeroing and read before the array, so a thread that observes the flag also observes the zeroed bytes rather than a cached view of a live key. Nothing zeroes a holder that is dropped without `destroy()`; the discipline is the API contract, not a collector hook.

Key material is lent to a block rather than handed out: the lend is the only member that gives a caller the array, and `destroy()` and that block exclude each other. What the lend guarantees is that no `destroy()` runs while the block holds the array, not that the block cannot keep the array past its own return; a block that keeps it holds bytes a later `destroy()` zeroes under it. A lock arriving during a write waits for that write to finish, and a write beginning after the lock finds the key gone and fails. Zeros reaching a seal part-way through it would leave a body encrypted under them beside a header carrying the real wrapped key, and the rename would commit that over the previous file. The common standard library offers atomics but no mutex, so the exclusion is an `expect`/`actual` primitive in `crypto` like the other platform primitives.

The master password is handled as `CharArray` from the text field through to the KDF call, and zeroed after derivation. Compose's `TextField` state is `String`-based, so this boundary is imperfect: a `BasicTextField` with a custom `CharArray`-backed state holder is the conforming approach and is used on the unlock and create screens.

Generated six-to-eight digit codes are `String`. They are short-lived, low-value, and needed as `String` for display and clipboard.

This reduces but does not eliminate residue. A heap dump taken while unlocked contains everything; one taken after locking contains the zeroed arrays plus whatever the garbage collector has not yet reclaimed of transient `String` instances.

The KDF adds residue that TAuth cannot reach. `Argon2BytesGenerator` zeroes its memory blocks when it returns each to the block pool, and leaves three things for the collector: the UTF-8 encoding it makes of the password `CharArray`, the 72-byte H0 prehash seeds, and the 1024-byte scratch block holding the last block digested. H0 is enough to finish the derivation without the password, so it is worth as much as the key it produces. All three are locals of the generator, so the exposure lasts from the derivation until the collector reclaims them.

### 8.5 Code ticker

While unlocked and the window is visible, a single coroutine emits on a one-second cadence, computing every visible TOTP entry's current code and the seconds remaining in its period. Entries scrolled out of view are not computed. The ticker is cancelled on lock and on window hide, so a hidden window consumes no CPU.

HOTP entries are outside the ticker entirely. Their codes change only on explicit request (§5.6), and recomputing one on a timer would advance the counter without the user asking.

Each row shows the remaining fraction of its own period, since entries may have different periods. The countdown ring turns amber in the final five seconds. When a period boundary crosses, the new code replaces the old with a brief crossfade rather than an abrupt swap.

---

## 9. User interface

**Secret disclosure gate.** Three actions put a shared secret where something other than TAuth can read it: copy `otpauth://` URI (§9.4), show QR code (§9.7), and plaintext export (§9.9). Each emits a complete credential, and the medium — clipboard, screen, file — is outside the vault's protection once the action completes. All three carry the same gate: re-entry of the master password even when the session is unlocked, and a one-line statement of what is about to leave the vault. Copying a generated code is not in this set; a code expires or is consumed, a secret does not.

### 9.1 Navigation

A single window. Routing is driven by `SessionState`, not by a navigation library:

- `NoVault` → create-vault screen
- `Locked` / `Unlocking` → unlock screen
- `Unlocked` → account list, with add / edit / settings presented as full-screen destinations within the unlocked graph

### 9.2 Create vault

Master password field with confirmation and a strength meter. A prominent, non-dismissable note states that the master password cannot be recovered and that losing it means losing every stored secret. The note is acknowledged with a checkbox before the create button enables.

Minimum password length is 8 characters, enforced. The strength meter is advisory and based on length, character-class diversity, and a check against a small embedded list of common passwords; it never blocks submission above the minimum length.

### 9.3 Unlock

Password field with a reveal toggle, auto-focused. Enter submits. The Argon2id derivation blocks the button and shows a progress indicator for its duration.

Failed attempts show an inline error. There is no attempt counter and no lockout: the vault is a local file, so a UI rate limit obstructs the legitimate user without impeding an attacker who can copy it.

If the previous lock had a reason worth reporting — idle timeout, in particular — the screen shows it as a subtitle so the user understands why they are being asked again.

### 9.4 Account list

A scrollable list. A TOTP row shows issuer, account name, the current code grouped for readability (`123 456`), and a circular countdown. Tapping it copies the code and shows a transient confirmation with the clipboard clear countdown.

An HOTP row shows issuer, account name, the current counter value, and a generate control in place of the countdown. It displays no code until the user asks for one, because displaying one consumes a counter value (§5.6). After generation the code stays on screen with a copy affordance until the row is collapsed, the list is left, or the vault locks; the generate control is disabled for a short interval afterwards so a double-tap does not silently burn two counter values. A failed vault write leaves the counter unchanged and shows no code.

Above the list: a search field filtering on issuer and account name, case-insensitively and on substring match; a sort control (manual order, issuer A–Z, recently added); a lock button; and an add button.

Manual reordering by drag. `orderIndex` is renumbered and the vault is written on drop.

Row overflow menu: edit, copy code, copy `otpauth://` URI, show QR code (§9.7), delete. Copying the URI is a secret disclosure and carries the gate stated at the head of §9. Delete requires a confirmation dialog naming the account, and is irreversible; recovery requires an export taken earlier.

A copied URI is subject to the same clipboard clear delay as a copied code (§11), matched on the exact string that was placed there.

An empty vault shows an empty state with the two import paths — scan a QR image, or paste a URI — rather than an unadorned blank list.

### 9.5 Add account

Three input paths in one screen:

1. **Paste URI.** A text field accepting `otpauth://`. Parses on input and shows a live preview of the resolved fields, or the specific parse error.
2. **QR image.** A file picker (`AwtWindow` hosting `java.awt.FileDialog`) accepting PNG, JPEG, GIF, BMP. The image is decoded with ZXing's `MultiFormatReader` over a `BufferedImageLuminanceSource` with `HybridBinarizer`. Multiple QR codes in one image are handled by `GenericMultipleBarcodeReader`, presenting a selection list. A decoded payload that is not an `otpauth://` URI is rejected with a specific message.
3. **Manual entry.** Type (TOTP or HOTP, defaulting to TOTP), issuer, account name, secret, and an advanced section for algorithm, digits, and either period or starting counter according to type. The secret field validates base32 on input. The counter field accepts an unsigned 64-bit value and defaults to 0.

All three converge on the same preview showing the resolved entry. A TOTP preview carries a live sample code. An HOTP preview shows the starting counter and the code that counter would produce, computed without persisting anything, so verifying the entry does not consume a counter value before the account exists. Saving writes the vault immediately.

Screen-region QR capture is listed in §16.8: it requires `java.awt.Robot` screen capture permission, which on macOS triggers a Screen Recording privacy prompt and on Wayland needs a portal integration.

### 9.6 Edit account

Issuer and account name are freely editable. Algorithm, digits, and period or counter are editable behind an "advanced" disclosure carrying a warning that changing them invalidates codes unless the server side matches. The secret is not editable; changing a secret means deleting and re-adding, which prevents a mistyped edit from silently destroying the only copy of a credential. The type is not editable, since TOTP and HOTP take different parameters and switching between them discards one of them.

The counter is editable because resynchronisation requires it: a client that has generated codes beyond the server's look-ahead window can only recover by being set back or forward (§5.6). The field shows the stored value and accepts any unsigned 64-bit value.

### 9.7 Show QR code

A dialog reachable from the row overflow menu, rendering the entry's `otpauth://` URI as a QR code so another authenticator — a phone, a second desktop, a hardware token's companion app — can enrol the same account by scanning the screen. This is the intended migration path off TAuth and the counterpart to QR import in §9.5.

**Encoding.** `QRCodeWriter.encode(uri, BarcodeFormat.QR_CODE, size, size, hints)` from `zxing-core`, with `EncodeHintType.ERROR_CORRECTION = ErrorCorrectionLevel.M`, `EncodeHintType.MARGIN = 2` (quiet zone, in modules), and `EncodeHintType.CHARACTER_SET = "UTF-8"`. Level M matches what Google Authenticator's own provisioning codes use and keeps the symbol small: a 160-bit secret with issuer and account name yields a URI of roughly 100–150 characters, a version 6–7 symbol at 41–45 modules square.

**Rendering.** The returned `BitMatrix` is drawn onto a Compose `Canvas` as one filled rectangle per dark module. The module size is computed as `floor(canvasPx / matrixWidth)` and the symbol is centred with the remainder as extra quiet zone, so no module straddles a fractional pixel boundary. Fractional module edges blur under scaling and scanners reject the result at small sizes far more often than the visual difference suggests.

The symbol is always dark-on-light with a light quiet zone, independent of the application theme. Inverting module polarity for a dark theme breaks a large fraction of scanners, so the dialog draws its own light surface behind the symbol rather than inheriting the theme background. Minimum rendered size is 240×240 logical pixels; the dialog scales the symbol up to the available space in whole-module increments.

**Actions.** Beneath the symbol: the issuer and account name as plain text, so the user can confirm they are exporting the account they intended; "Copy URI"; and "Save as PNG" via `MatrixToImageWriter.writeToPath` from `zxing-javase`, written with `0600` permissions.

**Gating.** Displaying the QR places a complete credential on screen in machine-readable form; a photograph, a screenshot, or an active screen-sharing session captures it in full. The dialog carries the secret disclosure gate stated at the head of §9. It closes after 60 seconds without interaction, and suppresses the idle lock timer while open so the vault does not lock underneath a symbol the user is mid-scan.

For an HOTP entry the encoded URI carries the counter as it stands when the dialog opens. Scanning it clones the entry at that position rather than at the position the other authenticator will next need, so the dialog states the counter in text beneath the symbol alongside the issuer and account name.

### 9.8 Settings

Reachable only from the unlocked graph, because the groups marked *policy* below are stored in the vault body and changing one is a vault write.

- **Security** *(policy)* — change master password; re-encrypt vault (DEK rotation).
- **Locking** *(policy)* — idle timeout (off / 1 / 5 / 15 min); lock on minimise; grace period before hide-triggered lock; lock on focus loss (default off).
- **Clipboard** *(policy)* — clear delay (off / 10 / 20 / 60 s).
- **Appearance** *(preference)* — theme (system / light / dark); list sort order.
- **Tray** *(preference)* — minimise to tray; start minimised. Both disabled with an explanation when no tray is available.
- **Data** — vault file location with a reveal-in-file-manager action; export; import.
- **About** — version, licence, and the security notes describing what the vault protects against and what it does not.

A policy change is applied in memory and written with the vault before the control reflects it, so a failed write leaves the stored policy and the displayed state in agreement. A preference change writes `preferences.json` and needs no unlocked vault, though the screen that hosts it does.

The distinction is stated once in the screen's header rather than repeated per control: appearance and tray settings live in a plaintext file; everything governing locking lives inside the vault and cannot be changed without the master password.

### 9.9 Export and import

**Encrypted export** produces a copy of the vault file. It is the recommended backup and requires no additional confirmation.

**Plaintext export** produces a JSON file or a list of `otpauth://` URIs, carrying the secret disclosure gate stated at the head of §9 and a dialog stating that the output is unencrypted. The file is written with `0600` permissions. This is the migration path to other authenticators and is the reason plaintext export exists at all. HOTP entries export with their current counter, which is a point-in-time snapshot: codes generated in TAuth after the export move the vault ahead of the exported file.

**Import** accepts a plaintext export or a newline-separated list of `otpauth://` URIs, shows a preview with per-entry validity, and detects duplicates by `(issuer, accountName, secret)`, offering skip or add-anyway per duplicate.

---

## 10. Tray and window lifecycle

### 10.1 Structure

```kotlin
fun main() = application {
    val session = remember { VaultSession(...) }
    val prefs = remember { PreferencesStore.load() }        // plaintext, pre-unlock
    val lifecycle = remember { WindowLifecycle.of(isSystemTraySupported(), prefs) }
    val windowState = rememberWindowState(isMinimized = lifecycle.startup == StartupWindow.ICONIFIED)
    var visible by remember { mutableStateOf(lifecycle.startup != StartupWindow.HIDDEN_TO_TRAY) }

    if (lifecycle.isTrayShown) {
        Tray(
            icon = TrayIcon,
            tooltip = "TAuth",
            state = rememberTrayState(),
            onAction = { visible = true },
            menu = {
                Item("Show") { visible = true }
                Item("Lock now") { session.lock(LockReason.Manual) }
                Separator()
                Item("Quit") { session.lock(LockReason.Exit); exitApplication() }
            },
        )
    }

    Window(
        onCloseRequest = {
            when (lifecycle.onCloseRequest) {
                CloseAction.HIDE_TO_TRAY -> visible = false
                CloseAction.EXIT -> { session.lock(LockReason.Exit); exitApplication() }
            }
        },
        visible = visible,
        state = windowState,
        title = "TAuth",
        icon = AppIcon,
    ) {
        TAuthApp(session, prefs)
    }
}
```

`WindowLifecycle.of` takes tray availability and the two tray preferences and answers what a close request does, where the window opens, whether a tray icon exists and whether the tray settings are offered. The window leaves the screen only where a tray icon can bring it back, so the answer turns on `isTraySupported && minimiseToTray` rather than on availability alone: a desktop with no tray and a user who turned the tray off both take the fallback of §10.2. Whether the settings are offered turns on availability alone, since those settings are the controls that set the preferences.

Minimising is the platform's own on every desktop and is not one of those answers. Hiding the window is the close request's alone, which is what leaves `WindowState.isMinimized` an observable thing for the minimise trigger of §8.3 to fire on and for `SecurityPolicy` to govern.

`isSystemTraySupported()` is `java.awt.SystemTray.isSupported()`. `isTraySupported` is a **global** property in `androidx.compose.ui.window`, not an `ApplicationScope` extension, and delegates to the same call. `Tray` is an `ApplicationScope` extension taking `(icon: Painter, state: TrayState, tooltip: String, onAction: () -> Unit, menu: @Composable MenuScope.() -> Unit)`. Both are confirmed present in Compose Multiplatform 1.11.1 (§15).

Relock is driven by observing visibility and minimisation rather than by wiring each call site:

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { visible to windowState.isMinimized }
        .collect { (isVisible, isMinimised) ->
            when {
                !isVisible -> session.scheduleLock(LockReason.HiddenToTray)
                isMinimised -> session.scheduleLock(LockReason.Minimised)
                else -> session.cancelScheduledLock()
            }
        }
}
```

The window layer reports what happened and does not decide what it means; §8.2 states what the session does with it. The policy therefore never has to be passed through composables or read while the vault is closed.

A window another process raised is the exception the collector has to carry: it becomes visible without the user having come back to it, so a relock scheduled before it went up survives the transition rather than being cancelled by it (§10.3).

### 10.2 Platform behaviour

**Linux.** `java.awt.SystemTray.isSupported()` returns true only when a StatusNotifierItem or legacy notification-area host is present. GNOME removed built-in tray support in 3.26. Recovery requires a shell extension: the third-party AppIndicator/KStatusNotifierItem extension, or the official Status Icons extension shipped with GNOME Shell Extensions from GNOME 47, neither of which is installed by default in most distributions. The AppIndicator extension has broken across GNOME major releases, notably at GNOME 48. The practical consequence is that a large fraction of GNOME users have no tray.

The application must therefore never become invisible and unquittable. When `isTraySupported` is false, the tray-related settings are disabled with an explanation, `onCloseRequest` exits the application, and `startMinimised` opens the window minimised on the taskbar rather than hidden with nothing to restore it. A tray the desktop supports and the user has turned off reaches the same close and startup behaviour, since no icon is on screen to raise a hidden window either way; its settings stay offered, because they are what turns the tray back on.

Ubuntu ships `ubuntu-appindicators` enabled by default, so the AWT tray works there without user action; on the reference machine (§15) `SystemTray.isSupported()` returns true under a Wayland session, because AWT runs through XWayland with the X11 toolkit. A GNOME installation without that extension, which is most non-Ubuntu GNOME, reports false and takes the degraded path above.

`dev.nucleusframework:composenativetray` talks to StatusNotifierItem over D-Bus directly, handles the GNOME double-left-click convention, and bundles a `SingleInstanceManager`. It is the fallback if the AWT tray proves inadequate on target distributions (§16.8).

**macOS.** The tray icon appears in the menu bar. The icon must be a monochrome template-style image sized for the menu bar (22×22 logical), legible on both light and dark backgrounds. A colour icon renders poorly. TAuth keeps its Dock icon. A pure menu-bar application with no Dock icon is achievable with `LSUIElement`:

```kotlin
macOS {
    infoPlist {
        extraKeysRawXml = """
            <key>LSUIElement</key>
            <true/>
        """.trimIndent()
    }
}
```

Neither this nor the equivalent `-Dapple.awt.UIElement=true` JVM argument is used: both remove the application from the Dock and the application switcher, which does not suit an application whose main window is the primary interface.

**Windows.** The tray icon works without additional configuration and may be placed in the notification overflow area by default, which is expected and requires no handling.

### 10.3 Single instance

Two TAuth processes writing one vault lose an update, and a tray application relaunched from the Start menu or Spotlight should raise the existing window rather than start a second process.

Implementation: at startup, attempt an exclusive `FileLock` on `<vaultDir>/instance.lock`. On success, bind a `ServerSocket` on `127.0.0.1:0`, write the chosen port into the lock file's sibling `instance.port`, and listen for a `SHOW` command. On failure to acquire the lock, read the port, connect, send `SHOW`, and wait for the running instance's acknowledgement; the launch that receives it exits with status 0. The running instance makes its window visible and requests focus, and reports that raise as a show request rather than as the user returning to the window: the relock collector of §10.1 cancels a pending relock only for the user's return, so a window raised by `SHOW` comes up with a scheduled relock still standing.

The exit turns on the acknowledgement rather than on the send, because a port a crashed instance recorded can have been taken since by an unrelated program: a launch that exited on the send alone would raise nothing and report nothing. The running instance records the request before it answers, so an acknowledged request is a request that will be acted on.

A launch that becomes primary replaces `instance.port`, which covers whatever a crashed instance left there. The lock file is never unlinked: unlinking releases no lock a live process holds on the inode, and the next launch would then take a second lock on a new inode, which is two primaries.

A launch that can neither take the lock nor reach a running instance opens its window without single-instance service and says so on screen, since exiting silently would leave the application unstartable for as long as whatever holds the lock does. That state costs a vault: two live instances each hold their own decrypted body, and a save rewrites the whole file, so the later save drops whatever the other wrote. `VaultStore`'s lock spans one `write()` and refuses only writes that overlap it, and `read()` takes no lock at all, so nothing reports the loss. Closing it needs the write to be a compare-and-swap against the file it read, which is not in this design.

A `SHOW` arrives over loopback, which carries no owner check, so it can come from any process on the machine and from a different OS user. That is why the raise it causes is not the user's return: a show request that cancelled a pending relock would let anything on the machine hold an unlocked window open on screen.

---

## 11. Clipboard

Copy uses `java.awt.Toolkit.getDefaultToolkit().systemClipboard`. After the configured delay, the clipboard is cleared **only if its current contents still equal the exact string TAuth placed there**, so that the timer never destroys something the user copied in the meantime. This covers generated codes and copied `otpauth://` URIs alike. The comparison reads the clipboard contents, which on some platforms can throw `IllegalStateException` under contention; failures are caught and the clear is skipped.

The clipboard is also cleared, subject to the same equality check, on every lock.

On Linux, clipboard contents are owned by the source application. Clearing works within the process's lifetime; after the application exits, X11 clipboard contents vanish anyway, while Wayland behaviour depends on the compositor and any clipboard manager. A clipboard manager will retain history regardless of what the application does, and this is noted in the security notes.

---

## 12. Error model

```kotlin
sealed interface VaultError {
    data object NoVaultFile : VaultError
    data object WrongPassword : VaultError                  // wrap unwrap failed authentication
    data object IntegrityFailure : VaultError               // body decryption failed authentication
    data class Corrupt(val detail: String) : VaultError     // structural parse failure
    data class UnsupportedVersion(val found: Int, val supported: Int) : VaultError
    data class InvalidSecret(val detail: String) : VaultError
    data class MalformedUri(val detail: String) : VaultError
    data object VaultClosed : VaultError                    // the session key is zeroed
    data class TooLarge(val size: Int, val limit: Int) : VaultError // refused rather than written
    data class Io(val cause: Throwable) : VaultError
    data class LockedByAnotherProcess(val path: String) : VaultError
}
```

`VaultError` is a sealed interface and is never thrown. Fallible operations return `Outcome<T, VaultError>`, so failure modes are visible in signatures and a `when` over the hierarchy with no `else` branch fails to compile when a case is added. Exceptions from the JDK are caught where they arise and converted immediately: `IOException` does not propagate past `VaultStore`, `GeneralSecurityException` does not propagate past the `crypto` package. See STYLE_GUIDE.md §4.

Every error maps to a specific user-facing message. `WrongPassword` and `IntegrityFailure` in particular must never share a message: one means "try again", the other means "this file has been modified or damaged". `WrongPassword` says the password did not work and claims nothing about the file, which §6.7 explains.

---

## 13. Testing

### 13.1 `commonTest`

**HOTP** — all ten RFC 4226 Appendix D vectors, each as a separate assertion naming its counter. Counter 0 and the 64-bit maximum, confirming the counter is encoded as an unsigned 64-bit big-endian value throughout.

**TOTP** — all eighteen RFC 6238 Appendix B vectors, each as a separate assertion with the algorithm, timestamp and expected value visible in the test name. Truncation of the eight-digit RFC values to six digits for the default configuration. Period boundary behaviour at exactly `T` and `T-1`. The `20000000000` vector, exercising 64-bit `T`.

**Shared core** — feeding `floor(t / period)` through the HOTP entry point reproduces every TOTP vector, proving one implementation rather than two.

**Base32** — RFC 4648 §10 vectors, padded and unpadded input, padding of the wrong length in both directions, every trailing group length that cannot end an encoding, lowercase input, embedded whitespace, invalid characters, empty input.

**otpauth URI** — issuer in the label prefix only; issuer as a parameter only; both present and equal; both present and conflicting; percent-encoded label with a colon and with spaces; missing secret; unknown type; digits outside 6–8; a period below the minimum; `hotp` without `counter`; `totp` carrying `counter`; `hotp` carrying `period`; counter at the 64-bit maximum; unknown parameters ignored; parameter names matched without regard to case; leading spaces and a trailing newline shed from the input while a leading U+2028 is not; a trailing space shed rather than kept by the last parameter's value; round-trip build-then-parse for every entry configuration of both types.

**Vault codec** — round trip with an empty entry list and with several hundred entries; wrong password produces `WrongPassword`; a flipped bit in the ciphertext or the GCM tag produces `IntegrityFailure`; a flipped bit at every offset across the header produces `Corrupt` and never `WrongPassword`, swept exhaustively rather than sampled; a flipped bit in the CRC itself produces `Corrupt`; a flipped bit at every offset ahead of the CRC produces `Corrupt`; a modified `headerLength` produces `Corrupt` and never a silent success; truncated file; a version byte the writer never wrote produces `Corrupt` while a later version whose CRC agrees produces `UnsupportedVersion`; an unknown header key is tolerated; two successive writes of identical content produce different ciphertext, proving nonce freshness.

Every value drawn from the CSPRNG — the DEK, the salt, the wrap nonce — is compared across two independently created vaults, because a constant satisfies any assertion made within one. Rotation keeps the salt and draws a fresh wrap nonce, which matters because it wraps under the KEK that wrapped the previous key. A header or body `v` the reader does not know produces `UnsupportedVersion`; a `headerLength` with the high bit set produces `Corrupt` rather than a backwards slice. A `v` written as a quoted digit reads as that version on both paths, and an entry's quoted `period` reads as that period, which is the latitude §6.7 records.

Each of the codec's three key arrays is lent to a block, so each zeroing is asserted rather than inspected: the KEK through `withKek`, a freshly drawn DEK through `withFreshDek`, and the DEK an unlock hands over through `adoptedOrZeroed`. Every one is checked both after a block that returns and after a block that throws. The adopted DEK is also checked to survive the one path that keeps it, since zeroing there would hand back a vault whose key is zeros.

**Entry model** — `orderIndex` renumbering on insert, delete and reorder; an `id` in canonical form carrying the version 7 nibble, ordering and uniqueness being `Uuid.generateV7`'s contract rather than this project's; `period`/`counter` pairing enforced per type; a `hotp` entry with a null counter rejected as `Corrupt`.

**Security policy** — a body with no `policy` object yields the full defaults; a partial `policy` fills the remainder from defaults; each default is asserted field by field so a later change names itself; a negative duration is rejected; a policy edit round-trips through a write and read; tampering with a policy value in the ciphertext produces `IntegrityFailure` rather than an altered policy.

### 13.2 `jvmTest`

**Crypto primitives** — Argon2id against published Argon2 reference vectors, confirming BouncyCastle is invoked with the intended version and parameters; AES-GCM against NIST test vectors; HMAC against RFC 2202 and RFC 4231 vectors, including the case 6 keys that exceed the hash's block size and so are hashed down before use; base64 over input producing `+` and `/`, and rejecting the URL-safe alphabet that replaces them; the generator behind `secureRandomBytes` is asserted to be a `java.security.SecureRandom`, which nothing about the bytes themselves establishes from inside one process.

The Argon2 cost test states the parameters as literals on the reference side, never through the constants under test, and each constant has a test of its own naming its value. The cost travels nowhere in the file, so these are the only things standing between a hand-edited constant and a silently weaker KDF.

**Vault store** — atomic write leaves no `.tmp` on success; a failed write leaves the original file intact and readable; a write that fails at the rename leaves the whole new vault in `vault.tauth.tmp`; a vault behind a directory the process cannot traverse is reported as unreadable rather than as absent; POSIX permissions are `0600` on the vault and the lock file and `0700` on the directory, including a directory or lock file that already existed too widely; concurrent writes from two `VaultStore` instances serialise on the in-process lock and leave one whole payload rather than a mixture. A write that cannot take the file lock reports `LockedByAnotherProcess` and leaves the previous vault byte for byte, driven by an injected channel whose `tryLock` declines the way it does when another process holds the lock — two channels in one JVM collide instead, and both stores queue on the in-process lock before either reaches the file lock.

**Vault paths** — resolution under a set `XDG_DATA_HOME`, an unset one, a blank one and a relative one, the same for `%APPDATA%`, an empty home leaving the location unresolved, and per-OS branches driven by an injected OS identifier rather than the real `os.name`.

**Session** — `lock()` zeroes the DEK array, verified by retaining a reference to the backing array; the KEK is zeroed after unwrap; scheduled lock fires after the grace period; scheduled lock is cancelled when the user brings the window back, and stands when another process's show request raises it; the ticker stops on lock; the ticker never computes an HOTP entry; `scheduleLock` reads its grace period and arming from the session's policy and is a no-op against a locked vault.

**Preferences** — `preferences.json` absent, empty, malformed or holding unknown keys all yield usable defaults rather than a startup failure, since the file is attacker-writable and the application must open regardless. No security-relevant field is read from it.

**HOTP counter** — generating a code persists the incremented counter before returning it; a write failure leaves the stored counter unchanged and yields no code; a counter surviving a lock/unlock cycle; two successive generations produce consecutive counter values and different codes.

**Password change** — the vault opens under the new password and fails under the old; the DEK is unchanged, verified by comparing decrypted body bytes.

**QR round trip** — for every entry configuration in the URI test matrix, encode the entry to an `otpauth://` URI, render it through `QRCodeWriter`, decode the resulting `BitMatrix` through `MultiFormatReader`, and assert the decoded payload equals the original URI byte for byte. This covers character-set handling for non-ASCII issuer and account names, which is where URI-to-QR round trips most often fail. A second case asserts the symbol version stays at or below 10 for a 160-bit secret with a 64-character label, since larger symbols become hard to scan at the dialog's minimum size.

### 13.3 Manual verification

Tray behaviour requires manual verification on GNOME (with and without a tray extension), KDE Plasma, Windows 11, and macOS.

Cross-platform vault portability is verified by creating a vault on one OS, copying it to the other two, and unlocking with the password on each.

The show-QR dialog is verified by scanning its output with at least three unrelated authenticators — Google Authenticator, Aegis or Raivo, and one desktop scanner — at the dialog's minimum size and on both a light and a dark system theme. Automated round-trip tests confirm the payload; only a real scanner confirms the rendering.

Argon2id timing is measured on the lowest-specification target machine to confirm the parameter choice in §6.5.

---

## 14. Milestones

**M1 — OTP core.** `Base32`, `Hmac` expect/actual, `Hotp`, `Totp`, `OtpAuthUri` covering both types, and the full test suite from §13.1. No UI. Deliverable: a green test run covering every RFC 4226 and RFC 6238 vector.

**M2 — Vault format.** `crypto` expect/actual set, `VaultHeader`, `VaultBody`, `VaultEntry`, `SecurityPolicy` as a field of the body, `VaultCodec`, `VaultStore`, `VaultPaths`, `VaultError`, and the tests from §13.1 and §13.2. No UI. Deliverable: create, write, read and round-trip a vault from tests. The store's POSIX branch is exercised here; its access-control-list branch is verified with the packaged artifacts in M5.

**M3a — Shell infrastructure.** `Preferences` and `PreferencesStore`, `ClipboardService`, single instance (§10.3), and tray availability with the fallback §10.2 describes. Deliverable: a preference file that survives a restart, a clipboard that clears only the string it placed, a second launch that raises the first window and exits, and a correct answer to whether this desktop has a tray.

**M3 — Session and unlocked UI.** `VaultSession` including `scheduleLock` and `cancelScheduledLock`, `SessionState`, `LockReason`, the code ticker, create-vault screen, unlock screen, account list with live TOTP codes and generate-on-request HOTP rows, the HOTP persist-before-display path, add-account by URI and manual entry, edit including counter, delete, and the secret disclosure gate stated at the head of §9, built as a component rather than at its one call site. Deliverable: a usable authenticator. Delete has no recovery path until export arrives in M4.

**M4 — Tray, lifecycle and settings.** Tray construction and menu, hide-to-tray, the relock triggers of §8.3, grace period, idle timeout, the lifecycle behaviour `SecurityPolicy` governs, and the settings screen of §9.8 including change master password, re-encrypt, and encrypted export.

**M5 — QR, plaintext export, packaging.** ZXing image decode for import and `QRCodeWriter` for the show-QR dialog (§9.7), plaintext export, import with duplicate detection, DMG/MSI/DEB configuration, icons for all three platforms, and verification of the packaged artifacts on each OS.

M1 and M2 have no dependency on Compose and are fully testable headless. M3a touches neither the session nor the vault, so it is buildable and testable ahead of M3; every relock trigger in M4 resolves to a call on the session M3 builds, and every M5 item is an entry point added to a screen M3 or M4 already has.

---

## 15. Reference measurements

Figures quoted elsewhere in this document were taken on one machine, described here so they can be interpreted and re-taken.

**Reference machine.** Ubuntu, GNOME Shell 50.1, Wayland session, 20 logical cores, 16 GB RAM, Azul Zulu JDK 21, Gradle 9.1, Kotlin 2.4.10.

**Argon2id timings** (§6.5) come from `Argon2BytesGenerator` in BouncyCastle 1.85, 32-byte output, 16-byte salt, JIT warmed, median of three runs. They scale with core speed and memory bandwidth, not core count, since p = 1.

**Tray availability** (§10.2): `java.awt.SystemTray.isSupported()` returns true, with `sun.awt.X11.XToolkit` as the toolkit under a Wayland session by way of XWayland, and `ubuntu-appindicators@ubuntu.com` providing the StatusNotifierItem host. This measures a machine where the tray works; §10.2 covers the case where it does not, which the same call detects.

**Compose tray API** (§10.1) was read from `ui-desktop-1.11.1.jar` with `javap` rather than from documentation for earlier versions.

**Library compatibility** (§4.1): kotlinx-serialization-json 1.11.0 and kotlinx-datetime 0.8.0 compile against Kotlin 2.4.10 in this project's `:shared` module, verified with a source file exercising `@Serializable` round-tripping and the kotlinx-datetime calendar types rather than by resolution alone.

The measurements that constrain a choice are the Argon2 timings. Re-take them if the parameters in §6.5 change, or before assuming the unlock latency is acceptable on hardware materially slower than the reference machine.

---

## 16. Future Improvements

### 16.1 Unlock via platform authenticator

The password-only design requires typing the master password on every unlock, and §8.3 makes unlocks frequent by design — hiding to the tray discards the key. The intended improvement is an optional second unlock path backed by the operating system's credential store, reached through Touch ID on macOS, Windows Hello or the Windows user session on Windows, and the freedesktop Secret Service keyring on Linux.

**Structure.** Envelope encryption already separates the KEK from the DEK. The keyring path stores a second copy of the DEK, so that either unlock path yields the same key and the body is never re-encrypted when the feature is toggled:

```
                    ┌──────────────────┐
  master password ──┤ Argon2id + salt  ├──► KEK (32 B)
                    └──────────────────┘        │
                                                │ AES-256-GCM unwrap
                                                ▼
  OS keyring entry ─────────────────────────► DEK (32 B) ──► AES-256-GCM ──► vault body
```

The master password remains mandatory. The keyring is a convenience path, never the only one, so that a lost keyring entry, a re-enrolled fingerprint, or a move to another machine never renders the vault unopenable.

### 16.2 Consequences to state in the UI when this lands

- Enabling keyring storage makes the platform authenticator sufficient to read every secret. The vault's security becomes the weaker of (master password, platform authenticator).
- On Linux, the freedesktop Secret Service default `login` collection is readable by any application running as the same user once the keyring is unlocked (CVE-2018-19358). This is a property of the platform, not of TAuth, and it must be stated at the point where the user enables the feature.
- On Windows without the Hello work in §16.6, and on Linux, no biometric or presence check occurs. The key is released to anyone with the user's logged-in session. The settings copy must name the actual protection ("Windows user account", "system keyring") rather than implying a fingerprint check.

### 16.3 File format impact

None. The header already carries `vaultId` (§6.3) for exactly this purpose. Enabling the feature adds one object to the header:

```json
"keyring": {
  "enabled": true,
  "service": "com.panda.tauth",
  "account": "vault-dek"
}
```

Header deserialisation already tolerates unknown keys, so a vault written with this block opens unchanged in a build that predates the feature. The keyring entry's account name incorporates `vaultId`, so two vaults on one machine never collide and a stale entry from a deleted vault is never applied to a new one.

### 16.4 Interface

```kotlin
interface SecretStore {
    val id: String                      // "macos-keychain", "windows-credential", "secret-service"
    val displayName: String             // "Touch ID", "Windows Credential Manager", "System keyring"
    val promptsForUserPresence: Boolean // true only where a biometric/PIN prompt is guaranteed

    fun isAvailable(): Boolean
    fun put(account: String, secret: ByteArray): Result<Unit>
    fun get(account: String): Result<ByteArray?>   // null = no entry; failure = store error or user cancel
    fun delete(account: String): Result<Unit>
}
```

`promptsForUserPresence` drives the UI wording required by §16.2. A `SecretStoreFactory` dispatches on `System.getProperty("os.name")` and probes availability once at startup. All store calls run off the UI thread and carry no timeout, since a call may legitimately block on a user-facing prompt.

New error cases: `KeyringUnavailable`, `KeyringEntryMissing`, and `KeyringCancelled`. The last is distinguished so that dismissing a Touch ID prompt returns silently to the password field instead of showing an error banner.

New operations: enable (requires an unlocked session; writes the DEK, then sets the header flag, so a failed write leaves the header untouched), disable (deletes the entry, clears the flag, warns if deletion failed), and repair (re-writes the entry when it is found missing at unlock time). Changing the master password does not touch the keyring entry, since the DEK is unchanged. Rotating the DEK (§7.1) must rewrite it.

New dependencies: `net.java.dev.jna:jna` and `jna-platform` (5.19.1 current), and on Linux `de.swiesend:secret-service` (3.0.0-beta, requires JDK 17+, pulls in `dbus-java`). The Linux dependency must be loaded reflectively or guarded by an OS check so that a missing D-Bus session on macOS or Windows never causes class initialisation failures. Packaging must then retain `jdk.unsupported`, which JNA requires on some paths.

### 16.5 Per-platform implementation

**Linux — freedesktop Secret Service.** `de.swiesend:secret-service` over D-Bus, reaching gnome-keyring, KWallet's Secret Service bridge, or KeePassXC's built-in provider. Availability probe: the session bus is reachable and `org.freedesktop.secrets` is a registered name; a headless session or missing `DBUS_SESSION_BUS_ADDRESS` yields unavailable. The secret is written to the default collection with attributes `{"application": "tauth", "vault": "<vaultId>"}`. `promptsForUserPresence = false`. KWallet support in the library is documented upstream as best-effort, so a failure on KDE must degrade to the password path rather than block startup. Storing in a non-default, always-locked collection is a hardening option that trades away the convenience the feature exists to provide, so it belongs behind a setting rather than as the default.

**macOS — Keychain with Touch ID.** Two tiers selected at runtime.

*Tier 1, biometric.* JNA bindings to `Security.framework`: `SecAccessControlCreateWithFlags(kCFAllocatorDefault, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlBiometryCurrentSet or kSecAccessControlOr or kSecAccessControlDevicePasscode, &error)`, then `SecItemAdd` / `SecItemCopyMatching` with `kSecClass = kSecClassGenericPassword`, `kSecAttrService`, `kSecAttrAccount`, `kSecAttrAccessControl`, and `kSecUseDataProtectionKeychain = true`. The data protection flag is mandatory: the legacy file-based keychain does not support `kSecAttrAccessControl`, and omitting it produces `errSecParam` or `-34018`. `kSecAccessControlBiometryCurrentSet` invalidates the entry when the enrolled fingerprint set changes; combining it with `kSecAccessControlDevicePasscode` via `kSecAccessControlOr` provides a passcode fallback so re-enrolment does not silently destroy the saved key.

*Tier 2, non-biometric.* The `security` command-line tool (`add-generic-password`, `find-generic-password -w`, `delete-generic-password`) against the login keychain. No Touch ID support exists on this path; access is governed by the login keychain's unlock state and the standard allow/always-allow ACL dialog. `promptsForUserPresence = false`.

The availability probe attempts tier 1 with a throwaway item and falls back to tier 2. **Tier 1 requires a code-signed application with a keychain-access-group entitlement, and therefore an Apple Developer account.** Without one, macOS users get tier 2 and no biometric prompt. This is the single largest external prerequisite for the feature and determines whether "Touch ID support" is deliverable at all.

CoreFoundation interop through JNA is the main implementation cost: `CFDictionaryCreate`, `CFStringCreateWithCString`, `CFDataCreate`, and disciplined `CFRelease` on every created reference. It is the largest native-interop surface in the project and warrants isolation behind a single file with its own tests.

**Windows — Credential Manager and DPAPI.** `Advapi32.CredWriteW` / `CredReadW` / `CredDeleteW` through `jna-platform`, which ships `CREDENTIAL` structure bindings. Written with `Type = CRED_TYPE_GENERIC`, `Persist = CRED_PERSIST_LOCAL_MACHINE`, `TargetName = "com.panda.tauth:vault-dek:<vaultId>"`. The DEK is additionally passed through `Crypt32.CryptProtectData` with `CRYPTPROTECT_UI_FORBIDDEN` and an entropy blob derived from the vault id, so a credential blob extracted on another machine or under another account is useless. `promptsForUserPresence = false`.

### 16.6 Windows Hello

`KeyCredentialManager` lives in `Windows.Security.Credentials`, a WinRT namespace with no supported JVM binding. Reaching it requires shipping a C++/WinRT helper DLL invoked over JNA or JNI, and a documented defect places the Hello prompt behind the calling window in JVM-hosted applications, requiring an explicit foreground-window handoff. This introduces a native build toolchain and a second signed binary to the distribution, so it is a separate piece of work from §16.5's Windows implementation and should not gate it.

### 16.7 Sequencing

Linux first: it is the development platform, requires no code signing, and exercises the whole `SecretStore` abstraction end to end. macOS tier 2, then tier 1 once a signing identity exists. Windows Credential Manager with DPAPI. Windows Hello last, if at all. The three platform implementations are mutually independent and can proceed in any order once the interface and the session-level unlock path exist.

Keyring behaviour cannot be meaningfully unit-tested; it depends on a live platform store and on user interaction. A written manual checklist covers, per OS: enable, relaunch, unlock via the store, disable, confirm the entry is gone from the platform's own credential UI, delete the entry externally and confirm graceful fallback, and confirm behaviour with the store locked or unavailable.

### 16.8 Other deferred items

- **Rollback detection** (§2.2). Every vault TAuth writes stays authentic, so an older copy put back in place opens normally. Telling the current file from a past one needs a counter held where whoever can rewrite the vault cannot reach it, which on a single machine does not exist — a plaintext sidecar is rewritten in the same motion as the vault. It waits for a remote endpoint that can hold the counter.
- **Screen-region QR capture** (§9.5), pending a decision on the macOS Screen Recording prompt and a Wayland portal integration.
- **`composenativetray`** as a replacement for the AWT tray on Linux (§10.2), pending field evidence from the GNOME versions actually in use.
- **Narrowed jlink module list** replacing `includeAllModules = true` (§4.3), once the packaged artifact is verified on each OS.

---

## 17. References

- [RFC 6238 — TOTP: Time-Based One-Time Password Algorithm](https://www.rfc-editor.org/rfc/rfc6238.html)
- [RFC 6238 errata](https://errata.rfc-editor.org/rfc6238) — errata 2866 (per-algorithm seeds), 5132, 8672 (64-bit `T`)
- [RFC 4226 — HOTP](https://www.rfc-editor.org/rfc/rfc4226)
- [RFC 4648 — Base16, Base32, Base64 Data Encodings](https://www.rfc-editor.org/rfc/rfc4648)
- [Key Uri Format — google/google-authenticator wiki](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)
- [draft-linuxgemini-otpauth-uri — otpauth URI usage specification](https://datatracker.ietf.org/doc/draft-linuxgemini-otpauth-uri/)
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- [BouncyCastle `Argon2BytesGenerator` javadoc](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/generators/Argon2BytesGenerator.html)
- [`javax.crypto.spec.GCMParameterSpec` javadoc](https://docs.oracle.com/javase/9/docs/api/javax/crypto/spec/GCMParameterSpec.html)
- [Compose Multiplatform — Top-level windows management](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html)
- [Compose Multiplatform — Desktop-only API](https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html)
- [Compose Multiplatform — Menu, tray and notifications tutorial](https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Tray_Notifications_MenuBar_new/README.md)
- [Compose Multiplatform — Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
- [`java.awt.SystemTray` javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/SystemTray.html)
- [AppIndicator and KStatusNotifierItem Support — GNOME extension](https://extensions.gnome.org/extension/615/appindicator-support/)
- [GNOME official Status Icons extension](https://www.omgubuntu.co.uk/2024/08/gnome-official-status-icons-extension)
- [ComposeNativeTray](https://github.com/kdroidFilter/ComposeNativeTray)
- [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir/latest/)

### Referenced by §16 (Future Improvements) only

- [swiesend/secret-service — Secret Service API for Java](https://github.com/swiesend/secret-service)
- [Apple — `SecAccessControlCreateWithFlags` and data protection keychain discussion](https://developer.apple.com/forums/thread/721649)
- [Implementing Windows Hello from Java — KeyCredentialManager obstacles](https://blog.purejava.org/posts/KeyCredentialManager/)
- [Windows Hello — Microsoft Learn](https://learn.microsoft.com/en-us/windows/apps/develop/security/windows-hello)
- [Azure SDK for Java — `WindowsCredentialApi` JNA bindings reference](https://azuresdkartifacts.blob.core.windows.net/azure-sdk-for-java/test-coverage/azure-identity/com.azure.identity.implementation/WindowsCredentialApi.java.html)
