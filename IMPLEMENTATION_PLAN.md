# TAuth — Implementation Plan

A cross-platform desktop TOTP authenticator built on Kotlin Multiplatform and Compose Multiplatform, targeting Linux, macOS and Windows. The vault is encrypted as a whole with a key derived from a master password, which is the sole unlock factor. The vault is unlocked only while the main window is on screen; hiding the window to the system tray destroys the in-memory key material.

Unlocking through platform authenticators — Touch ID, Windows Hello, the freedesktop Secret Service keyring — is specified in §16. The key hierarchy accommodates it without a file format change.

This document is the specification. Where code and plan disagree, one of them is a defect.

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

| Threat | Defence |
|---|---|
| Vault file copied from disk, backup, or a synced folder | Whole-file AES-256-GCM; key derived with Argon2id |
| Offline brute force of the master password | Argon2id with memory-hard parameters fixed by the format version |
| Tampering with any byte of the vault file, including metadata | GCM authentication tag covers the body; the header is bound as associated data |
| Editing the header to weaken or redirect the unwrap | The CRC fails before a key is derived; a repaired CRC fails the unwrap or the body's tag |
| Another user account on the same machine reading the vault | POSIX mode `0600` / Windows ACL restricted to the owner |
| Shoulder-surfing of an unattended unlocked window | Relock on hide-to-tray, on minimise, and on idle timeout |
| Silent weakening of the lock policy by editing a config file | Lock triggers and timeouts live in the vault body, under the GCM tag |
| Casual recovery of secrets from a memory dump after locking | Key material held in `ByteArray`, zeroed on lock; decoded secrets never converted to `String` |

### 2.2 Not defended against

- An attacker with code execution as the same OS user while the vault is unlocked. Key material is in the process heap by necessity.
- A vault replaced by an older copy of itself. Every file TAuth writes stays authentic, so a rollback is indistinguishable from the current vault. Detecting it needs an anchor the attacker cannot reach, which a local installation does not have (§16.8).
- Kernel-level keyloggers or screen capture.
- Heap contents paged to swap. The JVM does not expose `mlock`; swap encryption is the operating system's responsibility.
- Physical access with the machine unlocked and TAuth's window open.

### 2.3 Consequences of the design

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
    HashAlgorithm.kt          SHA1 | SHA256 | SHA512
    OtpCore.kt                RFC 4226 §5.2 HMAC and §5.3 truncation, shared by both types
    Hotp.kt                   counter moving factor
    Totp.kt                   RFC 6238 time-step moving factor
    OtpAuthUri.kt             otpauth:// parse and build
    PercentCodec.kt           RFC 3986 percent encode/decode
    EnumParsing.kt            case-insensitive enum lookup
    TotpCode.kt               code, period, and the instant the period ends
    CodeGrouping.kt           the one break a code is read across
    PreviewCode.kt            the code an account would produce before it is stored
  crypto/
    Aead.kt                   expect: AES-256-GCM seal/open
    Kdf.kt                    expect: Argon2id
    Hmac.kt                   expect: HMAC-SHA1/256/512
    SecureRandom.kt           expect: CSPRNG bytes
    SecureBytes.kt            zeroable byte holder
    Base64Codec.kt            base64 over kotlin.io.encoding
    Crc32.kt                  expect: CRC32 of the header bytes
  vault/
    VaultEntry.kt             entry model
    VaultBody.kt              decrypted body
    VaultHeader.kt            plaintext header
    VaultFormat.kt            byte-layout constants and the JSON configuration
    VaultCodec.kt             file <-> (header, body)
    VaultFile.kt              the bytes a vault lives in, behind the platform store
    VaultError.kt             the error hierarchy of §12
    EntryEdit.kt              the fields an edit may change, and the model's refusal of one
    EntryUri.kt               entry to otpauth:// URI and back
    EntryDrafts.kt            the text an add or edit form holds, and the account it resolves to
    PlaintextExport.kt        the two shapes accounts leave the vault in, and what each carries
    ImportRow.kt              what a file offers per row, and which rows the vault already holds
  session/
    SessionState.kt           NoVault | Locked | Unlocking | Unlocked
    LockReason.kt             the relock triggers, and the policy field each reads
    UnlockedEntry.kt          an entry as the UI holds it, without its secret
    SessionClipboard.kt       the one clipboard call a lock makes
    VaultSession.kt           key material, lock lifecycle, state flow
    CodeTicker.kt             live codes for the rows the list has on screen
    TickCadence.kt            the wait that lands a tick on a whole second
  password/
    PasswordStrength.kt       advisory score, and the minimum length §9.2 enforces
    CommonPasswords.kt        the embedded list a score is checked against
  settings/
    Preferences.kt            plaintext model, readable before unlock
    PreferencesState.kt       the single owner of that document, and the one path a change takes
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
    PreferencesStore.kt       plaintext JSON file; SecurityPolicy has no store of its own
                              and is read and written with the vault

shared/src/commonMain/kotlin/com/panda/tauth/ui/
  TAuthApp.kt                 root composable, routes on session state, and the password attempt
                              the create and unlock screens hand their characters to
  ClipboardCopy.kt            the copy a screen asks the shell for, and what it answered
  SingleInstanceNotice.kt     what a window with no single-instance service says above its screen
  theme/                      the palette, type scale, spacing scale and icon set of §9.10
  create/CreateVaultScreen.kt
  unlock/UnlockScreen.kt
  list/AccountListScreen.kt
  list/AccountRow.kt          code, countdown ring, mark, copy affordance
  list/AccountOrder.kt        the search filter, the three orderings, and where a drag drops
  list/Countdown.kt           the fraction of a period left, and the state it reports
  list/AccountMark.kt         the initial and the colour derived from an account's names
  list/RowState.kt            generated codes, the interval after one, and the copy confirmation
  edit/AddAccountScreen.kt
  edit/EditAccountScreen.kt
  edit/EntryPreview.kt        the resolved account every add path converges on
  edit/ScannedCodes.kt        the accounts among an image's codes, and the shell's decode seam
  edit/ScanState.kt           what an image offered, and the choice several accounts carry
  settings/SettingsScreen.kt
  settings/ExportError.kt     why a file did not reach the place it was asked for
  settings/SettingsWork.kt    what a settings action is doing, and what it reported
  settings/ShellSettings.kt   what the shell knows and no screen can ask for itself
  settings/PlaintextExport.kt the warning, the format, and the gate every account leaves through
  imports/ImportScreen.kt     what a file offered, and the choice a duplicate carries
  imports/ImportWork.kt       what an import has read and what has been decided about it
  components/PasswordField.kt         masked field over the holder beside it
  components/PasswordFieldState.kt    the CharArray a master password is edited in
  components/FormControls.kt          labelled text field and one-of-many choice
  components/SecretDisclosureGate.kt  the password re-entry every disclosure carries, and its state
  qr/QrSymbol.kt              the module grid a symbol is drawn from, and the shell's encode seam
  qr/QrLayout.kt              where the modules land on the canvas that draws them
  qr/ShowQrDialog.kt          the symbol on screen, what it stands for, and how long it stands

desktopApp/src/main/kotlin/com/panda/tauth/
  Main.kt                     application scope, window, tray, lifecycle
  TrayAvailability.kt         whether this desktop has a tray
  WindowLifecycle.kt          close and startup behaviour that follows
  TAuthTray.kt                the tray icon, and the three actions its menu carries
  TAuthIcon.kt                the mark the title bar carries, read from the drawable
                              §4.1's packaged icons are cut from
  ShellWindow.kt              where the window opens, and the geometry a window state records
  WindowClose.kt              what a close request does, and the order an exit does it in
  WindowGeometryRecorder.kt   the wait a geometry settles through before it reaches the file
  RelockTriggers.kt           what the window layer observes, and the report it makes of it
  IdleWatch.kt                the wait an interval passes without pointer or key input
  ExitLock.kt                 the lock a shutdown reaches through the runtime
  SingleInstance.kt           lock file + local socket
  InstanceStartup.kt          which claimed roles open a window and which one ends there
  WindowRaise.kt              the raise each show request makes, and the input that ends it
  ClipboardService.kt         copy with timed clear
  VaultExport.kt              where a copy of the vault goes, and the dialog that is asked
  OwnerOnlyFile.kt            the write every file leaving TAuth goes through, and the mode
                              it is created with
  FileManagerReveal.kt        which call this desktop answers for showing a file in its manager
  AboutBuild.kt               the version a packaged build reports, and the licence it carries
  QrDecoder.kt                every code an image holds, and the image the user chose to read
  QrEncoder.kt                ZXing symbol generation, and the image a saved symbol is written as
```

---

## 4. Dependencies

Every dependency and version goes through `gradle/libs.versions.toml`. This section records what each is for and the constraints on it, not the catalog itself.

| Dependency | Used for |
|---|---|
| `kotlinx-serialization-json` | the header, body, preferences and plaintext export documents |
| `kotlinx-datetime` | the calendar types the About group and `createdAt` render through |
| `bcprov-jdk18on` | Argon2id via `Argon2BytesGenerator` |
| `zxing-core` | `QRCodeWriter` for §9.7, `MultiFormatReader` for §9.5 |
| `zxing-javase` | `BufferedImageLuminanceSource`, for decoding image files |
| `composenativetray` | the tray icon and its menu (§10.2) |
| `compose-components-resources` | reading the drawable the tray and title bar carry |

BouncyCastle supplies Argon2id alone; it is a lightweight-API class needing no JCE provider registration. AES-GCM, HMAC and `SecureRandom` come from the JDK.

From kotlinx-datetime 0.8.0, `Instant` and `Clock` are `kotlin.time` types in the standard library. kotlinx-datetime supplies the calendar types built on them — `LocalDateTime`, `TimeZone`, `LocalDate` — and each is imported from the package that owns it.

The serialization Gradle plugin takes `version.ref = "kotlin"` because it ships with the compiler; the runtime library versions are independent of it.

The `composenativetray` artifact without the `-app` suffix carries the icon and the menu DSL alone and pulls in no windowing backend of its own.

Writing a QR symbol out as a PNG uses neither ZXing artifact: §9.7 renders it from the module grid the screen already holds, and the JDK's `ImageIO` encodes that.

### 4.1 Packaging

`nativeDistributions` targets DMG, MSI and DEB, and sets `includeAllModules = true`. That trades installer size for immunity to jlink stripping failures, where a stripped runtime fails at the module a code path reaches on one platform only and the failure lands on the user rather than the build. Narrowing it to an explicit module list is deferred (§16.8).

The mark is `desktopApp/src/main/composeResources/drawable/tauth.svg`, read through the resources library for the tray and the title bar. The three forms jpackage takes — `icons/tauth.png`, `icons/tauth.ico`, `icons/tauth.icns` — are cut from that one file, so the installer and the running application cannot show different marks. A monochrome variant sits beside it for the macOS menu bar, which draws a tray icon as a template image rather than in colour.

Beside the icons, `nativeDistributions` carries a description, a vendor and a copyright; on Linux a package name, a maintainer, a menu group and a category; on macOS a bundle identifier, fixed rather than derived because it is what a keychain entry and a Screen Recording grant are remembered against; on Windows a menu group and an upgrade UUID, fixed for the lifetime of the application because a changed one installs a second copy beside the first rather than replacing it.

---

## 5. OTP core

HOTP and TOTP are two moving factors over one core. `OtpCore` holds the HMAC and truncation of §5.2 and the digit bounds, and both types call it. They differ in where the moving factor comes from — a counter the client holds, or the clock — and in whether generating a code has a side effect.

### 5.1 Base32

RFC 4648 alphabet `ABCDEFGHIJKLMNOPQRSTUVWXYZ234567`. The decoder accepts input with or without `=` padding, ignores ASCII whitespace, and accepts lowercase by upper-casing before lookup. Invalid characters produce `VaultError.InvalidSecret`. The decoder returns `ByteArray`; the encoder produces unpadded output.

Padding is optional but not free-form: an `otpauth://` secret carries none, and input that does carry padding must carry the right amount. RFC 4648 §6 pads to a multiple of eight characters, so the count follows from the number of data symbols, and a group needing none can carry none. A wrong count means characters were lost, which is what padding exists to reveal.

Secret length is not constrained by the format. RFC 4226 recommends at least 128 bits and 160 bits is the norm; TAuth accepts any non-empty decoded secret and warns in the UI below 16 bytes.

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

with `T0 = 0` and `X` the entry's `period`. `T` is encoded as a **64-bit big-endian** integer. RFC 6238 errata 8672 records that treating `T` as 32-bit introduces a year-2038 defect; the implementation uses `Long` throughout and §5.4's `20000000000` vector exercises values beyond 32 bits.

Time comes from `kotlin.time.Clock.System`. A `Clock` is injected so tests supply fixed instants. No NTP correction is performed; a settings note explains that a system clock skewed by more than the period produces rejected codes.

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

Feeding counter `floor(t / 30)` into the same code path reproduces the TOTP vectors below, which is the check that the two types share one implementation.

#### TOTP (RFC 6238 Appendix B)

All vectors use `T0 = 0`, `X = 30`, and **8 digits**. The specification text claims a single shared secret and is wrong; errata 2866 (verified) records that the reference implementation uses a distinct seed per algorithm, and errata 5132 restates it. The seeds are ASCII strings:

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
- **Label** — percent-decoded path, leading `/` stripped. If it contains a `:` (or `%3A`), the portion before the first colon is the issuer prefix and the remainder, whitespace trimmed, is the account name. Otherwise the whole label is the account name.
- **secret** — required, base32, padding optional. Absent, undecodable, or decoding to no bytes produces `VaultError.InvalidSecret`. Whitespace and padding decode to nothing, so a non-empty secret can still carry no key.
- **issuer** — optional. Where both the parameter and a label prefix are present and differ, the parameter wins and the discrepancy is surfaced in the import preview.
- **algorithm** — optional, one of `SHA1`, `SHA256`, `SHA512`, case-insensitive. Default `SHA1`.
- **digits** — optional integer, 6–8. Default 6. Values outside the range are rejected rather than clamped.
- **period** — optional integer seconds, `totp` only, at least 1 with no upper bound. Default 30. Ignored on a `hotp` URI.
- **counter** — required for `hotp`, rejected on `totp`. Unsigned 64-bit. Absent on a `hotp` URI it is `VaultError.MalformedUri`; it alone has no default, because a wrong starting counter yields codes the server will not accept.

Unknown query parameters are ignored and not preserved.

Space, tab, carriage return and newline are shed from the ends of the input, which is what a paste from a chat window or a wrapped mail carries. One of those four surviving in the query makes the URI `VaultError.MalformedUri`: the query is where a wrapped paste is taken in silently, since base32 skips whitespace inside a secret and a parameter name carrying whitespace is an unknown parameter and ignored. The label carries whitespace raw, which is how issuers write it — `otpauth://totp/ACME Corp:alice@acme.com?secret=...` — and it is unambiguous there, since the label runs to the `?` and every character of it is part of a name.

The type and the algorithm are matched by ASCII case alone. Unicode case folding maps U+017F LATIN SMALL LETTER LONG S onto `S`, which would read `algorithm=%C5%BFHA256` as SHA-256 from a character the grammar's VCHAR has no room for.

Construction percent-encodes the label as `issuer:accountName`, emits `issuer` as an explicit parameter, and omits `algorithm`, `digits` and `period` at their defaults. A `hotp` URI always carries `counter`, at the value the entry holds when the URI is built.

Construction applies the parser's rules to its own arguments, so `parse(build(x)) == x` for every value the constructor accepts: the secret decodes to a key, the issuer and account name are well-formed UTF-16, the account name carries no colon, and an absent issuer is null rather than empty. A colon in the account name would build a label reading back as a different account under an issuer nobody entered; an empty issuer would build `&issuer=`, reading back as no issuer. A lone surrogate has no UTF-8 encoding and cannot be percent-encoded at all.

### 5.6 HOTP counter semantics

The counter is stored per entry and advances only when the user asks for a code. `TotpGenerator` derives its counter from the clock and holds no state; `HotpGenerator` reads and advances state, which makes code generation a write.

**Ordering.** Generating an HOTP code persists the incremented counter *before* the code reaches the screen. The reverse order lets a crash between display and write leave the stored counter behind the code already shown, and reissuing that code trips replay rejection on any server tracking consumed counters. The cost is a skipped counter value when a write succeeds and the user never uses the code, which the server's look-ahead window absorbs.

A counter at the unsigned 64-bit maximum has no successor to store, so generation is refused and no code is shown; wrapping to zero would reissue every code the server has already consumed. Editing the counter (§9.6) is what moves such an entry again.

**Write volume.** Each HOTP code view rewrites the whole vault per §6.6: fresh nonce, re-encrypt, atomic rename, fsync. TOTP entries cause no writes at all.

**Drift.** RFC 4226 increments the client counter on every code request and the server counter only on successful authentication, so any code generated and not submitted moves the two apart. Servers absorb this with a look-ahead window; beyond it, authentication fails until the counter is reset. The current counter is therefore visible on the entry and editable (§9.6), so a user told "your token is out of sync" can correct it without deleting and re-adding the account.

**Codes do not expire.** An HOTP code stays valid at the server until consumed or superseded, so it carries no countdown and is not recomputed on a timer (§8.5).

---

## 6. Vault file format

### 6.1 Location

| OS | Path |
|---|---|
| Linux | `${XDG_DATA_HOME:-$HOME/.local/share}/tauth/vault.tauth` |
| macOS | `~/Library/Application Support/TAuth/vault.tauth` |
| Windows | `%APPDATA%\TAuth\vault.tauth` |

`XDG_DATA_HOME` and `%APPDATA%` are used only when they name an absolute path; the XDG Base Directory Specification requires a relative value be ignored, and the same applies to `%APPDATA%`. A location that still comes out relative — an empty `user.home` leaves every branch relative — is refused rather than resolved against the working directory, which would put the vault wherever the application was launched from and leave the next launch unable to find it.

Settings are split by whether the application must read them before the vault is open.

`preferences.json` sits beside the vault in plaintext and holds what is needed to draw the window and build the tray before any password is entered: theme, density, window geometry and position, start-minimised, minimise-to-tray, list sort order. It contains no secrets and nothing governing when the vault locks.

Everything governing locking travels inside the encrypted body as `SecurityPolicy` (§6.4): idle timeout, lock-on-minimise, lock-on-focus-loss, hide grace period, clipboard clear delay. These are read only while unlocked, so placing them inside creates no chicken-and-egg problem, and it puts them under the GCM tag where an edit is detected rather than obeyed. A plaintext idle timeout is a file an attacker rewrites to disable the control §8.3 exists to provide.

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

The CRC covers the ten bytes ahead of it as well as the header JSON, and is checked before any key is derived, which is what separates a damaged file from a mistyped password (§6.7). Those ten bytes decide what the reader takes the file to be and where it believes the header ends, so the same step checks them: a damaged version byte is reported as damage rather than as a vault from a TAuth that does not exist, and a corrupted `headerLength` fails whether or not the bytes it slices happen to parse. The CRC is unkeyed and detects damage rather than tampering; tampering is the GCM tag's job.

The layout of those ten bytes is fixed for every format version, so a reader can check the preamble of a file written by a version it does not know before reporting that it cannot read it.

### 6.3 Header JSON

```json
{
  "v": 1,
  "vaultId": "<base64, 16 bytes>",
  "salt": "<base64, 16 bytes>",
  "wrap": { "nonce": "<base64, 12 bytes>", "ct": "<base64, 48 bytes>" },
  "body": { "nonce": "<base64, 12 bytes>" }
}
```

`wrap.ct` is 48 bytes: the 32-byte DEK encrypted under the KEK plus the 16-byte GCM tag. The wrap uses an empty associated data field.

`salt` is the only part of the derivation the file carries, because it is random per vault and cannot be derived from anything else. The function, its version, the lane count and the cost are fixed by the format version `v` (§6.5), so the header holds no copy of them: a stored copy could only disagree with the version implying it, and a cost read from a plaintext header would be an allocation of the attacker's choosing.

`vaultId` is a random 16-byte identifier generated at creation. It binds keyring entries to a specific vault (§16.3); the password-only design does not read it.

The header is serialised with `encodeDefaults = true`, `explicitNulls = false`, and no pretty-printing, so byte-for-byte reproduction is deterministic. The exact header bytes read from disk are retained and reused as associated data rather than re-serialised, which removes any dependence on serialiser stability.

Deserialisation tolerates unknown keys, so a vault written by a later version carrying additional header fields fails on the version check rather than on a parse error.

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

`id` is a UUIDv7 in canonical form, giving creation-ordered identifiers. `orderIndex` holds explicit user ordering and is renumbered densely from zero on every write. `secret` is the base32 string exactly as imported, not the decoded bytes, so a round-trip export reproduces the original URI.

`type` is `totp` or `hotp`. `period` applies to `totp` and is null on `hotp`; `counter` applies to `hotp` and is null on `totp`. The pairing is enforced on deserialisation, and a `hotp` entry with a null counter is `VaultError.Corrupt` rather than a silent default to zero, which would generate codes from the wrong position.

An entry meets the rules §5.5 places on a URI: `secret` is base32 decoding to at least one byte, `issuer` and `accountName` are well-formed UTF-16, `accountName` carries no colon, and `issuer` is absent rather than empty. The body is attacker-writable and JSON carries an unpaired surrogate through as readily as any other escape, so a body failing any of them is `VaultError.Corrupt` at the read rather than a failure at the first code the entry is asked for or a throw out of the URI constructor when the entry is exported.

Absent `policy` fields take the defaults shown, so a body written before a field existed opens unchanged; an absent `policy` object is the full default set. Defaults are conservative in every case, so a truncated or partially-understood policy locks sooner rather than later. Changing a policy value is an ordinary vault write and requires an unlocked session.

The three durations are rejected when negative, which makes the body `VaultError.Corrupt`. Zero disables a control and is a choice the user can make; a negative reads as disabled to every check while naming a duration, so it would switch a control off in a body that appears to set it.

### 6.5 Cryptographic parameters

| Purpose | Algorithm | Parameters |
|---|---|---|
| Password → KEK | Argon2id | version 0x13 (19), m = 65536 KiB (64 MiB), t = 3, p = 1, 16-byte random salt, 32-byte output |
| KEK → DEK wrap | AES-256-GCM | 12-byte random nonce, 128-bit tag, empty AAD |
| DEK → body | AES-256-GCM | 12-byte random nonce, 128-bit tag, AAD = file prefix |
| Random material | `java.security.SecureRandom` | default provider, no seeding |

Argon2id parameters exceed the OWASP minimum of m = 19456 KiB, t = 2, p = 1. On the reference machine (§15) they cost about 175 ms:

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

The two-level hierarchy — password to KEK to DEK to body — makes password change an O(1) header rewrite rather than a full re-encryption, and is the structure §16's keyring path attaches to. Replacing the DEK is a separate operation (§7.1), because a password change alone leaves a leaked DEK working.

**Nonce discipline.** A fresh 12-byte nonce is generated for the body on every write and for the wrap on every KEK change. Reusing a nonce with the same key across two plaintexts breaks GCM completely, so the rule is structural rather than procedural: every nonce is drawn inside `VaultCodec` and no function it calls accepts one from outside it.

### 6.6 Write procedure

1. Serialise the body to JSON bytes.
2. Generate a fresh body nonce.
3. Build the header JSON with the wrap block and the new body nonce.
4. Assemble the prefix (magic, version, length, CRC, header) and use it as AAD.
5. Encrypt the body under the DEK.
6. Take the lock.
7. Write prefix and ciphertext to `vault.tauth.tmp` in the same directory. On POSIX the owner-only mode is a creation attribute, read back before any ciphertext is written; elsewhere the file is created with no mode of its own and the directory's inheritable access control entry restricts it.
8. `FileChannel.force(true)` on the temp file.
9. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
10. Force the parent directory's channel, so the rename itself is durable and not only the bytes it renames.

The rename at step 9 is the commit point: a reader sees the previous file whole or the new one whole, never a mixture, and no spare copy is needed to make that true. Where `ATOMIC_MOVE` is unsupported the move falls back to `REPLACE_EXISTING` and logs a warning.

A save that fails at step 7 or 8 deletes `vault.tauth.tmp`: it holds part of a file nothing can read, and the vault file has not been touched. A save that fails at step 9 leaves it in place and logs its path, because it holds the whole new vault and, where the rename was not atomic, can be the only complete copy of it.

Steps 1 to 5 refuse a body the read procedure would reject: a `v` the reader does not support, a header past the 64 KiB bound of §6.7, or a file past the 16 MiB whole-file ceiling. Nothing reaching step 7 is something step 9 would commit as a vault this version cannot open.

Durability across a power cut is best effort and is not reported: on macOS, from JDK 21, `FileChannel.force` is `fcntl(fd, F_FULLFSYNC)` for a local mount and plain `fsync` for any other (JDK-8080589), and `fsync` returns before the drive empties its write cache; a network store can acknowledge a flush its server has not performed; Windows will not open a directory as a channel.

The lock at step 6 is an exclusive `FileLock` on a sibling `vault.lock`. It is advisory across processes on the same machine and stops two instances, or an instance and an export, from interleaving writes. It is a separate file so that locking never opens the vault for writing, and it is opened without following links, so a symbolic link left at that name opens nothing and, where it dangles, creates nothing.

Ahead of the lock, the directory holding the vault is restricted to its owner. On POSIX it is created at `0700` and it alone: a creation attribute reaches every parent `createDirectories` makes, and those parents are the data root shared with every other application, so they keep the mode a directory is created with. A directory that is anything other than `0700` is chmodded to it and the mode is then read back, so a mount that accepts a chmod and discards it produces `VaultError.Io` instead of a success that leaves the directory traversable. A directory reached through a symbolic link is left as it is found, because a chmod through the link tightens whatever it points at; the mode read back is that of the directory the link resolves to.

Elsewhere the directory is created with no mode of its own and its access control list is set to a single ALLOW entry for the owner, carrying `FILE_INHERIT` and `DIRECTORY_INHERIT`, which a file created inside it inherits. A path with no access control view produces `VaultError.Io`. Nothing is read back, and the entry is written through a symbolic link to whatever the link resolves to, so the mode-discarding guarantee and the symbolic-link exemption above hold on POSIX alone.

Reading is not gated on the directory's mode: a vault already there stays readable.

### 6.7 Read procedure

1. Read the whole file into memory through one open descriptor, whose size decides the ceiling, so the file measured is the file read. A file past the 16 MiB whole-file ceiling produces `VaultError.Corrupt` before the read is attempted, since a hostile size raises `OutOfMemoryError` rather than an exception that can be caught and converted.
2. Verify the magic. A file that does not carry it produces `VaultError.Corrupt`.
3. Parse `headerLength` and slice the header JSON. A length exceeding the file size, or exceeding a 64 KiB sanity bound, produces `VaultError.Corrupt`.
4. Verify the CRC over bytes `0`–`9` and the header JSON. A mismatch produces `VaultError.Corrupt`, before any key is derived.
5. Verify the format version. An unknown version produces `VaultError.UnsupportedVersion`.
6. Read the header's `v`, then deserialise the header. A `v` other than the supported header version produces `VaultError.UnsupportedVersion`.
7. Derive the KEK from the supplied password and the salt, then unwrap the DEK.
8. Decrypt the body with the retained prefix as AAD. An authentication failure produces `VaultError.IntegrityFailure`, which the UI reports as tampering or corruption rather than as a wrong password.
9. Read the body's `v`, then deserialise the body. A `v` other than the supported body version produces `VaultError.UnsupportedVersion`.

`v` is read out of a document on its own, ahead of the rest of that document, because only the version says what the rest of it means: a later version is free to give a field a type this one never used, and deserialising those fields under this version's model would report a vault from a later TAuth as damage.

The parser takes a quoted integer wherever this format specifies a number, so a `v` of `"1"` reads as version 1 on the header path and on the body path alike, and an entry's `period`, `digits` and `orderIndex` read the same way quoted. The two reads of `v` go through the same parser and so agree on the value; the writer emits a number.

A document that will not deserialise produces `VaultError.Corrupt` naming which document it was, and nothing out of the document itself. A parser reports the input it stopped in, and that input is the salt with the wrapped DEK on the header path and every entry's secret on the body path.

The order of steps 4 and 5 is what separates the three failures a user can act on. GCM reports a wrong key and a rewritten ciphertext identically, so without the checksum first, damage to the salt or the wrap block would surface as a wrong password. With it, damage to the header fails at step 4 as `Corrupt`, damage to the body fails at step 8 as `IntegrityFailure`, and a version byte the writer never wrote is damage rather than a release that does not exist.

`WrongPassword` at step 7 therefore means the password is wrong, or the salt or wrap block was rewritten by someone who repaired the unkeyed checksum too. Those two are not separable, so the message says the password did not work and claims nothing about the file.

### 6.8 Vault creation

On first run, or where the resolved path holds no file, the UI presents the create flow. Creation generates a 16-byte salt, a 16-byte vault id and a 32-byte DEK from `SecureRandom`, derives the KEK from the chosen password, wraps the DEK, and writes an empty entry list. The file exists before the user adds any account, so a failure to write surfaces immediately rather than after they have entered a secret.

A path that already holds a vault produces `VaultError.VaultFileExists` and no write, since the write would replace every secret in that vault with those of a new one. The check is not atomic with the write it guards: it stops the create flow from overwriting a vault, not a second process from writing one in between, which §10.3 covers.

The session reads the file back and opens it, so the create flow lands on the account list rather than on a password prompt, and a write that did not land as it was sealed is found at creation rather than at the next unlock. That is a second Argon2id derivation, paid once per vault. Reading the file rather than reopening the buffer is what makes the check about the vault on disk: the buffer opens by construction, whatever the filesystem did with it.

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

**Change password.** Requires an unlocked session and re-entry of the current password, verified by a fresh derivation and unwrap rather than against session state. Generates a new 16-byte salt, derives a new KEK from the new password, re-wraps the existing DEK, and rewrites the file with a fresh body nonce. The body plaintext is unchanged; the DEK is unchanged.

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
    data class Unlocked(val entries: List<UnlockedEntry>, val policy: SecurityPolicy) : SessionState
}
```

`VaultSession` owns a `MutableStateFlow<SessionState>` and holds the DEK in a private `SecureBytes`. The DEK is never exposed through the public API; callers request operations, not keys.

`UnlockedEntry` is a `VaultEntry` without its `secret`: the id, type, issuer, account name, algorithm, digits, period, counter, creation time and order index the account list and the edit screen draw. The session decodes each secret into key bytes it zeroes on lock, and the base32 text those came from stays alive in the open vault's parsed body (§8.4); an entry the UI holds carries neither form, so no entry the session publishes is a credential. What a composable holds of its own is another matter: the add screen keeps the pasted URI and the typed base32 in `String`s for the life of the screen. The policy travels with the state because it lives in the encrypted body, which is where a lock trigger and a clipboard clear have to read it from.

The state answers `NoVault` from whether a file sits at the resolved path, and creation refuses a path that already holds one (§6.8).

### 8.2 Lock

```kotlin
fun lock(reason: LockReason) {
    pendingLock?.cancel()               // a timer outliving the lock fires at a later session
    pendingLock = null
    secrets.values.forEach { it.destroy() }
    secrets.clear()
    vault?.close()                      // fills the DEK with zeros, then drops the reference
    vault = null
    _state.value = SessionState.Locked(reason)
    clipboard.clearIfHoldsOwnValue()
}

fun scheduleLock(reason: LockReason)
fun cancelScheduledLock()
```

Everything down to the state assignment runs under the lock guarding the session's fields, so a lock arriving from the tray and an unlock finishing on a worker cannot interleave over one key. The clipboard call is outside it: the platform clipboard blocks under contention, and stalling every other operation on the session for the length of that is worse than clearing a moment after the state says locked.

The session reaches the clipboard through a one-call interface it holds and the shell implements, rather than by publishing something a collector acts on. A lock for an exit is followed by the process ending, and a collector gets no turn to run in between, which would leave a code or an `otpauth://` URI on the clipboard of a machine whose vault is shut.

`scheduleLock` reads the grace period and the arming from the `SecurityPolicy` the session holds; a zero grace period locks at once. It is a no-op against an already-locked vault, so a caller never has to ask what state the session is in. `cancelScheduledLock` drops a pending timer without locking. Both are on the session rather than on the window because the policy lives in the unlocked body and is unreadable exactly when it is irrelevant.

The timer belongs to the application scope the session is constructed with, so shutting the application down cancels a pending lock. Three things drop one: `cancelScheduledLock`, a `lock` from any source, and that scope ending. A second `scheduleLock` arriving while one is pending is ignored rather than restarted, since the window has been off the screen since the first trigger fired and a later trigger must not push that deadline out.

Triggers arriving while a derivation is running are held and replayed once the body is open, and the first the policy arms takes effect. None can be judged when it lands, because the policy that says whether it is armed is still encrypted; holding only one would let a disarmed reason swallow an armed one that followed it, and the window would come back unlocked from the hide the arming exists to catch. A derivation that fails keeps them, because the window is still wherever the trigger found it, and the user returning to it clears them through `cancelScheduledLock`.

A `lock` landing while a derivation runs takes precedence over it. The unlock destroys the key it derived rather than installing it, leaves the state the lock set, and returns `VaultError.VaultClosed`: the vault the user closed stays closed, and opening it is another password entry.

A password change and a DEK rotation (§7.1) reach that point having already written the file, since the vault they reopen is the one they have just committed. A lock landing there leaves the rewrite standing and only the session shut, so `VaultClosed` says the session closed, not that the change was refused.

### 8.3 Lock triggers

| Trigger | Source | Default |
|---|---|---|
| Window hidden to tray | `onCloseRequest` setting `visible = false` | always |
| Window minimised | `WindowState.isMinimized` observed via `snapshotFlow` | on |
| Explicit "Lock now" | in-app button and tray menu item | always |
| Idle timeout while visible | no pointer or key input for N minutes | on, 5 min |
| Application exit | shutdown hook | always |
| Window focus lost | `LocalWindowInfo.current.isWindowFocused` | **off** |

Every configurable trigger reads the `SecurityPolicy` in the unlocked vault body, never `preferences.json`. The policy is available whenever it is needed, since a trigger can only fire against an unlocked vault, and it is unavailable exactly when it is irrelevant. Editing the plaintext preferences file cannot extend a timeout or disable a trigger.

Focus loss defaults to off: copying a code and switching to a browser is the application's most common interaction, and locking on focus loss makes every such action cost a full Argon2id re-derivation.

Hiding to the tray carries no switch, only the grace period below. It is what §1 means by the vault being unlocked while the window is on screen, and nothing else would catch a window that stayed hidden: the idle timeout runs while the window is visible, so a hidden window that did not lock would hold the key until the process ended.

The exit trigger is a JVM shutdown hook, covering a normal exit, `SIGINT` and `SIGTERM`, and the in-app quit paths that lock before they call it. `SIGKILL`, a power loss and a JVM crash run no hook at all, and a process ended that way leaves the key wherever the operating system leaves its pages and the clipboard holding whatever was last copied. The hook can also run after the toolkit is down, so its zeroing lands where its clipboard clear may not.

The idle trigger is held off while §9.7's QR dialog is on screen, which is the one thing this application draws to be read rather than typed at: a symbol being scanned looks to the watch exactly like an empty room. The hold ends with the dialog, and the dialog closes itself after a minute without interaction, so what it costs is bounded by that minute rather than by the person remembering to close it. No other trigger is affected, and a window hidden or minimised over an open dialog locks on its own trigger as it would over any other screen.

A configurable grace period delays the hide-triggered and minimise-triggered lock. Default is 0 seconds, meaning immediate; the options are 0 / 30 s / 2 min. The timer is cancelled if the window becomes visible again before it fires, and runs as a cancellable coroutine on the application scope, not as a `java.util.Timer`, so shutdown cancels it deterministically.

### 8.4 Handling of decoded secret material

Base32-decoded secrets are held as `SecureBytes` and are never converted to `String`. `SecureBytes.destroy()` fills the backing array with zeros and marks the holder unusable; a later lend refuses to run its block and returns `null`, which the vault path reports as `VaultError.VaultClosed`.

JVM `String` instances are immutable and cannot be wiped, and this boundary is **not** enforced by the API. `VaultEntry.secret` holds the base32 text as a `String`, `OpenVault` retains the whole `VaultBody`, and kotlinx's lexer materialises every string token before any deserialiser sees it, so each stored secret exists as an unwipeable `String` for as long as the vault is open. The rule above forbids a *decoded* secret in a `String`, and the base32 text passes it by the letter while being the credential in full. The session decodes every secret into `SecureBytes` at unlock and publishes entries carrying neither form, so no entry the UI receives is a credential. What a screen collects for itself is: an `otpauth://` URI pasted into the add screen and the base32 an `EntryDraft` holds are both the credential in full, in `String`s living until the screen leaves composition. The decoded bytes are lent to a block and never handed over, and that lend is `internal`, so the public API gives out nothing while any caller inside `:shared` can ask for one. What keeps the encoded form alive is the open vault the session holds for its key, so a heap dump of an unlocked vault still yields every secret in base32; ending that needs `OpenVault` to give up its parsed body, deferred in §16.8.

`SecureBytes.adopt` is the only constructor and takes ownership of the array, so a caller cannot hold a second reference by accident. The destroyed flag is `@Volatile`, written after the zeroing and read before the array, so a thread that observes the flag also observes the zeroed bytes rather than a cached view of a live key. Nothing zeroes a holder dropped without `destroy()`; the discipline is the API contract, not a collector hook.

Key material is lent to a block rather than handed out: the lend is the only member that gives a caller the array, and `destroy()` and that block exclude each other. What the lend guarantees is that no `destroy()` runs while the block holds the array, not that the block cannot keep it past its own return. A lock arriving during a write waits for that write to finish, and a write beginning after the lock finds the key gone and fails: zeros reaching a seal part-way through would leave a body encrypted under them beside a header carrying the real wrapped key, and the rename would commit that over the previous file. The common standard library offers atomics but no mutex, so the exclusion is an `expect`/`actual` primitive in `crypto` like the other platform primitives.

The master password is handled as `CharArray` from the text field through to the KDF call, and zeroed after derivation. Compose's `TextField` state is `String`-based, so every field that takes the master password is a `BasicTextField` over a `CharArray`-backed holder. The container, the minimum height and the focus indication come from Material's own decoration through `decorationBox`, so keeping the holder costs nothing visually.

Generated six-to-eight digit codes are `String`. They are short-lived, low-value, and needed as `String` for display and clipboard.

This reduces but does not eliminate residue. A heap dump taken while unlocked contains everything; one taken after locking contains the zeroed arrays plus whatever the garbage collector has not yet reclaimed of transient `String` instances.

The KDF adds residue TAuth cannot reach. `Argon2BytesGenerator` zeroes its memory blocks when it returns each to the block pool, and leaves three things for the collector: the UTF-8 encoding it makes of the password `CharArray`, the 72-byte H0 prehash seeds, and the 1024-byte scratch block holding the last block digested. H0 is enough to finish the derivation without the password, so it is worth as much as the key it produces. All three are locals of the generator, so the exposure lasts from the derivation until the collector reclaims them.

### 8.5 Code ticker

While unlocked and the window is visible, a single coroutine emits on a one-second cadence, computing every visible TOTP entry's current code. Entries scrolled out of view are not computed. A hidden window consumes no CPU.

The coroutine is the collection of a cold flow, so hiding the window cancels the collection and with it the ticker. A lock ends the flow instead, and needs no collector to do it: the flow reads the session state, and a state that is no longer `Unlocked` completes it after one empty emission, which leaves no row holding a code from a session that has closed. The list publishes the ids of the rows it has on screen, since the ticker cannot see them for itself, and a scroll recomputes at once rather than at the next second.

HOTP entries are outside the ticker entirely. Their codes change only on explicit request (§5.6), and recomputing one on a timer would advance the counter without the user asking.

`TotpCode` carries the code, the period, and the instant that period ends. The countdown is drawn from that instant against the clock at frame time, so the ring sweeps continuously while the ticker keeps its one-second cadence. That cadence is what keeps an HOTP row and an off-screen row uncomputed, and drawing must not change how often a code is computed.

Each row shows the remaining fraction of its own period, since entries may have different periods. Expiry is reported in more than colour (§9.10). Where the platform asks for reduced motion, the ring steps on the tick instead of sweeping.

---

## 9. User interface

**Secret disclosure gate.** Three actions put a shared secret where something other than TAuth can read it: copy `otpauth://` URI (§9.4), show QR code (§9.7), and plaintext export (§9.9). Each emits a complete credential, and the medium — clipboard, screen, file — is outside the vault's protection once the action completes. All three carry the same gate: re-entry of the master password even when the session is unlocked, and a one-line statement of what is about to leave the vault. Copying a generated code is not in this set; a code expires or is consumed, a secret does not.

The interface is opened many times a day for a few seconds each, and the whole of an interaction is: appear, find one account, put its code on the clipboard, leave. The measure of every screen is how few actions sit between the window opening and a code on the clipboard.

### 9.1 Navigation

A single window. Routing is driven by `SessionState`, not by a navigation library:

- `NoVault` → create-vault screen
- `Locked` → unlock screen
- `Unlocking` → the screen that asked for the password, showing its progress. A creation, an unlock and a settings action that rewrites the vault all run through this state, and the state alone does not say which asked for it. A derivation started from settings leaves the settings screen standing, since routing away would take the user off the control they used for the length of a derivation.
- `Unlocked` → account list, with add / edit / settings / import preview as full-screen destinations within the unlocked graph. The preview is the one of the four the user does not route to: it stands for as long as there are rows to decide about, so reading a file opens it and finishing with it returns to the settings screen the file was chosen from.

### 9.2 Create vault

Master password field with confirmation and a strength meter. A prominent, non-dismissable note states that the master password cannot be recovered and that losing it means losing every stored secret. It is acknowledged with a checkbox before the create button enables.

Minimum password length is 8 characters, enforced. The strength meter is advisory — length, character-class diversity, and a check against a small embedded list of common passwords — and never blocks submission above the minimum length.

### 9.3 Unlock

Password field with a reveal toggle, auto-focused. Enter submits. The derivation blocks the button and shows a progress indicator for its duration.

Failed attempts show an inline error. There is no attempt counter and no lockout: the vault is a local file, so a UI rate limit obstructs the legitimate user without impeding an attacker who can copy it.

Where the previous lock had a reason worth reporting — an idle timeout in particular — the screen shows it as a subtitle, so the user understands why they are being asked again.

### 9.4 Account list

A scrollable list, compact by default, with a density choice (§9.10). The code is the most prominent element of a row, since it is what the interaction exists to reach.

A TOTP row shows the account's mark, issuer, account name, the current code grouped for readability (`123 456`), and a circular countdown. Activating it copies the code and shows a transient confirmation with the clipboard clear countdown.

An HOTP row shows the mark, issuer, account name, the current counter, and a generate control in place of the countdown. It displays no code until the user asks for one, because displaying one consumes a counter value (§5.6). After generation the code stays on screen with a copy affordance until the row is collapsed, the list is left, or the vault locks; the generate control is disabled for a short interval afterwards so a double-tap does not silently burn two counter values. A failed vault write leaves the counter unchanged and shows no code.

Above the list: a search field filtering on issuer and account name, case-insensitively and on substring match; a sort control (manual order, issuer A–Z, recently added); a lock button; a settings button, which is where the destination of §9.8 is entered; and an add button.

**Keyboard path.** The search field takes focus as the window opens. The arrow keys move the selection through the list, Enter copies the selected row's code, and Escape leaves the current destination. Every action a pointer reaches is reachable from the keyboard, reordering included.

Manual reordering by drag, and by a keyboard equivalent on the selected row. `orderIndex` is renumbered and the vault is written on drop.

Reordering acts only while the list is in manual order and the search field is empty. A drop yields a position in the whole vault, so it can be read off the list only when the list is showing the whole vault in the order the vault stores it; under either of the other two orderings, or with a query hiding rows, a drop would renumber by a position nothing on screen names. The handle is inert in those states rather than absent, so the row does not change shape as a query is typed.

Row overflow menu: edit, copy code, copy `otpauth://` URI, show QR code (§9.7), delete. Copying the URI is a secret disclosure and carries the gate stated at the head of §9. Delete requires a confirmation dialog naming the account, and is irreversible; recovery requires an export taken earlier.

A copied URI is subject to the same clipboard clear delay as a copied code (§11), matched on the exact string that was placed there.

A code changing is announced politely, so a screen reader is not interrupted every period.

An empty vault shows an empty state naming the three paths §9.5 offers: pasting an `otpauth://` URI, reading an image of the QR code, or typing the details by hand.

### 9.5 Add account

Three input paths in one screen:

1. **Paste URI.** A text field accepting `otpauth://`. Parses on input and shows a live preview of the resolved fields, or the specific parse error.
2. **QR image.** A file picker filtering on PNG, JPEG, GIF and BMP. The image is decoded with ZXing's `MultiFormatReader` over a `BufferedImageLuminanceSource` with `HybridBinarizer`, through `GenericMultipleBarcodeReader` so every code in the image is read rather than the first: one screenshot can hold a page of them. The accounts among the payloads are what is offered — a code can be a payment link, a wireless password, anything at all — and one account is taken without asking while several are presented as a selection list naming each by its issuer and account name and nothing else, since the list stands on screen while it is read. An image holding codes but no account, and an image holding no code, are different things to the person holding it and are not one sentence. The path converges on the same field a paste fills.
3. **Manual entry.** Type (TOTP or HOTP, defaulting to TOTP), issuer, account name, secret, and an advanced section for algorithm, digits, and either period or starting counter according to type. The secret field validates base32 on input, against the secret alone rather than through the whole form: the entry model refuses an empty account name before it reaches the secret, so a form checked only through that would answer a base32 mistake by naming a different field. The counter field accepts an unsigned 64-bit value and defaults to 0.

All three converge on the same preview showing the resolved entry. A TOTP preview carries a live sample code. An HOTP preview shows the starting counter and the code that counter would produce, computed without persisting anything, so verifying the entry does not consume a counter value before the account exists. Saving writes the vault immediately.

Screen-region QR capture is deferred (§16.8).

### 9.6 Edit account

Issuer and account name are freely editable. Algorithm, digits, and period or counter are editable behind an "advanced" disclosure carrying a warning that changing them invalidates codes unless the server side matches. The secret is not editable; changing a secret means deleting and re-adding, which prevents a mistyped edit from silently destroying the only copy of a credential. The type is not editable, since TOTP and HOTP take different parameters and switching between them discards one of them.

The counter is editable because resynchronisation requires it: a client that has generated codes beyond the server's look-ahead window can only recover by being set back or forward (§5.6). The field shows the stored value and accepts any unsigned 64-bit value.

### 9.7 Show QR code

A dialog reachable from the row overflow menu, rendering the entry's `otpauth://` URI as a QR code so another authenticator — a phone, a second desktop, a hardware token's companion app — can enrol the same account by scanning the screen. This is the intended migration path off TAuth and the counterpart to QR import in §9.5.

**Encoding.** `QRCodeWriter.encode(uri, BarcodeFormat.QR_CODE, 1, 1, hints)` from `zxing-core`, with `EncodeHintType.ERROR_CORRECTION = ErrorCorrectionLevel.M`, `EncodeHintType.MARGIN = 2` (quiet zone, in modules), and `EncodeHintType.CHARACTER_SET = "UTF-8"`. Level M matches what Google Authenticator's own provisioning codes use and keeps the symbol small: a 160-bit secret with issuer and account name yields a URI of roughly 100–150 characters, a version 6–7 symbol at 41–45 modules square.

The writer scales its result up to whole multiples of the size asked of it and never returns less than the symbol, so a request of one pixel is what returns the module grid itself. The rendering below is stated in modules and needs that grid rather than a bitmap resampled to a size chosen before the canvas is known.

`zxing-core` is a JVM library and the dialog is a screen, so what crosses into `:shared` is a module grid rather than a `BitMatrix`: `QrSymbol` carries the dark modules with the quiet zone already in them, and `QrEncoding` is the seam the shell hands its encoder over through, in the manner of the clipboard and the export destination.

**Rendering.** The `QrSymbol` is drawn onto a Compose `Canvas` as one filled rectangle per dark module. The module size is `floor(canvasPx / symbol.width)` and the symbol is centred with the remainder as extra quiet zone, so no module straddles a fractional pixel boundary. Fractional module edges blur under scaling and scanners reject the result at small sizes far more often than the visual difference suggests.

The symbol is always dark-on-light with a light quiet zone, independent of the application theme. Inverting module polarity for a dark theme breaks a large fraction of scanners, so the dialog draws its own light surface behind the symbol rather than inheriting the theme background. Minimum rendered size is 240×240 logical pixels; the dialog scales the symbol up to the available space in whole-module increments. That minimum is held beside the symbol rather than in the palette, because a theme free to shrink the symbol is free to make it unscannable.

**Actions.** Beneath the symbol: the issuer and account name as plain text, so the user can confirm they are exporting the account they intended; "Copy URI"; and "Save as PNG", written with `0600` permissions. The image is rendered from the `QrSymbol` the screen is drawing rather than from a second encode of the same URI, so what lands in the file is the symbol the user was looking at; its modules are laid down whole for the reason the screen lays them down whole. The save is the shell's, since only the shell has a filesystem, and the screen reports the request and whatever comes back. It is offered only over a symbol: a URI the format cannot carry leaves nothing to write.

**Gating.** Displaying the QR places a complete credential on screen in machine-readable form; a photograph, a screenshot, or an active screen-sharing session captures it in full. The dialog carries the gate stated at the head of §9. It closes after 60 seconds without interaction, and suppresses the idle lock timer while open so the vault does not lock underneath a symbol the user is mid-scan.

For an HOTP entry the encoded URI carries the counter as it stands when the dialog opens. Scanning it clones the entry at that position rather than at the position the other authenticator will next need, so the dialog states the counter in text beneath the symbol alongside the issuer and account name.

### 9.8 Settings

Reachable only from the unlocked graph, because the groups marked *policy* below are stored in the vault body and changing one is a vault write.

- **Security** *(policy)* — change master password; re-encrypt vault (DEK rotation).
- **Locking** *(policy)* — idle timeout (off / 1 / 5 / 15 min); lock on minimise; grace period before hide-triggered lock; lock on focus loss (default off).
- **Clipboard** *(policy)* — clear delay (off / 10 / 20 / 60 s).
- **Appearance** *(preference)* — theme (system / light / dark); density; list sort order.
- **Tray** *(preference)* — minimise to tray; start minimised. Both disabled with an explanation when no tray is available.
- **Data** — vault file location with a reveal-in-file-manager action; the encrypted export; the unencrypted export; import. Each of the three reports in a slot of its own: they fail over different files, and one message naming another's would send the user to the wrong place.
- **About** — version, licence, and the security notes describing what the vault protects against and what it does not.

The screen is a set of controls, not a document. Each control carries its own label and, where it needs one, a single line beneath it; standing paragraphs of explanation belong to the About group. The groups are sized for the window of §10.1 rather than for a full screen.

A policy change is applied in memory and written with the vault before the control reflects it, so a failed write leaves the stored policy and the displayed state in agreement. A preference change writes `preferences.json` and needs no unlocked vault, though the screen that hosts it does.

The distinction is stated once in the screen's header rather than repeated per control: appearance and tray settings live in a plaintext file; everything governing locking lives inside the vault and cannot be changed without the master password.

### 9.9 Export and import

**Encrypted export** produces a copy of the vault file. It is the recommended backup and requires no additional confirmation.

The copy carries the whole vault, so it is created the way §6.6 creates the vault itself: `0600` as a creation attribute, read back before any ciphertext is written, and the write made into the channel the creation opened rather than back through the name it was created under. A destination the user picks is a directory another local user may be able to write to, which is where the difference between that and a `chmod` after the write is the whole file. Where the destination filesystem carries no POSIX modes, an owner-only access control entry is set on the empty file instead, and a filesystem offering neither refuses the export rather than writing it.

Every file TAuth writes outside its own directory goes down that one path, because each carries a secret in a form something other than TAuth reads: this copy, §9.7's saved QR image, and the plaintext export below. What differs between them is what the destination is asked for and how a failure is worded, not how the file is created.

**Plaintext export** produces a JSON file or a list of `otpauth://` URIs, carrying the gate stated at the head of §9 and a dialog stating that the output is unencrypted. This is the migration path to other authenticators and is the reason plaintext export exists at all. HOTP entries export with their current counter, which is a point-in-time snapshot: codes generated in TAuth after the export move the vault ahead of the exported file.

The two formats differ in what survives being read back. The URI list is one `otpauth://` URI per line, each line ended, which is what another authenticator enrols from and is all it enrols from. The JSON document is `{"v": 1, "entries": [...]}` over the entry objects of §6.4 unchanged, so it carries the entry ids, the creation times and the stored order, none of which a URI has a field for. The `policy` object is not exported: it governs this application and enrols nothing. Both are written in the order the vault stores rather than the order the entries happen to sit in, so a re-import restores the list as it was left.

The warning is read before the password is asked for, since the password is what the user is being asked to spend on a decision they have not yet been shown. Both formats are offered there, and the dialog states the counter snapshot as well as what the file holds.

**Import** accepts a plaintext export or a newline-separated list of `otpauth://` URIs, shows a preview with per-entry validity, and detects duplicates by `(issuer, accountName, secret)`, offering skip or add-anyway per duplicate.

A file opening with `{` is read as a document and anything else as a list of URIs, so a document that will not parse reports itself rather than reading as a great many broken URIs. Reading is per line and per element: one that will not parse is refused on its own, naming where it sat in the file and stating the rule it broke rather than the value, since the value is a credential and the preview is on screen. Blank lines are passed over. A document that will not parse, and one carrying no `entries` at all, each produce nothing rather than a preview of nothing.

The secret half of the duplicate key is compared with padding and case set aside, which are what differ between two spellings of one key; nothing on this path decodes a secret. A file carrying one account twice offers the second as a duplicate of the first, on the same rule. The comparison reads the secrets the vault holds, so it happens in the session rather than on a screen, whose entries carry none.

An account arriving takes an id of the receiving vault's making, since one carried in may already name an entry there; the creation time is kept where the document carries one, and is the moment of the import where a URI does not. Accepted accounts are added in one vault write: added one at a time, a batch stopping half way would leave the file holding a part of what the user accepted.

The preview counts what will be added, what the vault already holds and what could not be read, and lists a row per account under its issuer and account name. A duplicate carries the choice above and opens on skipping it; every other account is taken. A refused row names where it sat in the file and the rule it broke, and no row puts a secret on the screen. Nothing can be added while the choices leave nothing to add. The rows carry every secret the file offered, so they end with the write that takes them, with the preview being left, and with the vault being locked.

### 9.10 Appearance

The palette is a flat dark surface under a single accent, with the light scheme drawn from the same tokens. Colours, spacing, type and iconography come from the theme; no screen holds a raw colour or a raw dimension. The mark of §4.1 is the brand seed.

A face is bundled for all three platforms rather than taken from each, so the application reads the same everywhere. The generated code keeps a monospace face, so a digit changing moves none of the others. Every other numeral — the HOTP counter, the countdown seconds — is set in tabular figures for the same reason.

Controls carry icons beside their labels, drawn from one set held in the theme. An account carries a generated mark: its initial over a colour derived from its issuer and account name. **No brand icon is shown and none is fetched.** The issuer is a field of an `otpauth://` URI that anything can write, so a real company's mark beside an account would be the application vouching for a string nothing authenticates, and fetching one would tell whoever serves it what accounts the vault holds.

Density is a user choice — compact or comfortable — applied to the account list and stored in `preferences.json`.

Two things are drawn outside the theme and say so where they are drawn: §9.7's QR dialog, which is dark-on-light regardless of theme and holds its own minimum size, and the shell's tray and window icon, which the desktop draws on surfaces no composition reaches.

Every state the interface reports in colour is also reported in another channel: the countdown states its expiry in text a screen reader announces as well as by the ring's colour. Where the platform asks for reduced motion, animation is dropped rather than shortened.

---

## 10. Tray and window lifecycle

### 10.1 Structure

```kotlin
// The role is claimed outside the composition, so a launch that hands its request over reaches
// neither the window below nor the session and the vault behind it (§10.3).
fun main() = startUnlessSuperseded(SingleInstance().claim(), ::runTAuth)

private fun runTAuth(role: InstanceRole) = application {
    val session = remember { VaultSession(...) }
    val opening = remember { store.load() }                 // plaintext, pre-unlock
    val prefs = remember { PreferencesState(opening, store::save) }
    val hasTray = remember { isSystemTraySupported() }
    val startup = remember { WindowLifecycle.of(hasTray, opening) }
    val lifecycle = WindowLifecycle.of(hasTray, prefs.value)
    val windowState = rememberWindowState(isMinimized = startup.startup == StartupWindow.ICONIFIED)
    var visible by remember { mutableStateOf(startup.startup != StartupWindow.HIDDEN_TO_TRAY) }

    TAuthTray(
        isShown = lifecycle.isTrayShown,
        onShow = { visible = true },
        onLock = { session.lock(LockReason.Manual) },
        onQuit = { session.lock(LockReason.Exit); exitApplication() },
    )

    Window(
        onCloseRequest = {
            when (lifecycle.onCloseRequest) {
                CloseAction.HIDE_TO_TRAY -> visible = false
                CloseAction.EXIT -> { session.lock(LockReason.Exit); exitApplication() }
            }
        },
        visible = visible,
        state = windowState,
        title = APPLICATION_NAME,
        icon = tauthIcon(),
    ) {
        TAuthApp(session, prefs, shell)
    }
}
```

The window is small and stays small. It is resizable between a minimum and a maximum, minimises, and does not maximise: nothing it draws benefits from a full screen, and every destination is laid out for that width.

The preference document has a single owner. `PreferencesState` holds it, and every writer — the settings screen, the account list's sort control, the window geometry recorder — changes it through `update`, which derives the next document from the value the holder carries and then writes that. A writer copying its own field onto the document as the file held it at launch puts back every other field chosen since, and the geometry recorder writes without being asked, so it would do that on every move of the window.

Two things read that document at different times. The window opens where the document stood at launch, and the state it is given is remembered against that alone, so a preference changed later does not move a window the user has placed. What a close request does, and whether a tray icon stands, read the live value, so a tray preference changed in settings takes effect without a restart.

`shell` carries what §9.8's Data and About groups report and no screen in `:shared` can ask for itself: the vault file's location and a reveal action, the packaged version and licence, where an exported copy is written, and whether the tray settings are offered — which it takes from `WindowLifecycle` rather than asking the toolkit a second time, so the window and the screen answer that question the same way.

`WindowLifecycle.of` takes tray availability and the two tray preferences and answers what a close request does, where the window opens, whether a tray icon exists and whether the tray settings are offered. The window leaves the screen only where a tray icon can bring it back, so the answer turns on `isTraySupported && minimiseToTray` rather than on availability alone: a desktop with no tray and a user who turned the tray off both take the fallback of §10.2. Whether the settings are offered turns on availability alone, since those settings are the controls that set the preferences.

Minimising is the platform's own on every desktop and is not one of those answers. Hiding the window is the close request's alone, which is what leaves `WindowState.isMinimized` an observable thing for the minimise trigger of §8.3 to fire on and for `SecurityPolicy` to govern.

The window opens at the geometry §6.1 holds, clamped to the bounds the model enforces, and a position that is unset is left to the platform to choose. A move or a resize is written back once it settles, so a drag reaches the file as one write rather than as every position it passed through. A minimised or full screen window records nothing: the extent it reports is that state's rather than the one it returns to, and the geometry standing in the file is the one the window will come back to.

`isSystemTraySupported()` is `java.awt.SystemTray.isSupported()`, which §10.2 records as a proxy for the question the tray library actually answers.

Relock is driven by observing visibility, minimisation and focus rather than by wiring each call site:

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { WindowPresence(visible, windowState.isMinimized, windowInfo.isWindowFocused, shownBy) }
        .collect { presence ->
            when {
                !presence.isVisible -> session.scheduleLock(LockReason.HiddenToTray)
                presence.isMinimised -> session.scheduleLock(LockReason.Minimised)
                presence.shownBy == ShowSource.SHOW_REQUEST -> Unit
                else -> {
                    session.cancelScheduledLock()
                    if (!presence.isFocused) session.scheduleLock(LockReason.FocusLost)
                }
            }
        }
}
```

The window layer reports what happened and does not decide what it means; §8.2 states what the session does with it. The policy therefore never has to be passed through composables or read while the vault is closed.

A window standing on the screen is back whether or not the window manager gave it the focus, so the cancel turns on visibility and the focus loss is reported alongside it as a trigger of its own. A restore the desktop leaves unfocused would otherwise keep the relock its hide scheduled and fire it in front of the user. Focus reads from `LocalWindowInfo`, which is the window's own composition, so the collector runs inside the window rather than beside it.

A window another process raised is the exception the collector has to carry: it becomes visible without the user having come back to it, so a relock scheduled before it went up survives the transition rather than being cancelled by it (§10.3).

### 10.2 Platform behaviour

**Linux.** `java.awt.SystemTray.isSupported()` returns true only when a StatusNotifierItem or legacy notification-area host is present. GNOME removed built-in tray support in 3.26. Recovery requires a shell extension: the third-party AppIndicator/KStatusNotifierItem extension, or the official Status Icons extension shipped from GNOME 47, neither of which is installed by default in most distributions. The AppIndicator extension has broken across GNOME major releases, notably at GNOME 48. The practical consequence is that a large fraction of GNOME users have no tray.

The application must therefore never become invisible and unquittable. Where no tray is available, the tray-related settings are disabled with an explanation, `onCloseRequest` exits the application, and `startMinimised` opens the window minimised on the taskbar rather than hidden with nothing to restore it. A tray the desktop supports and the user has turned off reaches the same close and startup behaviour, since no icon is on screen to raise a hidden window either way; its settings stay offered, because they are what turns the tray back on.

Ubuntu ships `ubuntu-appindicators` enabled by default, so the tray works there without user action; on the reference machine (§15) `SystemTray.isSupported()` returns true under a Wayland session, because AWT runs through XWayland with the X11 toolkit. A GNOME installation without that extension reports false and takes the degraded path above.

The tray icon and its menu are `dev.nucleusframework:composenativetray`, which talks to StatusNotifierItem over D-Bus directly. Compose's own `Tray` is `java.awt.SystemTray` with a `java.awt.PopupMenu`, and neither is drawn by Compose: the menu is AWT's own rendering, which no theme reaches, and the icon is handed to a tray slot at the size the painter claims rather than the size the desktop asks for, so it arrives cropped on a shell scaling it. The library renders the desktop's own menu and takes the drawable, sizing the mark itself.

Its primary action follows each platform: a single left click on Windows, macOS and KDE Plasma, and a double left click on GNOME, which is the convention there.

Whether a tray exists is still `java.awt.SystemTray.isSupported()`, since the library exposes no equivalent. That answer is a proxy rather than the same question: AWT reports on an XEmbed notification area while the library speaks StatusNotifierItem, so a desktop offering one and not the other is answered wrongly. What it decides is the degraded path above, and it errs in the direction that costs a hidden window rather than an unquittable one only where AWT is the pessimistic of the two.

**macOS.** The tray icon appears in the menu bar, sized for it at 22×22 logical points. A monochrome variant is supplied for it, since the menu bar draws a tray icon as a template image rather than in colour. TAuth keeps its Dock icon: `LSUIElement` and the equivalent `-Dapple.awt.UIElement=true` JVM argument both remove the application from the Dock and the application switcher, which does not suit an application whose main window is the primary interface.

**Windows.** The tray icon works without additional configuration and may be placed in the notification overflow area by default, which is expected and requires no handling. System accent-colour following is deferred (§16.8).

### 10.3 Single instance

Two TAuth processes writing one vault lose an update, and a tray application relaunched from the Start menu or Spotlight should raise the existing window rather than start a second process.

The role is claimed before the composition starts, so a launch that hands its request over constructs no window, no session and no `VaultStore`, and ends by returning from `main`. The claim attempts an exclusive `FileLock` on `<vaultDir>/instance.lock`. On success, bind a `ServerSocket` on `127.0.0.1:0`, write the chosen port into the sibling `instance.port`, and listen for a `SHOW` command. On failure to acquire the lock, read the port, connect, send `SHOW`, and wait for the running instance's acknowledgement; the launch that receives it exits with status 0. The running instance makes its window visible and requests focus, and reports that raise as a show request rather than as the user returning to the window: the relock collector of §10.1 cancels a pending relock only for the user's return, so a window raised by `SHOW` comes up with a scheduled relock still standing.

The exit turns on the acknowledgement rather than on the send, because a port a crashed instance recorded can have been taken since by an unrelated program: a launch that exited on the send alone would raise nothing and report nothing. The running instance records the request before it answers, so an acknowledged request is a request that will be acted on.

A launch that becomes primary replaces `instance.port`, which covers whatever a crashed instance left there. The lock file is never unlinked: unlinking releases no lock a live process holds on the inode, and the next launch would then take a second lock on a new inode, which is two primaries.

A launch that can neither take the lock nor reach a running instance opens its window without single-instance service and says so on screen, since exiting silently would leave the application unstartable for as long as whatever holds the lock does. That state costs a vault: two live instances each hold their own decrypted body, and a save rewrites the whole file, so the later save drops whatever the other wrote. `VaultStore`'s lock spans one `write()` and refuses only writes that overlap it, and `read()` takes no lock at all, so nothing reports the loss. Closing it needs the write to be a compare-and-swap against the file it read, which is not in this design.

A `SHOW` arrives over loopback, which carries no owner check, so it can come from any process on the machine and from a different OS user. That is why the raise it causes is not the user's return: a show request that cancelled a pending relock would let anything on the machine hold an unlocked window open on screen.

What ends a raise is the first pointer or key event after it, read from the same toolkit stream the idle trigger of §8.3 watches. The window is raised until someone is at the machine, and only that arrival puts the presence of §10.1 back to the user's, so a relock the raise came up under fires as scheduled if nobody comes. Focus is not that evidence, since the raise asks for the focus itself. Neither are `MOUSE_ENTERED` and `MOUSE_EXITED`: a window mapped, raised or moved under a stationary pointer enters and leaves it, which reports the window arriving rather than a person, and taking those would end every raise on the raise. Real movement arrives as `MOUSE_MOVED`, so nothing a person does is lost. The exclusion holds for the idle trigger for the same reason.

---

## 11. Clipboard

Copy uses `java.awt.Toolkit.getDefaultToolkit().systemClipboard`. After the configured delay, the clipboard is cleared **only if its current contents still equal the exact string TAuth placed there**, so the timer never destroys something the user copied in the meantime. This covers generated codes and copied `otpauth://` URIs alike. The comparison reads the clipboard contents, which on some platforms can throw `IllegalStateException` under contention; failures are caught and the clear is skipped.

The clipboard is also cleared, subject to the same equality check, on every lock.

On Linux, clipboard contents are owned by the source application. Clearing works within the process's lifetime; after the application exits, X11 clipboard contents vanish anyway, while Wayland behaviour depends on the compositor and any clipboard manager. A clipboard manager will retain history regardless of what the application does, and this is noted in the security notes.

---

## 12. Error model

```kotlin
sealed interface VaultError {
    data object NoVaultFile : VaultReadError
    data object VaultFileExists : VaultCreateError                       // creation refused rather than overwriting
    data object WrongPassword : VaultOpenError, PasswordCheckError       // wrap unwrap failed authentication
    data object IntegrityFailure : VaultOpenError                        // body decryption failed authentication
    data class Corrupt(val detail: String) :                             // structural parse failure
        VaultReadError, VaultOpenError, PasswordCheckError, VaultAdoptError, ImportReadError, ImageReadError
    data class UnsupportedVersion(val found: Int, val supported: Int) : VaultOpenError, VaultAssembleError
    data class InvalidSecret(val detail: String) : VaultAdoptError, EntryAddError, UriParseError
    data class MalformedUri(val detail: String) : UriParseError
    data object NoSuchEntry : EntryLookupError                           // no entry under the id an operation named
    data class InvalidEntry(val detail: String) :                        // values an operation refuses to store
        EntryAddError, EntryChangeError, DraftError
    data object VaultClosed :                                            // no live key: locked, or mid-unlock
        VaultAdoptError, VaultEncodeError, PasswordGateError, EntryLookupError, ImportReadError
    data class TooLarge(val size: Int, val limit: Int) : VaultAssembleError   // refused rather than written
    data class Io(val cause: Throwable) : VaultReadError, VaultWriteError, ImportReadError, ImageReadError
    data class LockedByAnotherProcess(val path: String) : VaultWriteError
}

sealed interface VaultCreateError : VaultError
sealed interface VaultUnlockError : VaultError
sealed interface VaultRewriteError : VaultError
sealed interface EntryWriteError : VaultError
sealed interface EntryAddError : EntryWriteError
sealed interface EntryChangeError : EntryWriteError
sealed interface DiscloseError : VaultError
sealed interface DraftError : VaultError
sealed interface UriParseError : DraftError
sealed interface ImportReadError : VaultError
sealed interface ImageReadError : VaultError

sealed interface VaultReadError : VaultUnlockError, VaultRewriteError
sealed interface VaultCommitError : VaultCreateError, VaultRewriteError, EntryAddError, EntryChangeError
sealed interface VaultWriteError : VaultCommitError
sealed interface VaultReencodeError : VaultRewriteError
sealed interface VaultOpenError : VaultCreateError, VaultUnlockError, VaultReencodeError
sealed interface VaultEncodeError : VaultCommitError, VaultReencodeError
sealed interface VaultAssembleError : VaultEncodeError
sealed interface VaultAdoptError : VaultCreateError, VaultUnlockError, VaultRewriteError
sealed interface PasswordGateError : DiscloseError
sealed interface EntryLookupError : DiscloseError, EntryChangeError
sealed interface PasswordCheckError : PasswordGateError
```

`VaultError` is a sealed interface and is never thrown. Fallible operations return `Outcome<T, E>`, where `E` is the view of this hierarchy holding the cases that operation reports, so failure modes are visible in signatures and a `when` over a view with no `else` branch fails to compile when a case joins it. Exceptions from the JDK are caught where they arise and converted immediately: `IOException` does not propagate past `VaultStore`, `GeneralSecurityException` does not propagate past the `crypto` package. See STYLE_GUIDE.md §4.

Each case declares the views it belongs to. A view exists per operation whose failure reaches a message, and per step within one; a step's view names the operations it reaches, so an operation's cases are the union of its steps rather than a list repeated on every case. A `when` over a view has a branch only for a case that view admits, which is what makes a message mapping over one testable at every branch.

| View | Cases |
|---|---|
| `VaultReadError` | `NoVaultFile`, `Io`, `Corrupt` |
| `VaultWriteError` | `Io`, `LockedByAnotherProcess` |
| `VaultOpenError` | `WrongPassword`, `IntegrityFailure`, `Corrupt`, `UnsupportedVersion` |
| `VaultAssembleError` | `UnsupportedVersion`, `TooLarge` |
| `VaultEncodeError` | the assemble, and `VaultClosed` |
| `VaultCommitError` | the encode and the write |
| `VaultReencodeError` | the open and the encode |
| `PasswordCheckError` | `WrongPassword`, `Corrupt` |
| `PasswordGateError` | the password check, and `VaultClosed` |
| `EntryLookupError` | `NoSuchEntry`, `VaultClosed` |
| `VaultAdoptError` | `InvalidSecret`, `Corrupt`, `VaultClosed` |
| `VaultCreateError` | `VaultFileExists`, and the read, the open, the commit and the adopt |
| `VaultUnlockError` | the read, the open and the adopt |
| `VaultRewriteError` | the read, the open, the commit and the adopt |
| `EntryAddError` | `InvalidSecret`, `InvalidEntry`, and the commit |
| `EntryChangeError` | `InvalidEntry`, the entry lookup and the commit |
| `EntryWriteError` | the add and the change |
| `DiscloseError` | the password gate and the entry lookup |
| `UriParseError` | `MalformedUri`, `InvalidSecret` |
| `DraftError` | the URI parse, and `InvalidEntry` |
| `ImportReadError` | `Corrupt`, `Io`, `VaultClosed` |
| `ImageReadError` | `Corrupt`, `Io` |

Every error maps to a specific user-facing message, and the wording is per operation even where the case is shared. `WrongPassword` and `IntegrityFailure` must never share a message: one means "try again", the other means "this file has been modified or damaged". `NoSuchEntry` and `Corrupt` are separated for the same reason — an entry deleted between a click and the operation it started is not a damaged vault.

`InvalidEntry` covers every value an operation refuses to store: the entry model's rules where the model is the one refusing, and rules an operation holds of its own — an id already in the vault, a counter with no successor. The detail states the rule and never the value, because it reaches log output and the screen.

`ExportError` is a second hierarchy of the same shape, over what stops a file reaching the place the user chose. `VaultUnreadable` belongs to a copy of the vault, which reads the vault first; `NotRestricted` and `Io` belong to the write itself, which is `FileWriteError` and is what §9.7's saved image reports. A copy of the vault is `VaultExportError`, the union of the two. The wording differs between the operations for the same case, since one has a vault to say is unchanged and the other has not.

Two cases belong to a write alone. `TooLarge` is the encoder refusing to produce a file the reader would refuse, and a read past the same ceiling reports `Corrupt` instead; `LockedByAnotherProcess` is the file lock a write takes and a read does not. Neither can reach an unlock, an export or a disclosure.

Reading an image is a view of its own rather than the import's, since it names no vault: the account a code holds is not stored by being found, so a vault closed under the reading is not one of its cases. `Corrupt` reaches an import because the file offered is a document TAuth did not necessarily write, and one that is not an export is damaged in the sense that case names.

Adding an entry and changing one are separate views because they report different cases: an add decodes a secret and so reports `InvalidSecret`, while it names no existing entry and so cannot report `NoSuchEntry`; a change is the reverse. One view over both would leave each of their screens a branch nothing can reach.

A view is named for a step rather than for a call site, so three branches are admitted by a view and unreachable on the path that view serves: `TooLarge` and `InvalidSecret` on a creation, whose body is empty and carries no entry to decode, and `UnsupportedVersion` on every commit, whose header and body versions are the writer's own constants rather than anything read. Naming a view per call site instead would need a type per operation over the same steps; each of these branches is worded for the case it names, so one reached by a later change says the right thing.

---

## 13. Testing

Rules are in STYLE_GUIDE.md §10: backticked behavioural names, one subject per test, spec vectors as individual cases rather than a loop over a table, no mocking framework, a fixed `Clock` and a temp directory, no reliance on wall-clock time and no sleeps. Tests never read or write outside a temp directory and never touch the real vault path.

Changes under `crypto/` or `vault/` need a test that fails before the change and passes after.

### 13.1 Required coverage

**OTP core.** Every RFC 4226 Appendix D and RFC 6238 Appendix B vector of §5.4 as its own case, naming its counter or its algorithm and timestamp. The `20000000000` vector, exercising 64-bit `T`. Period boundary behaviour at exactly `T` and `T-1`. Feeding `floor(t / period)` through the HOTP entry point reproduces every TOTP vector, which is what establishes one implementation rather than two. Base32 against RFC 4648 §10, padded and unpadded, padding of the wrong length in both directions, every trailing group length that cannot end an encoding, lowercase, embedded whitespace, invalid characters, empty input.

**URI.** Every parsing rule of §5.5 as its own case, in both directions, plus round-trip build-then-parse for every entry configuration of both types. Every expectation is a URI written out in the test rather than one rebuilt from the entry it describes, since the format omits a parameter equal to its default and a rebuilt expectation agrees with whatever fields the build happened to read.

**Vault codec.** Round trip with an empty entry list and with several hundred entries. A wrong password gives `WrongPassword`; a flipped bit in the ciphertext or the GCM tag gives `IntegrityFailure`; a flipped bit at every offset across the header, in the CRC itself, and at every offset ahead of it gives `Corrupt` and never `WrongPassword`, swept exhaustively rather than sampled. A modified `headerLength` never silently succeeds, and one with the high bit set gives `Corrupt` rather than a backwards slice. Two successive writes of identical content produce different ciphertext, which is what proves nonce freshness. Every value drawn from the CSPRNG is compared across two independently created vaults, because a constant satisfies any assertion made within one. Each key array is lent to a block so its zeroing is asserted rather than inspected, after a block that returns and after one that throws.

**Crypto primitives.** Argon2id against published Argon2 reference vectors; AES-GCM against NIST vectors; HMAC against RFC 2202 and RFC 4231, including the case 6 keys exceeding the hash's block size. The Argon2 cost test states the parameters as literals on the reference side, never through the constants under test, and each constant has a test of its own naming its value — these are the only things standing between a hand-edited constant and a silently weaker KDF. `secureRandomBytes` is asserted to be backed by `java.security.SecureRandom`, which nothing about the bytes themselves establishes from inside one process.

**Error views.** Each view's membership asserted as a whole set rather than case by case, so a membership added to a case fails the view it joined just as one dropped fails the view it left. A case added to the hierarchy stops the naming table compiling. The two cases a write alone produces are asserted absent from an unlock. The views over `ExportError` are asserted the same way.

**Vault store.** An atomic write leaves no `.tmp` on success; a failed write leaves the original intact and readable; a failure at the rename leaves the whole new vault in the temp file; POSIX modes are `0600` on the vault and the lock file and `0700` on the directory, including ones that already existed too widely; a write that cannot take the file lock reports `LockedByAnotherProcess` and leaves the previous vault byte for byte.

**Vault paths.** Resolution under a set, unset, blank and relative `XDG_DATA_HOME`, the same for `%APPDATA%`, an empty home leaving the location unresolved, and per-OS branches driven by an injected OS identifier rather than the real `os.name`.

**Session and entry operations.** Every lock path zeroes the DEK and every decoded secret, verified by retaining a reference to the backing array. The scheduled lock's grace period and arming, the triggers held and replayed across a derivation, and the precedence of a lock landing during one. Every entry operation is read back through the codec rather than off the session, and repeated against a refused write to establish that nothing moved. The ticker never computes an HOTP entry, and a row scrolled out of view is not computed at all.

**QR.** For every entry configuration in the URI matrix, encode the entry to a URI, render it, decode the symbol, and assert the payload equals the original byte for byte; the symbol is drawn at several pixels a module, since the binarizer averages blocks rather than sampling points. A URI reaches the encoder percent-encoded and so carries nothing outside ASCII, which leaves the character set standing on no entry configuration — it is asserted on the seam instead, over text outside Latin-1. Three cases hold the parameters the symbol is read at: the error correction level the decode reports, a quiet zone two modules deep on every side, and the symbol version staying at or below 10 for a 160-bit secret with a 64-character label, since larger symbols become hard to scan at the dialog's minimum size. Text past the format's capacity yields no symbol.

**Screens.** Every failure mapping a screen carries is asserted at every branch, with the wrong-password and damaged-file messages kept apart in both directions. Every state a screen reports is read through the semantics tree, and no rendering of a draft, a holder or an entry carries a secret.

**Appearance.** Every theme token read from the light and the dark mode in one composition, so a token the theme fails to switch shows as two equal readings. The code readout is monospace. The countdown's expiry state is read through the description it carries rather than through its colour, and the fraction it reports is read at two different periods at one instant, which agree only if the period dividing it is the one the code was generated under.

### 13.2 Verified nowhere

Stated rather than left to be discovered:

- **Anything visual.** Compose's test APIs read the semantics tree, so an unpainted background and an unrenderable field populate it identically. §13.3 and a person looking are the only verification of a rendering.
- **The two access-control-list branches**, in `VaultStore` (§6.6) and `OwnerOnlyFile` (§9.9). Reached only where a filesystem exposes `AclFileAttributeView`, which on Linux is no ordinary mount: a FAT volume takes that branch and then offers no view either, so it exercises the refusal beside it rather than the entry being set.
- **DMG and MSI.** jpackage emits only the host format.
- **Four session paths**: a lock landing mid-rewrite, two settings operations in flight at once, an export taking its copy behind the session lock, and the zeroing of the KEK a rewrite derives.
- **Two routing fallbacks**: leaving the edit destination when the account it named is deleted underneath it, and the import wiring crossed with routing.
- **`SettingsWork` zeroing the password arrays it was handed.**
- **The zeroing inside the password check and the preview code**, which is not observable from outside the codec.

### 13.3 Manual verification

Tray behaviour on GNOME (with and without a tray extension), KDE Plasma, Windows 11 and macOS: the click each platform names — a single left click on Windows, macOS and KDE Plasma, a double on GNOME — the menu on the secondary click throughout, and whether the desktop draws the icon at the size it asked for.

The relock triggers of §8.3 on a running window, since the collector reads state a headless test cannot produce: hiding to the tray, minimising, restoring onto a desktop that gives the window no focus, and leaving the window untouched for the idle interval, each against a grace period of 0 and of 30 seconds. What this catches is a window whose composition stops reporting when it leaves the screen, which would leave a hidden window unlocked.

The raise of §10.3 by launching TAuth a second time while the first runs: against a window hidden to the tray, one minimised, and one standing behind another, with the pointer left where it rests each time. The window comes forward in all three, the second launch ends on its own, and a window raised with nobody at the machine locks when its relock falls due.

Cross-platform vault portability: create a vault on one OS, copy it to the other two, and unlock with the password on each.

The show-QR dialog scanned with at least three unrelated authenticators — Google Authenticator, Aegis or Raivo, and one desktop scanner — at the dialog's minimum size and on both a light and a dark system theme. Automated round-trip tests confirm the payload; only a real scanner confirms the rendering.

Argon2id timing on the lowest-specification target machine, confirming the parameter choice in §6.5.

The access-control-list branch on Windows, and only there: a packaged build installed with a vault created on an NTFS volume and an export written to one.

---

## 14. Milestones

**M1 — OTP core.** `Base32`, `Hmac` expect/actual, `Hotp`, `Totp`, `OtpAuthUri` covering both types. No UI. Deliverable: a green test run covering every RFC 4226 and RFC 6238 vector.

**M2 — Vault format.** The `crypto` expect/actual set, the models of §6, `VaultCodec`, `VaultStore`, `VaultPaths`, `VaultError`. No UI. Deliverable: create, write, read and round-trip a vault from tests. The store's POSIX branch is exercised here; its access-control-list branch is verified on Windows with the packaged artifacts, as §13.3 states.

**M3a — Shell infrastructure.** `Preferences` and `PreferencesStore`, `ClipboardService`, the single-instance mechanism of §10.3, and tray availability with the fallback §10.2 describes. Deliverable: a preference file that survives a restart, a clipboard that clears only the string it placed, a lock file and loopback listener that answer whether another instance holds the vault, and a correct answer to whether this desktop has a tray.

**M3 — Session and unlocked UI.** `VaultSession` including `scheduleLock` and `cancelScheduledLock`, `SessionState`, `LockReason`, the code ticker, create-vault, unlock, account list with live TOTP codes and generate-on-request HOTP rows, the HOTP persist-before-display path, add by URI and manual entry, edit including counter, delete, and the disclosure gate built as a component rather than at its one call site. Deliverable: a usable authenticator.

**M4 — Tray, lifecycle and settings.** Tray construction and menu, hide-to-tray, the relock triggers of §8.3, grace period, idle timeout, the lifecycle behaviour `SecurityPolicy` governs, the single-instance role taken at startup so a second launch raises the first window and exits, and the settings screen of §9.8 including change master password, re-encrypt and encrypted export.

**M4b — Errors narrowed to the operation that reports them.** Each case declares the operations it belongs to, so a read and a write each get a sealed view over the cases they can produce, and `VaultStore`, `VaultCodec` and `VaultSession` return the view that fits. The message mappings of §9 follow: each `when` shrinks to the cases its screen's operation can reach, every branch is then one a test can drive, and a sentence naming a read cannot be reached by a write. Deliverable: every mapping admits only what the steps of its own operation report, each branch it carries is asserted, and the branches a view admits that its path cannot reach are the three §12 names and no others.

**M5 — QR, plaintext export, packaging.** ZXing image decode for import and `QRCodeWriter` for the show-QR dialog (§9.7), plaintext export in both formats, import with duplicate detection, DMG/MSI/DEB configuration, and icons for all three platforms.

**M6 — The primary view.** The interface takes the shape §9 states: a small window that does not maximise (§10.1), a compact account list with a density choice and the code where the eye lands (§9.4), and the keyboard path from opening the window to a code on the clipboard.

The appearance of §9.10 lands whole: the bundled face with tabular figures, the flat dark palette under a single accent with the light scheme drawn from the same tokens, an icon beside every control, and the generated account mark. The countdown sweeps continuously against the instant its period ends (§8.5) and states its expiry in more than colour.

The settings screen is rebuilt as a screen rather than a document (§9.8), and every destination is laid out for the window's width.

This milestone revises STYLE_GUIDE.md §7's theme rules, and §9.1, §9.4, §9.8, §9.10 and §10.1 here. It follows M5 because it redraws every screen the milestones before it complete, and a screen redrawn before its behaviour exists is drawn twice.

M1 and M2 have no dependency on Compose and are fully testable headless. M3a touches neither the session nor the vault, so it is buildable and testable ahead of M3; every relock trigger in M4 resolves to a call on the session M3 builds, and every M5 item is an entry point added to a screen M3 or M4 already has. M4b sits between them because it edits every signature that reports a failure and every mapping that renders one: taken after M5 it would touch the QR dialog, plaintext export and import as well, and taken before M4 it would have no settings screen to narrow.

---

## 15. Reference measurements

Figures quoted elsewhere in this document were taken on one machine, described here so they can be interpreted and re-taken.

**Reference machine.** Ubuntu, GNOME Shell 50.1, Wayland session, 20 logical cores, 16 GB RAM, Azul Zulu JDK 21, Gradle 9.1, Kotlin 2.4.10.

**Argon2id timings** (§6.5) come from `Argon2BytesGenerator` in BouncyCastle 1.85, 32-byte output, 16-byte salt, JIT warmed, median of three runs. They scale with core speed and memory bandwidth, not core count, since p = 1.

**Tray availability** (§10.2): `java.awt.SystemTray.isSupported()` returns true, with `sun.awt.X11.XToolkit` as the toolkit under a Wayland session by way of XWayland, and `ubuntu-appindicators@ubuntu.com` providing the StatusNotifierItem host. This measures a machine where the tray works; §10.2 covers the case where it does not, which the same call detects.

**Library compatibility** (§4): kotlinx-serialization-json 1.11.0 and kotlinx-datetime 0.8.0 compile against Kotlin 2.4.10 in this project's `:shared` module, verified with a source file exercising `@Serializable` round-tripping and the kotlinx-datetime calendar types rather than by resolution alone.

The measurements that constrain a choice are the Argon2 timings. Re-take them if the parameters in §6.5 change, or before assuming the unlock latency is acceptable on hardware materially slower than the reference machine.

---

## 16. Future Improvements

### 16.1 Unlock via platform authenticator

The password-only design requires typing the master password on every unlock, and §8.3 makes unlocks frequent by design — hiding to the tray discards the key. The intended improvement is an optional second unlock path backed by the operating system's credential store, reached through Touch ID on macOS, Windows Hello or the Windows user session on Windows, and the freedesktop Secret Service keyring on Linux.

**Structure.** Envelope encryption already separates the KEK from the DEK. The keyring path stores a second copy of the DEK, so either unlock path yields the same key and the body is never re-encrypted when the feature is toggled:

```
                    ┌──────────────────┐
  master password ──┤ Argon2id + salt  ├──► KEK (32 B)
                    └──────────────────┘        │
                                                │ AES-256-GCM unwrap
                                                ▼
  OS keyring entry ─────────────────────────► DEK (32 B) ──► AES-256-GCM ──► vault body
```

The master password remains mandatory. The keyring is a convenience path, never the only one, so a lost keyring entry, a re-enrolled fingerprint or a move to another machine never renders the vault unopenable.

### 16.2 Consequences to state in the UI when this lands

- Enabling keyring storage makes the platform authenticator sufficient to read every secret. The vault's security becomes the weaker of (master password, platform authenticator).
- On Linux, the freedesktop Secret Service default `login` collection is readable by any application running as the same user once the keyring is unlocked (CVE-2018-19358). This is a property of the platform, not of TAuth, and it must be stated at the point where the user enables the feature.
- On Windows without the Hello work in §16.6, and on Linux, no biometric or presence check occurs. The key is released to anyone with the user's logged-in session, so the settings copy must name the actual protection — "Windows user account", "system keyring" — rather than implying a fingerprint check.

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
- **`OpenVault` giving up its parsed body** (§8.4) once the session has decoded every secret out of it, so the base32 text ends at the decode rather than at the lock. The handle carries the DEK and the body together, and the session keeps it for the key, so dropping the body means a handle that answers for one and not the other, and a write path that rebuilds the body from the session's own state. Rebuilding re-encodes each secret from its decoded bytes, which is a different string from the one imported wherever the import carried padding, lowercase or whitespace — §6.4 stores the text as imported so that an export reproduces the original URI.
- **Screen-region QR capture** (§9.5). It requires `java.awt.Robot` screen capture permission, which on macOS triggers a Screen Recording privacy prompt and on Wayland needs a portal integration.
- **Narrowed jlink module list** replacing `includeAllModules = true` (§4.1), once the packaged artifact is verified on each OS.
- **System accent-colour following on Windows** (§10.2).

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
- [Compose Multiplatform — Native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
- [`java.awt.SystemTray` javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/SystemTray.html)
- [AppIndicator and KStatusNotifierItem Support — GNOME extension](https://extensions.gnome.org/extension/615/appindicator-support/)
- [GNOME official Status Icons extension](https://www.omgubuntu.co.uk/2024/08/gnome-official-status-icons-extension)
- [ComposeNativeTray](https://github.com/kdroidFilter/ComposeNativeTray)
- [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir/latest/)

### Referenced by §16 only

- [swiesend/secret-service — Secret Service API for Java](https://github.com/swiesend/secret-service)
- [Apple — `SecAccessControlCreateWithFlags` and data protection keychain discussion](https://developer.apple.com/forums/thread/721649)
- [Implementing Windows Hello from Java — KeyCredentialManager obstacles](https://blog.purejava.org/posts/KeyCredentialManager/)
- [Windows Hello — Microsoft Learn](https://learn.microsoft.com/en-us/windows/apps/develop/security/windows-hello)
- [Azure SDK for Java — `WindowsCredentialApi` JNA bindings reference](https://azuresdkartifacts.blob.core.windows.net/azure-sdk-for-java/test-coverage/azure-identity/com.azure.identity.implementation/WindowsCredentialApi.java.html)
