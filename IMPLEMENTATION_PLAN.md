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
    CodeGrouping.kt           the one break a code is read across
    PreviewCode.kt            the code an account would produce, worked out before it is stored
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
    VaultFile.kt              the bytes a vault lives in, behind the platform store
    VaultError.kt             sealed error hierarchy
    EntryEdit.kt              the fields an edit may change, and the model's refusal of one
    EntryUri.kt               entry to otpauth:// URI and back
    EntryDrafts.kt            the text an add or an edit form holds, and the account it resolves to
    PlaintextExport.kt        the two shapes the accounts leave the vault in, and what each carries
    ImportRow.kt              what a file offers, one row at a time, and which rows the vault holds
  session/
    SessionState.kt           NoVault | Locked | Unlocking | Unlocked
    LockReason.kt             enum of relock triggers, with the policy each reads
    UnlockedEntry.kt          an entry as the UI holds it, without its secret
    SessionClipboard.kt       the one clipboard call a lock makes
    VaultSession.kt           key material, lock lifecycle, state flow
    CodeTicker.kt             live codes for the rows the list has on screen
    TickCadence.kt            the wait that lands a tick on a whole second
  password/
    PasswordStrength.kt       advisory master-password score, and the minimum length §9.2 enforces
    CommonPasswords.kt        the embedded list a score is checked against
  settings/
    Preferences.kt            plaintext model, readable before unlock
    PreferencesState.kt       the one owner of that document, and the one path a change takes
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
  TAuthApp.kt                 root composable, routes on session state, and the password attempt
                              the create and unlock screens hand their characters to
  ClipboardCopy.kt            the copy a screen asks the shell for, and what it answered
  SingleInstanceNotice.kt     what a window with no single-instance service says above its screen
  theme/                      Material 3 colour scheme and typography, the spacing scale screens
                              lay out with, the colours Material has no role for, and which of
                              the two schemes a stored theme preference asks for
  create/CreateVaultScreen.kt
  unlock/UnlockScreen.kt
  list/AccountListScreen.kt
  list/AccountRow.kt          code display, countdown ring, copy affordance
  list/AccountOrder.kt        the search filter, the three orderings, and where a drag drops
  list/Countdown.kt           the second a countdown turns on, and the colour it turns
  list/RowState.kt            generated codes, the interval after one, and the copy confirmation
  edit/AddAccountScreen.kt
  edit/EditAccountScreen.kt
  edit/EntryPreview.kt        the resolved account every add path converges on
  edit/ScannedCodes.kt        the accounts among the codes an image held, and the seam the shell
                              reads one through
  edit/ScanState.kt           what an image offered, and the choice several accounts carry
  settings/SettingsScreen.kt
  settings/ExportError.kt             why a copy of the vault was not placed where it was asked for
  settings/SettingsWork.kt            what a settings action is doing, what it reported, and the
                                      policy the screen draws while a rewrite runs
  settings/ShellSettings.kt           what the shell knows and no screen can ask for itself
  settings/PlaintextExport.kt         the warning, the format, and the gate every account leaves
                                      through
  imports/ImportScreen.kt             what a file offered, and the choice a duplicate carries
  imports/ImportWork.kt               what an import has read and what has been decided about it
  components/PasswordField.kt         masked field over the holder beside it
  components/PasswordFieldState.kt    the CharArray a master password is edited in
  components/FormControls.kt          labelled text field and one-of-many choice
  components/SecretDisclosureGate.kt  the password re-entry every disclosure carries, and its state
  qr/QrSymbol.kt                      the module grid a symbol is drawn from, and the seam the shell
                                      hands its encoder over through
  qr/QrLayout.kt                      where the modules land on the canvas that draws them
  qr/ShowQrDialog.kt                  the symbol on screen, what it stands for, and how long it stands

desktopApp/src/main/kotlin/com/panda/tauth/
  Main.kt                     application scope, window, tray, lifecycle
  TrayAvailability.kt         whether this desktop has a tray
  WindowLifecycle.kt          close and startup behaviour that follows
  TAuthTray.kt                the tray icon, and the three actions its menu carries
  TAuthIcon.kt                the mark the tray and the title bar carry, read from the drawable
                              §4.3's packaged icons are cut from
  ShellWindow.kt              where the window opens, and the geometry a window state records
  WindowClose.kt              what a close request does, and the order an exit does it in
  WindowGeometryRecorder.kt   the wait a geometry settles through before it reaches the file
  RelockTriggers.kt           what the window layer observes, and the report it makes of it
  IdleWatch.kt                the wait an interval passes through without pointer or key input
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
    implementation(libs.compose.components.resources)
    implementation(libs.composeNativeTray)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
}
```

`composenativetray` is the tray icon and its menu, for the reasons §10.2 gives; the artifact without the `-app` suffix carries the icon and the menu DSL alone and pulls in no windowing backend of its own. The resources library is what a drawable is read through, by the tray and by the title bar alike (§4.3's icons).

ZXing is used in both directions: `core` provides `MultiFormatReader` for QR import (§9.5) and `QRCodeWriter` for QR display (§9.7); `javase` provides `BufferedImageLuminanceSource` for decoding image files. Writing a symbol out as a PNG needs neither, since §9.7 renders it from the module grid the screen holds and the JDK's `ImageIO` encodes that.

`nativeDistributions` sets `includeAllModules = true`, which trades installer size for immunity to jlink stripping failures: a stripped runtime fails at the module a code path reaches on one platform only, and that failure lands on the user rather than on the build. Narrowing to an explicit `modules("java.naming", "java.management", "jdk.crypto.ec")` list is listed in §16.8, after the artifacts are verified.

The mark is `src/main/composeResources/drawable/tauth.svg`, read through the resources library for the tray and the title bar. The three forms jpackage takes — `icons/tauth.png`, `icons/tauth.ico` and `icons/tauth.icns` — are cut from that one file, so the installer and the running application cannot show different marks. A monochrome variant sits beside it for the macOS menu bar, which draws a tray icon as a template image rather than in colour.

Beside the icons, `nativeDistributions` carries what an installer names the application by: a description, a vendor and a copyright; on Linux a package name, a maintainer, a menu group and a category; on macOS a bundle identifier, which is what a keychain entry and a Screen Recording grant are remembered against and is therefore fixed here rather than derived; on Windows a menu group and an upgrade UUID, fixed for the lifetime of the application because a changed one installs a second copy beside the first rather than replacing it.

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

A counter at the unsigned 64-bit maximum has no successor to store, so generation is refused and no code is shown. Unsigned arithmetic wraps, and a counter wrapping to zero would reissue every code the server has already consumed. Editing the counter (§9.6) is what moves such an entry again.

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

A path that already holds a vault produces `VaultError.VaultFileExists` and no write, since the write would replace every secret in that vault with those of a new one. The check is not atomic with the write it guards: it stops the create flow from overwriting a vault, not a second process from writing one in between, which is what §10.3 covers.

The session reads the file back and opens it, so the create flow lands on the account list rather than on a password prompt, and a write that did not land as it was sealed is found at creation rather than at the next unlock. That is a second Argon2id derivation, paid once per vault; the alternative is a codec that hands its key back to be adopted. Reading the file rather than reopening the buffer is what makes the check about the vault on disk: the buffer opens by construction, whatever the filesystem did with it.

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
    data class Unlocked(val entries: List<UnlockedEntry>, val policy: SecurityPolicy) : SessionState
}
```

`VaultSession` owns a `MutableStateFlow<SessionState>` and holds the DEK in a private `SecureBytes`. The DEK is never exposed through the public API; callers request operations, not keys.

`UnlockedEntry` is a `VaultEntry` without its `secret`: the id, type, issuer, account name, algorithm, digits, period, counter, creation time and order index that the account list and the edit screen draw. The session decodes each secret into key bytes it zeroes on lock, and the base32 text those came from stays alive in the open vault's parsed body (§8.4); an entry the UI holds carries neither form, so no entry the session publishes is a credential. What a composable holds of its own is another matter: the add screen keeps the pasted URI and the typed base32 in `String`s for the life of the screen. The policy travels with the state because it lives in the encrypted body, which is where a lock trigger and a clipboard clear have to read it from.

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

Everything down to the state assignment runs under the lock that guards the session's fields, so a lock arriving from the tray and an unlock finishing on a worker cannot interleave over one key. The clipboard call is outside it: the platform clipboard blocks under contention, and stalling every other operation on the session for the length of that is worse than clearing a moment after the state says locked.

The session reaches the clipboard through a one-call interface it holds and the shell implements, rather than by publishing something a collector acts on. A lock for an exit is followed by the process ending, and a collector gets no turn to run in between, which would leave a code or an `otpauth://` URI on the clipboard of a machine whose vault is shut.

`scheduleLock` reads the grace period from the `SecurityPolicy` the session holds, and whether the reason is armed at all; a zero grace period locks at once. It is a no-op against an already-locked vault, so a caller never has to ask what state the session is in. `cancelScheduledLock` drops a pending timer without locking. Both are on the session rather than on the window because the policy lives in the unlocked body and is unreadable exactly when it is irrelevant.

The timer belongs to the application scope the session is constructed with, so shutting the application down cancels a pending lock. Three things drop one: `cancelScheduledLock`, a `lock` from any source, and that scope ending. A second `scheduleLock` arriving while one is pending is ignored rather than restarted, since the window has been off the screen since the first trigger fired and a later trigger must not push that deadline out.

Triggers arriving while a derivation is running are held and replayed once the body is open, and the first the policy arms takes effect. None of them can be judged when it lands, because the policy that says whether it is armed is still encrypted; holding only one would let a disarmed reason swallow an armed one that followed it, and the window would come back unlocked from the hide that the arming exists to catch. A derivation that fails keeps them, because the window is still wherever the trigger found it, and the user returning to it clears them through `cancelScheduledLock`.

A `lock` landing while a derivation runs takes precedence over it. The unlock destroys the key it derived rather than installing it, leaves the state the lock set, and returns `VaultError.VaultClosed`: the vault the user closed stays closed, and opening it is another password entry.

A password change and a DEK rotation (§7.1) reach that point having already written the file, since the vault they reopen is the one they have just committed. A lock landing there leaves the rewrite standing and only the session shut, so the vault that stays closed is the rewritten one and the password that opens it is the new one. `VaultClosed` therefore says the session closed, not that the change was refused.

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

The exit trigger is a JVM shutdown hook, which covers a normal exit, `SIGINT` and `SIGTERM`, and the in-app quit paths that lock before they call it. `SIGKILL`, a power loss and a JVM crash run no hook at all, and a process ended that way leaves the key wherever the operating system leaves its pages and the clipboard holding whatever was last copied. The hook can also run after the toolkit is down, so its zeroing lands where its clipboard clear may not.

The idle trigger is held off while §9.7's QR dialog is on screen, which is the one thing this application draws to be read rather than typed at: a symbol being scanned looks to the watch exactly like an empty room. The hold ends with the dialog, and the dialog closes itself after a minute without interaction, so what it costs is bounded by that minute rather than by the person remembering to close it. No other trigger is affected, and a window hidden or minimised over an open dialog locks on its own trigger as it would over any other screen.

A configurable grace period delays the hide-triggered and minimise-triggered lock. Default is 0 seconds, meaning immediate. Options are 0 / 30 s / 2 min. The grace timer is cancelled if the window becomes visible again before it fires. The timer runs as a cancellable coroutine on the application scope, not as a `java.util.Timer`, so that shutdown cancels it deterministically.

### 8.4 Handling of decoded secret material

Base32-decoded secrets are held as `SecureBytes` and are never converted to `String`. `SecureBytes.destroy()` fills the backing array with zeros and marks the holder unusable; a later lend refuses to run its block and returns `null`, which the vault path reports as `VaultError.VaultClosed`.

JVM `String` instances are immutable and cannot be wiped, and this boundary is **not** enforced by the API. `VaultEntry.secret` holds the base32 text as a `String`, `OpenVault` retains the whole `VaultBody`, and kotlinx's lexer materialises every string token before any deserialiser sees it, so each stored secret exists as an unwipeable `String` for as long as the vault is open. The rule above forbids a *decoded* secret in a `String`, and the base32 text passes it by the letter while being the credential in full. The session decodes every secret into `SecureBytes` at unlock and publishes entries carrying neither form of it, so no entry the UI receives is a credential. What a screen collects for itself is: an `otpauth://` URI pasted into the add screen and the base32 an `EntryDraft` holds are both the credential in full, in `String`s that live until the screen leaves composition. The decoded bytes are lent to a block and never handed over, and that lend is `internal`, so the public API gives out nothing while any caller inside `:shared`, `ui/` included, can ask for one. What keeps the encoded form alive is the open vault the session holds for its key, so a heap dump of an unlocked vault still yields every secret in base32; ending that needs `OpenVault` to give up its parsed body, which is listed as deferred in §16.8.

`SecureBytes.adopt` is the only constructor and it takes ownership of the array, so a caller cannot hold a second reference by accident. The destroyed flag is `@Volatile`, written after the zeroing and read before the array, so a thread that observes the flag also observes the zeroed bytes rather than a cached view of a live key. Nothing zeroes a holder that is dropped without `destroy()`; the discipline is the API contract, not a collector hook.

Key material is lent to a block rather than handed out: the lend is the only member that gives a caller the array, and `destroy()` and that block exclude each other. What the lend guarantees is that no `destroy()` runs while the block holds the array, not that the block cannot keep the array past its own return; a block that keeps it holds bytes a later `destroy()` zeroes under it. A lock arriving during a write waits for that write to finish, and a write beginning after the lock finds the key gone and fails. Zeros reaching a seal part-way through it would leave a body encrypted under them beside a header carrying the real wrapped key, and the rename would commit that over the previous file. The common standard library offers atomics but no mutex, so the exclusion is an `expect`/`actual` primitive in `crypto` like the other platform primitives.

The master password is handled as `CharArray` from the text field through to the KDF call, and zeroed after derivation. Compose's `TextField` state is `String`-based, so this boundary is imperfect: a `BasicTextField` with a custom `CharArray`-backed state holder is the conforming approach, and every field that takes the master password uses it. The container, the minimum height and the focus indication come from Material's own decoration through `BasicTextField`'s `decorationBox`, so keeping the holder costs nothing visually.

Generated six-to-eight digit codes are `String`. They are short-lived, low-value, and needed as `String` for display and clipboard.

This reduces but does not eliminate residue. A heap dump taken while unlocked contains everything; one taken after locking contains the zeroed arrays plus whatever the garbage collector has not yet reclaimed of transient `String` instances.

The KDF adds residue that TAuth cannot reach. `Argon2BytesGenerator` zeroes its memory blocks when it returns each to the block pool, and leaves three things for the collector: the UTF-8 encoding it makes of the password `CharArray`, the 72-byte H0 prehash seeds, and the 1024-byte scratch block holding the last block digested. H0 is enough to finish the derivation without the password, so it is worth as much as the key it produces. All three are locals of the generator, so the exposure lasts from the derivation until the collector reclaims them.

### 8.5 Code ticker

While unlocked and the window is visible, a single coroutine emits on a one-second cadence, computing every visible TOTP entry's current code and the seconds remaining in its period. Entries scrolled out of view are not computed. A hidden window consumes no CPU.

The coroutine is the collection of a cold flow, so hiding the window cancels the collection and with it the ticker. A lock ends the flow instead, and needs no collector to do it: the flow reads the session state, and a state that is no longer `Unlocked` completes it after one empty emission, which leaves no row holding a code from a session that has closed. The list publishes the ids of the rows it has on screen, since the ticker cannot see them for itself, and a scroll recomputes at once rather than at the next second.

HOTP entries are outside the ticker entirely. Their codes change only on explicit request (§5.6), and recomputing one on a timer would advance the counter without the user asking.

Each row shows the remaining fraction of its own period, since entries may have different periods. The countdown ring turns amber in the final five seconds. When a period boundary crosses, the new code replaces the old with a brief crossfade rather than an abrupt swap.

---

## 9. User interface

**Secret disclosure gate.** Three actions put a shared secret where something other than TAuth can read it: copy `otpauth://` URI (§9.4), show QR code (§9.7), and plaintext export (§9.9). Each emits a complete credential, and the medium — clipboard, screen, file — is outside the vault's protection once the action completes. All three carry the same gate: re-entry of the master password even when the session is unlocked, and a one-line statement of what is about to leave the vault. Copying a generated code is not in this set; a code expires or is consumed, a secret does not.

### 9.1 Navigation

A single window. Routing is driven by `SessionState`, not by a navigation library:

- `NoVault` → create-vault screen
- `Locked` → unlock screen
- `Unlocking` → the screen that asked for the password, showing its progress: a creation, an unlock and a settings action that rewrites the vault all run through this state, and the state alone does not say which asked for it. A derivation started from settings leaves the settings screen standing, since routing away from it would take the user off the control they used for the length of an Argon2id derivation
- `Unlocked` → account list, with add / edit / settings / import preview presented as full-screen destinations within the unlocked graph. The preview is the one of the four the user does not route to: it stands for as long as there are rows to decide about, so reading a file opens it and finishing with it returns to the settings screen the file was chosen from

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

Above the list: a search field filtering on issuer and account name, case-insensitively and on substring match; a sort control (manual order, issuer A–Z, recently added); a lock button; a settings button, which is where the destination of §9.8 is entered; and an add button.

Manual reordering by drag. `orderIndex` is renumbered and the vault is written on drop.

The drag handle acts only while the list is in manual order and the search field is empty. A drop yields a position in the whole vault, so it can be read off the list only when the list is showing the whole vault in the order the vault stores it; under either of the other two orderings, or with a query hiding rows, a drop would renumber by a position nothing on screen names. The handle is inert in those states rather than absent, so the row does not change shape as a query is typed.

Row overflow menu: edit, copy code, copy `otpauth://` URI, show QR code (§9.7), delete. Copying the URI is a secret disclosure and carries the gate stated at the head of §9. Delete requires a confirmation dialog naming the account, and is irreversible; recovery requires an export taken earlier.

A copied URI is subject to the same clipboard clear delay as a copied code (§11), matched on the exact string that was placed there.

An empty vault shows an empty state naming the ways an account arrives, rather than an unadorned blank list. It names the three paths §9.5 offers: pasting an `otpauth://` URI, reading an image of the QR code, or typing the details by hand.

### 9.5 Add account

Three input paths in one screen:

1. **Paste URI.** A text field accepting `otpauth://`. Parses on input and shows a live preview of the resolved fields, or the specific parse error.
2. **QR image.** A file picker (`java.awt.FileDialog`) filtering on PNG, JPEG, GIF and BMP. The image is decoded with ZXing's `MultiFormatReader` over a `BufferedImageLuminanceSource` with `HybridBinarizer`, through `GenericMultipleBarcodeReader` so that every code in the image is read rather than the first: one screenshot can hold a page of them. The accounts among the payloads are what is offered — a code can be a payment link, a wireless password, anything at all — and one account is taken without asking while several are presented as a selection list naming each by its issuer and account name and nothing else, since the list stands on screen while it is read. An image holding codes but no account, and an image holding no code, are different things to the person holding it and are not one sentence. The path converges on the same field a paste fills, so one image and one paste reach the same preview.
3. **Manual entry.** Type (TOTP or HOTP, defaulting to TOTP), issuer, account name, secret, and an advanced section for algorithm, digits, and either period or starting counter according to type. The secret field validates base32 on input, against the secret alone rather than through the whole form: the entry model refuses an empty account name before it reaches the secret, so a form checked only through that would answer a base32 mistake by naming a different field. The counter field accepts an unsigned 64-bit value and defaults to 0.

All three converge on the same preview showing the resolved entry. A TOTP preview carries a live sample code. An HOTP preview shows the starting counter and the code that counter would produce, computed without persisting anything, so verifying the entry does not consume a counter value before the account exists. Saving writes the vault immediately.

Screen-region QR capture is listed in §16.8: it requires `java.awt.Robot` screen capture permission, which on macOS triggers a Screen Recording privacy prompt and on Wayland needs a portal integration.

### 9.6 Edit account

Issuer and account name are freely editable. Algorithm, digits, and period or counter are editable behind an "advanced" disclosure carrying a warning that changing them invalidates codes unless the server side matches. The secret is not editable; changing a secret means deleting and re-adding, which prevents a mistyped edit from silently destroying the only copy of a credential. The type is not editable, since TOTP and HOTP take different parameters and switching between them discards one of them.

The counter is editable because resynchronisation requires it: a client that has generated codes beyond the server's look-ahead window can only recover by being set back or forward (§5.6). The field shows the stored value and accepts any unsigned 64-bit value.

### 9.7 Show QR code

A dialog reachable from the row overflow menu, rendering the entry's `otpauth://` URI as a QR code so another authenticator — a phone, a second desktop, a hardware token's companion app — can enrol the same account by scanning the screen. This is the intended migration path off TAuth and the counterpart to QR import in §9.5.

**Encoding.** `QRCodeWriter.encode(uri, BarcodeFormat.QR_CODE, 1, 1, hints)` from `zxing-core`, with `EncodeHintType.ERROR_CORRECTION = ErrorCorrectionLevel.M`, `EncodeHintType.MARGIN = 2` (quiet zone, in modules), and `EncodeHintType.CHARACTER_SET = "UTF-8"`. Level M matches what Google Authenticator's own provisioning codes use and keeps the symbol small: a 160-bit secret with issuer and account name yields a URI of roughly 100–150 characters, a version 6–7 symbol at 41–45 modules square.

The writer scales its result up to whole multiples of the size asked of it and never returns less than the symbol, so a request of one pixel is what returns the module grid itself. The rendering below is stated in modules and needs that grid rather than a bitmap resampled to a size chosen before the canvas is known.

`zxing-core` is a JVM library and the dialog is a screen, so what crosses into `:shared` is a module grid rather than a `BitMatrix`: `QrSymbol` carries the dark modules with the quiet zone already in them, and `QrEncoding` is the seam the shell hands its encoder over through, in the manner of the clipboard and the export destination.

**Rendering.** The `QrSymbol` is drawn onto a Compose `Canvas` as one filled rectangle per dark module. The module size is computed as `floor(canvasPx / symbol.width)` and the symbol is centred with the remainder as extra quiet zone, so no module straddles a fractional pixel boundary. Fractional module edges blur under scaling and scanners reject the result at small sizes far more often than the visual difference suggests.

The symbol is always dark-on-light with a light quiet zone, independent of the application theme. Inverting module polarity for a dark theme breaks a large fraction of scanners, so the dialog draws its own light surface behind the symbol rather than inheriting the theme background. Minimum rendered size is 240×240 logical pixels; the dialog scales the symbol up to the available space in whole-module increments.

**Actions.** Beneath the symbol: the issuer and account name as plain text, so the user can confirm they are exporting the account they intended; "Copy URI"; and "Save as PNG", written with `0600` permissions. The image is rendered from the `QrSymbol` the screen is drawing rather than from a second encode of the same URI, so what lands in the file is the symbol the user was looking at; its modules are laid down whole for the reason the screen lays them down whole. The save is the shell's, since only the shell has a filesystem, and the screen reports the request and whatever comes back. It is offered only over a symbol: a URI the format cannot carry leaves nothing to write.

**Gating.** Displaying the QR places a complete credential on screen in machine-readable form; a photograph, a screenshot, or an active screen-sharing session captures it in full. The dialog carries the secret disclosure gate stated at the head of §9. It closes after 60 seconds without interaction, and suppresses the idle lock timer while open so the vault does not lock underneath a symbol the user is mid-scan.

For an HOTP entry the encoded URI carries the counter as it stands when the dialog opens. Scanning it clones the entry at that position rather than at the position the other authenticator will next need, so the dialog states the counter in text beneath the symbol alongside the issuer and account name.

### 9.8 Settings

Reachable only from the unlocked graph, because the groups marked *policy* below are stored in the vault body and changing one is a vault write.

- **Security** *(policy)* — change master password; re-encrypt vault (DEK rotation).
- **Locking** *(policy)* — idle timeout (off / 1 / 5 / 15 min); lock on minimise; grace period before hide-triggered lock; lock on focus loss (default off).
- **Clipboard** *(policy)* — clear delay (off / 10 / 20 / 60 s).
- **Appearance** *(preference)* — theme (system / light / dark); list sort order.
- **Tray** *(preference)* — minimise to tray; start minimised. Both disabled with an explanation when no tray is available.
- **Data** — vault file location with a reveal-in-file-manager action; the encrypted export; the unencrypted export; import. Each of the three reports in a slot of its own: they fail over different files, and one message naming another's would send the user to the wrong place.
- **About** — version, licence, and the security notes describing what the vault protects against and what it does not.

A policy change is applied in memory and written with the vault before the control reflects it, so a failed write leaves the stored policy and the displayed state in agreement. A preference change writes `preferences.json` and needs no unlocked vault, though the screen that hosts it does.

The distinction is stated once in the screen's header rather than repeated per control: appearance and tray settings live in a plaintext file; everything governing locking lives inside the vault and cannot be changed without the master password.

### 9.9 Export and import

**Encrypted export** produces a copy of the vault file. It is the recommended backup and requires no additional confirmation.

The copy carries the whole vault, so it is created the way §6.6 creates the vault itself: `0600` as a creation attribute, read back before any ciphertext is written, and the write made into the channel the creation opened rather than back through the name it was created under. A destination the user picks is a directory another local user may be able to write to, which is where the difference between those and a `chmod` after the write is the whole file. Where the destination filesystem carries no POSIX modes, an owner-only access control entry is set on the empty file instead, and a filesystem offering neither refuses the export rather than writing it.

Every file TAuth writes outside its own directory goes down that one path, because every one of them carries a secret in a form something other than TAuth reads: this copy, §9.7's saved QR image, and the plaintext export below. What differs between them is what the destination is asked for and what a failure is worded as, not how the file is created.

**Plaintext export** produces a JSON file or a list of `otpauth://` URIs, carrying the secret disclosure gate stated at the head of §9 and a dialog stating that the output is unencrypted. The file is written with `0600` permissions. This is the migration path to other authenticators and is the reason plaintext export exists at all. HOTP entries export with their current counter, which is a point-in-time snapshot: codes generated in TAuth after the export move the vault ahead of the exported file.

The two formats differ in what survives being read back. The URI list is one `otpauth://` URI per line, each line ended, which is what another authenticator enrols from and is all it enrols from. The JSON document is `{"v": 1, "entries": [...]}` over the entry objects of §6.4 unchanged, so it carries the entry ids, the creation times and the stored order, none of which a URI has a field for. The `policy` object is not exported: it governs this application and enrols nothing. Both are written in the order the vault stores rather than the order the entries happen to sit in, so a re-import restores the list as it was left.

The warning is read before the password is asked for, since the password is what the user is being asked to spend on a decision they have not yet been shown. Both formats are offered there, and the dialog states the counter snapshot above as well as what the file holds.

**Import** accepts a plaintext export or a newline-separated list of `otpauth://` URIs, shows a preview with per-entry validity, and detects duplicates by `(issuer, accountName, secret)`, offering skip or add-anyway per duplicate.

A file opening with `{` is read as a document and anything else as a list of URIs, so a document that will not parse reports itself rather than reading as a great many broken URIs. Reading is per line and per element: one that will not parse is refused on its own, naming where it sat in the file and stating the rule it broke rather than the value, since the value is a credential and the preview is on screen. Blank lines are passed over. A document that will not parse, and one carrying no `entries` at all, each produce nothing rather than a preview of nothing.

The secret half of the duplicate key is compared with padding and case set aside, which are what differ between two spellings of one key; nothing on this path decodes a secret. A file carrying one account twice offers the second as a duplicate of the first, on the same rule. The comparison reads the secrets the vault holds, so it happens in the session rather than on a screen, whose entries carry none.

An account arriving takes an id of the receiving vault's making, since one carried in may already name an entry there; the creation time is kept where the document carries one, and is the moment of the import where a URI does not. Accepted accounts are added in one vault write: added one at a time, a batch stopping half way would leave the file holding a part of what the user accepted.

The preview counts what will be added, what the vault already holds and what could not be read, and lists a row per account under its issuer and account name. A duplicate carries the choice §9.9 offers and opens on skipping it; every other account is taken. A refused row names where it sat in the file and the rule it broke, and no row puts a secret on the screen. Nothing can be added while the choices leave nothing to add. The rows carry every secret the file offered, so they end with the write that takes them, with the preview being left, and with the vault being locked.

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

    if (lifecycle.isTrayShown) {
        // dev.nucleusframework.composenativetray, for the reasons §10.2 gives. The drawable rather
        // than a painter, so the desktop sizes the mark rather than the painter claiming a size.
        Tray(
            icon = Res.drawable.tauth,
            tooltip = "TAuth",
            primaryAction = { visible = true },
        ) {
            Item(label = "Show") { visible = true }
            Item(label = "Lock now") { session.lock(LockReason.Manual) }
            Divider()
            Item(label = "Quit") { session.lock(LockReason.Exit); exitApplication() }
        }
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
        icon = tauthIcon(),
    ) {
        TAuthApp(session, prefs, shell)
    }
}
```

The preference document has a single owner. `PreferencesState` holds it, and every writer — the settings screen, the account list's sort control, the window geometry recorder — changes it through `update`, which derives the next document from the value the holder carries and then writes that. A writer copying its own field onto the document as the file held it at launch puts back every other field chosen since, and the geometry recorder writes without being asked, so it would do that on every move of the window.

Two things read that document at different times. The window opens where the document stood at launch, and the state it is given is remembered against that alone, so a preference changed later does not move a window the user has placed. What a close request does, and whether a tray icon stands, read the live value, so a tray preference changed in settings takes effect without a restart.

`shell` carries what §9.8's Data and About groups report and no screen in `:shared` can ask for itself: the vault file's location and a reveal action, the packaged version and licence, where an exported copy is written, and whether the tray settings are offered — which it takes from `WindowLifecycle` rather than asking the toolkit a second time, so the window and the screen answer that question the same way.

`WindowLifecycle.of` takes tray availability and the two tray preferences and answers what a close request does, where the window opens, whether a tray icon exists and whether the tray settings are offered. The window leaves the screen only where a tray icon can bring it back, so the answer turns on `isTraySupported && minimiseToTray` rather than on availability alone: a desktop with no tray and a user who turned the tray off both take the fallback of §10.2. Whether the settings are offered turns on availability alone, since those settings are the controls that set the preferences.

Minimising is the platform's own on every desktop and is not one of those answers. Hiding the window is the close request's alone, which is what leaves `WindowState.isMinimized` an observable thing for the minimise trigger of §8.3 to fire on and for `SecurityPolicy` to govern.

The window opens at the geometry §6.1 holds, clamped to the bounds the model enforces, and a position that is unset is left to the platform to choose. A move or a resize is written back once it settles, so a drag reaches the file as one write rather than as every position it passed through. A minimised, maximised or full screen window records nothing: the extent it reports is that state's rather than the one it returns to, and the geometry standing in the file is the one the window will come back to.

`isSystemTraySupported()` is `java.awt.SystemTray.isSupported()`. `isTraySupported` is a **global** property in `androidx.compose.ui.window`, not an `ApplicationScope` extension, and delegates to the same call. `Tray` is an `ApplicationScope` extension taking `(icon: Painter, state: TrayState, tooltip: String, onAction: () -> Unit, menu: @Composable MenuScope.() -> Unit)`. Both are confirmed present in Compose Multiplatform 1.11.1 (§15).

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

**Linux.** `java.awt.SystemTray.isSupported()` returns true only when a StatusNotifierItem or legacy notification-area host is present. GNOME removed built-in tray support in 3.26. Recovery requires a shell extension: the third-party AppIndicator/KStatusNotifierItem extension, or the official Status Icons extension shipped with GNOME Shell Extensions from GNOME 47, neither of which is installed by default in most distributions. The AppIndicator extension has broken across GNOME major releases, notably at GNOME 48. The practical consequence is that a large fraction of GNOME users have no tray.

The application must therefore never become invisible and unquittable. When `isTraySupported` is false, the tray-related settings are disabled with an explanation, `onCloseRequest` exits the application, and `startMinimised` opens the window minimised on the taskbar rather than hidden with nothing to restore it. A tray the desktop supports and the user has turned off reaches the same close and startup behaviour, since no icon is on screen to raise a hidden window either way; its settings stay offered, because they are what turns the tray back on.

Ubuntu ships `ubuntu-appindicators` enabled by default, so the AWT tray works there without user action; on the reference machine (§15) `SystemTray.isSupported()` returns true under a Wayland session, because AWT runs through XWayland with the X11 toolkit. A GNOME installation without that extension, which is most non-Ubuntu GNOME, reports false and takes the degraded path above.

The tray icon and its menu are `dev.nucleusframework:composenativetray`, which talks to StatusNotifierItem over D-Bus directly. Compose's own `Tray` is `java.awt.SystemTray` with a `java.awt.PopupMenu`, and neither is drawn by Compose: the menu is AWT's own rendering, which no theme reaches, and the icon is handed to a tray slot at the size the painter claims rather than the size the desktop asks for, so it arrives cropped on a GNOME shell scaling it. The library renders the desktop's own menu and takes the drawable, sizing the mark itself.

Its primary action follows each platform: a single left click on Windows, macOS and KDE Plasma, and a double left click on GNOME, which is the convention there.

Whether a tray exists is still `java.awt.SystemTray.isSupported()`, since the library exposes no equivalent. That answer is a proxy rather than the same question: AWT reports on an XEmbed notification area while the library speaks StatusNotifierItem, so a desktop offering one and not the other is answered wrongly. What it decides is the degraded path above, and it errs in the direction that costs a hidden window rather than an unquittable one only where AWT is the pessimistic of the two.

**macOS.** The tray icon appears in the menu bar, sized for it at 22×22 logical points. `java.awt.TrayIcon` takes a plain `Image` and carries no template flag, so the menu bar draws the image as given rather than tinting it to the current appearance; a monochrome glyph therefore keeps one colour across a light and a dark menu bar and disappears into one of them. The icon carries the background it reads against, which costs the appearance-matched look a template image would have had. TAuth keeps its Dock icon. A pure menu-bar application with no Dock icon is achievable with `LSUIElement`:

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

Implementation: the role is claimed before the composition starts, so a launch that hands its request over constructs no window, no session and no `VaultStore`, and ends by returning from `main`. The claim attempts an exclusive `FileLock` on `<vaultDir>/instance.lock`. On success, bind a `ServerSocket` on `127.0.0.1:0`, write the chosen port into the lock file's sibling `instance.port`, and listen for a `SHOW` command. On failure to acquire the lock, read the port, connect, send `SHOW`, and wait for the running instance's acknowledgement; the launch that receives it exits with status 0. The running instance makes its window visible and requests focus, and reports that raise as a show request rather than as the user returning to the window: the relock collector of §10.1 cancels a pending relock only for the user's return, so a window raised by `SHOW` comes up with a scheduled relock still standing.

The exit turns on the acknowledgement rather than on the send, because a port a crashed instance recorded can have been taken since by an unrelated program: a launch that exited on the send alone would raise nothing and report nothing. The running instance records the request before it answers, so an acknowledged request is a request that will be acted on.

A launch that becomes primary replaces `instance.port`, which covers whatever a crashed instance left there. The lock file is never unlinked: unlinking releases no lock a live process holds on the inode, and the next launch would then take a second lock on a new inode, which is two primaries.

A launch that can neither take the lock nor reach a running instance opens its window without single-instance service and says so on screen, since exiting silently would leave the application unstartable for as long as whatever holds the lock does. That state costs a vault: two live instances each hold their own decrypted body, and a save rewrites the whole file, so the later save drops whatever the other wrote. `VaultStore`'s lock spans one `write()` and refuses only writes that overlap it, and `read()` takes no lock at all, so nothing reports the loss. Closing it needs the write to be a compare-and-swap against the file it read, which is not in this design.

A `SHOW` arrives over loopback, which carries no owner check, so it can come from any process on the machine and from a different OS user. That is why the raise it causes is not the user's return: a show request that cancelled a pending relock would let anything on the machine hold an unlocked window open on screen.

What ends a raise is the first pointer or key event after it, read from the same toolkit stream the idle trigger of §8.3 watches. The window is raised until someone is at the machine, and only that arrival puts the presence of §10.1 back to the user's, so a relock the raise came up under fires as scheduled if nobody comes. Focus is not that evidence, since the raise asks for the focus itself. Neither are `MOUSE_ENTERED` and `MOUSE_EXITED`: a window mapped, raised or moved under a stationary pointer enters and leaves it, which reports the window arriving rather than a person, and taking those would end every raise on the raise. Real movement arrives as `MOUSE_MOVED`, so nothing a person does is lost. The exclusion holds for the idle trigger for the same reason.

---

## 11. Clipboard

Copy uses `java.awt.Toolkit.getDefaultToolkit().systemClipboard`. After the configured delay, the clipboard is cleared **only if its current contents still equal the exact string TAuth placed there**, so that the timer never destroys something the user copied in the meantime. This covers generated codes and copied `otpauth://` URIs alike. The comparison reads the clipboard contents, which on some platforms can throw `IllegalStateException` under contention; failures are caught and the clear is skipped.

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

Every error maps to a specific user-facing message. `WrongPassword` and `IntegrityFailure` in particular must never share a message: one means "try again", the other means "this file has been modified or damaged". `WrongPassword` says the password did not work and claims nothing about the file, which §6.7 explains. `NoSuchEntry` and `Corrupt` are separated for the same reason: an entry deleted between a click and the operation it started is not a damaged vault.

`InvalidEntry` covers every value an operation refuses to store: the entry model's rules where the model is the one refusing, and rules an operation holds of its own — an id already in the vault, a counter with no successor — where it is. The detail states the rule and never the value, because it reaches log output and the screen.

The hierarchy carries a view per operation whose failure reaches a message, and a view per step within one, each holding the cases that operation or step produces. A step's view names the operations it reaches, so an operation's cases are the union of its steps rather than a list repeated on every case. A `when` over a view has a branch only for a case that view admits, which is what makes a message mapping over one testable at every branch.

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

`ExportError` is a second hierarchy of the same shape, over what stops a file reaching the place the user chose. `VaultUnreadable` belongs to a copy of the vault, which reads the vault first; `NotRestricted` and `Io` belong to the write itself, which is `FileWriteError` and is what §9.7's saved image reports. A copy of the vault is `VaultExportError`, the union of the two. The wording differs between the operations for the same case, since one has a vault to say is unchanged and the other has not, which is why they are separate mappings rather than one.

Two cases belong to a write alone. `TooLarge` is the encoder refusing to produce a file the reader would refuse, and a read past the same ceiling reports `Corrupt` instead; `LockedByAnotherProcess` is the file lock a write takes and a read does not. Neither can reach an unlock, an export or a disclosure.

Reading an image is a view of its own rather than the import's, since it names no vault: the account a code holds is not stored by being found, so a vault closed under the reading is not one of its cases. `Corrupt` reaches an import because the file offered is a document TAuth did not necessarily write, and one that is not an export is damaged in the sense that case names. Its message is the import's own: the case is shared, the wording per operation is not, which is what a view per operation is for.

Adding an entry and changing one are separate views because they report different cases: an add decodes a secret and so reports `InvalidSecret`, while it names no existing entry and so cannot report `NoSuchEntry`; a change is the reverse. One view over both would leave each of their screens a branch nothing can reach.

A view is named for a step rather than for a call site, so two branches are admitted by a view and unreachable on the path that view serves. `TooLarge` is admitted by a creation, whose body is empty and cannot approach the encoder's ceiling, and `InvalidSecret` is admitted by the same, whose body carries no entry to decode. `UnsupportedVersion` is admitted by every commit, whose header and body versions are the writer's own constants rather than anything read. Naming a view per call site instead would need a type per operation over the same steps; each of these branches is worded for the case it names, so one reached by a later change says the right thing.

---

## 13. Testing

### 13.1 `commonTest`

**HOTP** — all ten RFC 4226 Appendix D vectors, each as a separate assertion naming its counter. Counter 0 and the 64-bit maximum, confirming the counter is encoded as an unsigned 64-bit big-endian value throughout.

**TOTP** — all eighteen RFC 6238 Appendix B vectors, each as a separate assertion with the algorithm, timestamp and expected value visible in the test name. Truncation of the eight-digit RFC values to six digits for the default configuration. Period boundary behaviour at exactly `T` and `T-1`. The `20000000000` vector, exercising 64-bit `T`.

**Shared core** — feeding `floor(t / period)` through the HOTP entry point reproduces every TOTP vector, proving one implementation rather than two.

**Base32** — RFC 4648 §10 vectors, padded and unpadded input, padding of the wrong length in both directions, every trailing group length that cannot end an encoding, lowercase input, embedded whitespace, invalid characters, empty input.

**otpauth URI** — issuer in the label prefix only; issuer as a parameter only; both present and equal; both present and conflicting; percent-encoded label with a colon and with spaces; missing secret; unknown type; digits outside 6–8; a period below the minimum; `hotp` without `counter`; `totp` carrying `counter`; `hotp` carrying `period`; counter at the 64-bit maximum; unknown parameters ignored; parameter names matched without regard to case; leading spaces and a trailing newline shed from the input while a leading U+2028 is not; a trailing space shed rather than kept by the last parameter's value; round-trip build-then-parse for every entry configuration of both types.

**Error views** — each view's membership asserted as a whole set rather than case by case, so a membership added to a case fails the view it joined just as one dropped fails the view it left. The two cases a write alone produces are asserted absent from an unlock, which is what keeps the encoder's size ceiling and a held file lock off the unlock screen. A case added to the hierarchy stops the naming table compiling, and naming it there fails the count until it joins the list every view is measured over. Reading an image is asserted to hold the image's own two cases and nothing about a vault, which is what separates it from the import's view over the same file-reading failures. The views over `ExportError` are asserted the same way and for the same reason: writing a file reports the destination and nothing about a vault, while a copy of the vault reports the read as well.

**Vault codec** — round trip with an empty entry list and with several hundred entries; wrong password produces `WrongPassword`; a flipped bit in the ciphertext or the GCM tag produces `IntegrityFailure`; a flipped bit at every offset across the header produces `Corrupt` and never `WrongPassword`, swept exhaustively rather than sampled; a flipped bit in the CRC itself produces `Corrupt`; a flipped bit at every offset ahead of the CRC produces `Corrupt`; a modified `headerLength` produces `Corrupt` and never a silent success; truncated file; a version byte the writer never wrote produces `Corrupt` while a later version whose CRC agrees produces `UnsupportedVersion`; an unknown header key is tolerated; two successive writes of identical content produce different ciphertext, proving nonce freshness.

Every value drawn from the CSPRNG — the DEK, the salt, the wrap nonce — is compared across two independently created vaults, because a constant satisfies any assertion made within one. Rotation keeps the salt and draws a fresh wrap nonce, which matters because it wraps under the KEK that wrapped the previous key. A header or body `v` the reader does not know produces `UnsupportedVersion`; a `headerLength` with the high bit set produces `Corrupt` rather than a backwards slice. A `v` written as a quoted digit reads as that version on both paths, and an entry's quoted `period` reads as that period, which is the latitude §6.7 records.

Each of the codec's three key arrays is lent to a block, so each zeroing is asserted rather than inspected: the KEK through `withKek`, a freshly drawn DEK through `withFreshDek`, and the DEK an unlock hands over through `adoptedOrZeroed`. Every one is checked both after a block that returns and after a block that throws. The adopted DEK is also checked to survive the one path that keeps it, since zeroing there would hand back a vault whose key is zeros.

**Entry model** — `orderIndex` renumbering on insert, delete and reorder; an `id` in canonical form carrying the version 7 nibble, ordering and uniqueness being `Uuid.generateV7`'s contract rather than this project's; `period`/`counter` pairing enforced per type; a `hotp` entry with a null counter rejected as `Corrupt`.

**Edit model** — each field an edit may change, one case per field; the secret, the id, the creation time, the order index and the type unchanged by an edit that changes everything it can reach; a hotp counter set to a resynchronisation value. Every rule the entry model holds is refused through the edit rather than thrown: a digit count outside the range, a counter on a `totp` entry, a `hotp` entry left with none, a period on a `hotp` entry, an empty account name, a colon in one, an empty issuer. A dropped issuer is accepted, and a refusal carries the rule it broke rather than the value.

**Lock reasons** — the arming and the grace period of each trigger, one case per reason, read from a single policy whose durations all differ so that a reason reading the wrong field answers with a number no case accepts. Hiding to the tray, a manual lock and an exit are armed whatever the policy says; minimise and focus loss follow their switches; a zero idle timeout disarms the idle trigger rather than firing it at once.

**Security policy** — a body with no `policy` object yields the full defaults; a partial `policy` fills the remainder from defaults; each default is asserted field by field so a later change names itself; a negative duration is rejected; a policy edit round-trips through a write and read; tampering with a policy value in the ciphertext produces `IntegrityFailure` rather than an altered policy.

**Password check** — the password that created a vault is accepted against the header that vault holds, and one differing by a single character is refused as `WrongPassword`. A salt that is not base64, a wrapped key of the wrong size and a wrap nonce of the wrong size are each refused as `Corrupt` rather than as a wrong password, which is what keeps a header an attacker rewrote from reading as a mistyped password. The body takes no part on this path and nothing here asserts anything about it. The zeroing of the derived key and of the key it unwraps is not observable from outside the codec and is asserted nowhere.

**Entry URI** — an entry at the default algorithm, digit count and period builds a URI carrying none of the three, and one off all three under an issuer with a space in it builds each of them; an hotp entry carries its counter, at a middling value and at the unsigned 64-bit maximum; an entry with no issuer builds a label of the account name alone. Every expectation is a URI written out in the test rather than one rebuilt from the entry it describes, since the format omits a parameter equal to its default and a rebuilt expectation agrees with whatever fields the build happened to read. The secret survives a build-then-parse round trip, and no rendering of the URI object carries it.

**Entry drafts** — each field an add form and an edit form collect, one case per field. An issuer left blank resolves to an absent issuer rather than an empty one. The type decides which moving factor is read, so a counter typed into a totp form is dropped and a period typed into an hotp one is; on the edit form the type is the entry's rather than the form's. A counter past the unsigned 64-bit maximum, a half-typed number in any numeric field, an empty account name, a colon in one, a secret that is not base32 and a digit count outside the range are each refused, and the refusal states the rule rather than the value it refused. No rendering of a draft carries the secret.

**QR symbol** — the grid a symbol crosses the module line as, and the arithmetic that puts it on a canvas. A module is read by its column and its row, and the module at the transposed position is a different one, which is what a mirrored rendering would agree with. The grid does not follow the array it was built from, since a caller keeping that array could otherwise move a module under a drawing that reads it every frame; a grid that is not square is refused. A module is a whole number of pixels, what the modules do not fill is split between the two sides, a canvas the modules fill exactly starts at its own edge, and a canvas smaller than the symbol lays down no module rather than a fraction of one.

**Plaintext export** — the two shapes the accounts leave in, over a body whose order indices run against the list they sit in, so an export taking the list as it finds it disagrees with one reading the order the vault stores. A URI list carries one line an account, ends its last line, and is empty rather than a blank line for a vault holding nothing; a JSON document carries every account, states the version it was written at, and carries the id, the creation time and the order index a URI has no field for. Neither carries the policy. A counter-based account exports at the counter it stands at.

**Import rows** — a list of URIs reads one account a line, carrying the account, the counter and a creation time of the import's own; blank lines are passed over and a line that will not parse is refused on its own, naming where it sat and quoting neither the secret nor the value it refused. A document reads every account it carries, keeps the creation time it carries, and gives each an id of the receiving vault's making; one the entry model refuses is refused on its own and states the rule, and one in no shape at all is refused without quoting what it was reading. A file that opens as a document and is not one, and a document carrying no entries, are unreadable rather than empty, and the message quotes none of the file. An account the vault already holds is a duplicate, one it does not is not, a secret spelled with other padding or case is the same account, and a file carrying one account twice offers the second as the duplicate. What an import accepts is every account by default and a duplicate only once its position is chosen; a position no duplicate sits at adds nothing, and a refused row is no account whatever is chosen.

**Scanned codes** — an account among the codes is offered and one that is not is passed over, which over a mixture is the accounts alone; every account in an image is offered, and an image holding none offers nothing. A choice is named by its issuer and account name, by its account alone where it has no issuer, and by nothing that carries the secret.

**Code grouping** — six, seven and eight digit codes each break in one place, with the left group never the shorter of the two; a code padded with a leading zero keeps it.

**Preview code** — the code an account would produce before it is stored, against published vectors alone: the RFC 6238 SHA-1 values at 59 and at 1111111109 seconds, the SHA-256 value at 59 seconds under that algorithm's own seed, the same account read at six digits, and a period the account names rather than the default; the RFC 4226 counter 0 and counter 1 values at eight digits and at six. An hotp preview is asserted not to move with the clock, which is what would show a code no server computes. The zeroing of the decoded key is not observable from outside and is asserted nowhere.

**Account order** — the search matches a substring of the account name or of the issuer without regard to case, refuses one that matches neither, and matches an account with no issuer on its name alone; an empty query and one of spaces alone each match an account outright. The three orderings run over one set of four accounts whose order indices, issuers, account names and creation times all differ, with two issuers differing only in case so that an ordering comparing them byte for byte separates two accounts the user reads as one provider. A drop lands one row on, stays put under half a row, moves up, clamps to either end of the list, and stays where it was against a list that has not been measured. The pitch a drop divides by is the distance between two adjacent rows rather than one row's height, read from offsets whose first is negative as a scrolled list reports it, so a pitch taken from that offset alone rather than from the difference is wrong here; it falls back to the single row's height when only one is on screen and to nothing when none is. A six-gap drag divides by the pitch that function returns, with the distance travelled written out rather than derived from it, so the row height as the divisor overshoots the drop by one place rather than moving with it.

**Countdown** — the second at which the ring changes colour, stated as a literal in the test rather than read from the constant under test, read on the boundary, one second above it, at a whole period and at one second left; the light and the dark colour sets each supply their own running and expiring colours. The boundary and the second above it are the pair that moves it; the other two sit well inside their bands and would not. The sweep is read at the same instant under a thirty-second and a sixty-second period, which differ only if the period the code was generated under is the one dividing it; a whole period fills the ring, and a period of nothing fills nothing rather than dividing by zero.

### 13.2 `jvmTest`

**Crypto primitives** — Argon2id against published Argon2 reference vectors, confirming BouncyCastle is invoked with the intended version and parameters; AES-GCM against NIST test vectors; HMAC against RFC 2202 and RFC 4231 vectors, including the case 6 keys that exceed the hash's block size and so are hashed down before use; base64 over input producing `+` and `/`, and rejecting the URL-safe alphabet that replaces them; the generator behind `secureRandomBytes` is asserted to be a `java.security.SecureRandom`, which nothing about the bytes themselves establishes from inside one process.

The Argon2 cost test states the parameters as literals on the reference side, never through the constants under test, and each constant has a test of its own naming its value. The cost travels nowhere in the file, so these are the only things standing between a hand-edited constant and a silently weaker KDF.

**Vault store** — atomic write leaves no `.tmp` on success; a failed write leaves the original file intact and readable; a write that fails at the rename leaves the whole new vault in `vault.tauth.tmp`; a vault behind a directory the process cannot traverse is reported as unreadable rather than as absent; POSIX permissions are `0600` on the vault and the lock file and `0700` on the directory, including a directory or lock file that already existed too widely; concurrent writes from two `VaultStore` instances serialise on the in-process lock and leave one whole payload rather than a mixture. A write that cannot take the file lock reports `LockedByAnotherProcess` and leaves the previous vault byte for byte, driven by an injected channel whose `tryLock` declines the way it does when another process holds the lock — two channels in one JVM collide instead, and both stores queue on the in-process lock before either reaches the file lock.

**Vault paths** — resolution under a set `XDG_DATA_HOME`, an unset one, a blank one and a relative one, the same for `%APPDATA%`, an empty home leaving the location unresolved, and per-OS branches driven by an injected OS identifier rather than the real `os.name`.

**Session** — `lock()` zeroes the DEK array, verified by retaining a reference to the backing array; the KEK is zeroed after unwrap; scheduled lock fires after the grace period; scheduled lock is cancelled when the user brings the window back, and stands when another process's show request raises it; the ticker stops on lock; the ticker never computes an HOTP entry; `scheduleLock` reads its grace period and arming from the session's policy and is a no-op against a locked vault. A lock zeroes every decoded secret and clears the clipboard; an unlocked entry has no field holding the base32 secret, and each secret decodes to the key that base32 stands for; a second `scheduleLock` does not push a pending deadline out; a lock landing during a derivation leaves the vault locked and the unlock reporting `VaultClosed`; a trigger arriving during a derivation locks the vault once the body is open, and one the policy disarms arriving ahead of an armed one does not swallow it; a lock drops the timer scheduled before it, and a schedule after that lock arms a timer of its own; a body holding two entries under one id is refused; creation against a path that already holds a vault is refused and writes nothing. A tick carries the published RFC 6238 code for the instant on a fixed clock, computed under the algorithm and the period the entry itself names rather than the defaults; the seconds it reports run out on the period boundary; a row scrolled into view is computed before the next tick and one scrolled out of view is not computed at all; a lock leaves an empty map behind and ends the ticker, and a collection cancelled as the window hides emits nothing further.

**Entry operations** — add, edit, delete and reorder each write the vault, and what the file holds afterwards is read back through the codec rather than taken from the session. An added entry lands past the last order index whatever index it arrived with and lends the key its base32 stands for; an id already in the vault is refused and writes nothing; a delete zeroes the key it drops and closes the gap it leaves in the order; a move renumbers densely and an index past either end is that end; an operation naming an entry the vault does not hold reports `NoSuchEntry`, and one against a locked vault reports `VaultClosed`. Every operation is repeated against a refused write: the entries, the order, the counter, the decoded keys and the published state all stay as the file still holds them, a delete's key stays live and lendable, and an added entry's key is neither installed nor left alive. Reading an import is exercised through the session as well: it offers the accounts a file carries, marks one the vault already holds against the vault's own secrets, reports `VaultClosed` against a locked vault, and reads no file of its own. A batch is one write however many accounts it carries, reaches the file whole, keeps the order it arrived in, lands past the entries already stored and leaves every key in it lendable; an empty one writes nothing, one naming an id the vault holds or naming one id twice is refused, and one against a refused write leaves the entries as they were with no key behind. Ordering is asserted from inside the write, which is the only point that separates the two orders: the bytes being written already carry the change while the state the session publishes does not. A generated code is computed under the algorithm and the digit count its own entry names, each against a published vector, since an entry sitting at the defaults agrees with a session that reads neither field; a counter at the unsigned 64-bit maximum is refused, stays where it is and writes nothing. An entry write landing during a derivation waits for it, since one that did not would be committed to the file and then dropped by the install of the body that derivation had already read.

**Secret disclosure** — the master password is checked while the vault stays open, against the header the open vault holds: the vault's own password is accepted and one differing by a single character is refused, and after either the published state and the decoded keys are exactly as they were, so a mistyped answer at the gate is not a lock. A check neither reads the file nor writes it, which a check succeeding after the file behind it has gone establishes rather than assumes. A check against a locked vault reports `VaultClosed`. The whole vault discloses through the same check: as a list of URIs compared against URIs written out in the test, and as a document carrying every account in the order the vault holds; a refused password discloses nothing at all and reports the password, a locked vault reports `VaultClosed`, and neither reads nor writes the file. The URI a disclosure returns carries the fields its entry names — a spaced issuer with a non-default algorithm, digit count and period on one entry, a counter on the other — and is compared against a URI written out in the test. A refused password returns no URI at all and reports the password rather than the entry; an id the vault does not hold and a locked vault each disclose nothing. That no published entry carries the secret the URI does is asserted too, but `UnlockedEntry` has no field it could be in, so the assertion holds of an empty list and of any state whatever: it is a fence against a field being added, not evidence about this work.

**Scan state** — the half of the image path that holds what was found, tested without a composition. One account is taken without asking and offers no choice; several are offered as a choice, take none of them yet, and the one chosen is the one taken, with choosing ending the choice and abandoning it taking nothing. A code that is not an account and an image holding no code each say so, and say different things; an image the user declined says nothing and takes nothing; one the shell could not read reports what it said. A second read opens on nothing the first reported.

**Import preview** — the summary counts what will be added and follows a duplicate being taken; an account is named by its issuer and account name and no row carries the secret it stands for. A duplicate says the vault already holds it and opens on skipping, an account the vault does not hold offers no choice at all, and choosing one reports its position. A refused row names where it sat and the rule it broke. Importing and cancelling each report themselves, a file with nothing left to add holds the import back until a duplicate is taken, and a write in flight holds the controls. A message is asserted for four of the failures storing new entries reports, including the wording that says none of them landed.

**Import state** — the half of the import that holds the rows, tested without a composition. A file read opens a preview holding what it offered; one the user declined opens none and reports nothing, and one the shell or the vault refused reports what each said and opens none. A duplicate is taken by its position and taken twice is left out again. What is written is what the preview accepted, with a taken duplicate among it; a write that landed drops the rows, one that was refused keeps the preview up and reports what it said, and a second read opens on none of the first read's choices.

**Unencrypted export** — the warning is on screen before any password is asked for and nothing is disclosed while it stands; dismissing it writes nothing and ends the request. The format chosen there is the format disclosed and the format written, and the flow opens on JSON. An accepted password writes what was disclosed and ends the request; a refused one writes nothing, leaves the gate standing and says the password did not open the vault. A destination that refused the file is reported and one that took it reports nothing.

**Secret disclosure gate** — the statement the caller supplied is on screen, the field opens focused, and the confirm button and the field's Done action each hand over the characters typed and each refuse an empty entry, which would otherwise reach the check as a zero-length password. A running check disables the button and refuses a second submission behind it, while the field goes on taking characters that reach the caller once the check ends; the progress indicator is on screen for a check and absent otherwise; cancelling reports a dismissal and hands over nothing. A message is asserted for each of the four failures a disclosure reports — a wrong password, a damaged header, an entry deleted underneath the gate and a lock that overtook the check — with the wrong-password and damaged-file messages kept apart in both directions. The check reads the header the open vault holds rather than the file, so what its damaged case is about is that header and nothing about the file on disk reaches this mapping.

**Gate state** — the half of the gate that holds the password, tested without a composition. The array handed to a check is zeroed on every path out of it: after a refusal, after a confirmation arriving with no gate open, and when a lock cancels the scope mid-derivation, which is the case the gate exists for and the one where nothing else would wipe it — the array is a copy no field holder owns. A check whose gate was dismissed while it ran discloses nothing when it finishes, does not close a gate opened after it, and leaves no check running; a check that is not dismissed discloses what it was given and keeps the gate up on a refusal.

**Account row** — the issuer, the account name and the code in its two groups, at six digits and at eight; the countdown reports a running code above the boundary and an expiring one on it, read through the description the ring carries rather than through its colour, and reports how much of its period is left as a range a screen reader announces, which is the arc's own fraction and the only readable form of it: a sixty-second account sweeps half what a thirty-second one does at the same instant. An hotp row shows its counter and no code at all until it is given one, which the same row carrying that same code once it is given one makes falsifiable; it carries no countdown, and offers nothing to collapse until a code is on it. A disabled generate control reports nothing when pressed. Each item of the overflow menu reports what it names, and copying a code is offered only while one is on screen.

**Show QR** — the symbol is on screen through the description it carries, which is the only part of a drawing a test reaches; the account it stands for is named beneath it by issuer and account name. An hotp account states its counter and what scanning that counter does, and a totp account states no counter at all. A URI the format cannot carry says so and draws nothing, rather than leaving the dialog blank. Copying reports the request and closing reports a dismissal. A save is offered where there is somewhere to write and absent where there is not, takes no second request while one is running, and reports a message for each of the two failures writing a file produces. The dialog stands through a save in flight rather than closing on its interval, since the file dialog it is waiting on is the user standing in front of one. The dialog closes on its own after a minute with nobody at it, and an interaction puts that interval back, which is asserted by using it just inside the minute and finding it still standing a minute later. What the symbol looks like is asserted nowhere: the semantics tree carries the description and not a pixel, and only the scanners of §13.3 read the rendering.

**Password attempt** — the array a password field hands over is a copy no holder owns, and this is what zeroes it: after a refusal, after an acceptance, and when the scope is cancelled mid-derivation, which is a lock or a closing window and the one path where nothing else would. A refusal is reported and an acceptance reports nothing; an attempt says it is running until its derivation ends and not once it has, which is what decides whether the create or the unlock screen shows the progress of a derivation, since one attempt is held per operation and the session's state does not say which of them asked.

**Account list** — the ids of the rows on screen are published, which is the only way the ticker learns them, and a row below the fold of a viewport too short to hold the list is left out of that set. A search on either field keeps the row that matches and drops the one that does not; the sort control reports what was chosen, and each of the three orderings lays the rows out from their positions on screen, over three accounts arranged so that each ordering produces a layout neither of the others does — two rows would admit only two layouts between three orderings, leaving one ordering indistinguishable from another whatever the fixtures held. A tapped code reaches the clipboard with the clear delay in force and the row counts that same delay down a second at a time, says so once when no clear is scheduled, and says the copy was refused when the clipboard refuses it. An hotp row carries no code until the control is pressed; the control goes dead behind a generation and a second press inside that interval reaches the vault not at all, which is the two counter values a double-tap would otherwise spend; the code stays once the interval has passed and goes when the row is collapsed; a refused generation shows no code and leaves the control live. Copying the URI puts the gate up over the account it names and nothing on the clipboard; a refused password leaves the gate standing, says the password did not open the vault, and still puts nothing there; the accepted one closes the gate, puts the URI on the clipboard and counts it down as a code does. Showing the QR code puts up a gate of its own, stating the screen rather than the clipboard, and draws nothing until a password is accepted; a refused one draws nothing either. What the accepted one draws is asserted as the text the screen handed the encoder, since the drawing itself says nothing about which URI it stands for. Copying from the dialog reaches the clipboard under the same delay, and closing takes the code off the screen. Saving hands the shell the symbol on screen rather than the URI behind it, which is the whole of what makes the file the symbol the user saw, and a refused save is reported over the code. A code on screen holds the idle lock off and the code leaving the screen lets it back, while the gate in front of it holds nothing off, since a gate is typed at and typing is what the watch is listening for. An hotp row copies the code it generated rather than one arriving under its id from the ticker, which the ticker does not produce and the screen therefore must not read. A delete names the account, reports it when confirmed and reports nothing when dismissed. A drag past the end of the list reports the last position; one in an ordering that is not the stored order and one while a query is filtering the list each report nothing, and clearing the query brings the handle back. A message is asserted for each of the seven failures a change to an entry reports: the account that is no longer there, a refused value quoting the rule it broke, a lock before the write, another process holding the file, a failed write, a vault past the size the writer will produce, and a version the reader does not know. The routing suite asserts the same slot over a refused delete, which is where the list's error reaches it from the session rather than from a fixture.

**Entry preview** — a message is asserted for each of the three ways typed or pasted values fail to make an account: a URI the parser does not read, a secret that will not decode, and details the entry model refuses, each quoting the rule it broke. Nothing about a vault file reaches this mapping, since a draft is resolved without opening or writing one.

**Add account** — nothing is previewed and nothing can be saved before anything is entered. A pasted URI previews its issuer, its account name and a sample code that is the published value for the second the caller named; an hotp one previews its starting counter and the code that counter produces; a URI that does not parse is reported in place of a preview and leaves the save disabled. Saving hands over the account that was resolved, with its secret. The typed form reaches the same sample code as the URI for the same account, and the account name, the issuer, the secret, the digit count, the period, the algorithm, the counter and the type each reach the account that is saved. The advanced fields are hidden until asked for, and the form offers the moving factor its type carries and not the other; the counter field opens reading zero, without anything being typed into it, since an account enrolled at any other position is one the server is not expecting. The secret is checked on its own: a base32 mistake is reported against that field with the account name left blank, which is the field the entry model would otherwise refuse first, and a secret that is empty or valid reports nothing. Cancelling reports the cancellation and hands over no account. A message is asserted for each of the seven failures storing a new entry reports: a refused value quoting the rule it broke, a secret that will not decode reported against the secret, a lock before the write, another process holding the file, a failed write, a vault past the size the writer will produce, and a version the reader does not know. The image path is offered only where the shell can read one; an account read from an image reaches the preview and is what a save hands over, an image holding no code and a code that is not an account each say so, an image that could not be read says so, and several accounts are offered by name with the one chosen reaching the preview.

**Edit account** — the screen opens on the account name stored, states the type rather than offering it, and carries no secret field at all, which is `EntryEdit` having no field for one rather than a check on this screen. The account name and the issuer each reach the edit handed over, and an issuer cleared away is handed over as absent. The advanced disclosure is hidden until asked for and carries its warning; the digit count, the period and the algorithm each reach the edit; a totp entry is offered no counter and an hotp entry no period; an hotp counter set backwards and one at the unsigned 64-bit maximum each reach the edit, which is the resynchronisation §9.6 exists for. A half-typed number holds the save back and says what it is waiting for, while the rules on a name are left to the entry model and reported in the words it refused them. Cancelling hands over nothing. A message is asserted for each of the seven failures a change to an entry reports: the account that is no longer there, a refused value quoting the rule it broke, a lock before the write, another process holding the file, a failed write, a vault past the size the writer will produce, and a version the reader does not know. An add reports a secret that will not decode and a change cannot, so the two screens hold different mappings and neither slot takes the other's failure.

**Application routing** — a location holding no vault opens the create screen and one holding a vault opens the unlock screen; a creation driven through the create screen reaches the account list, which is the only place the create path is exercised end to end; an unlock reaches the account list; the add destination opens inside the unlocked graph and returns to the list when it is cancelled; a lock taken from the list and a lock taken from the add destination each leave the unlocked graph for the unlock screen. The unlock screen is drawn for a locked vault whatever route the graph was left on, so what establishes that a lock also resets the route is where the next unlock lands: on the list, not on the destination the lock interrupted. The edit destination opens from a row's overflow menu. An account pasted into the add screen and a rename made on the edit screen each reach the vault file, read back through the codec rather than off the screen, which is the only place the two save paths are exercised at all. A refused delete is reported on the list and does not follow the user onto the add screen, which is the separation of a list failure from a destination's save failure. The fallback that leaves the edit destination when the account it named is deleted underneath it is reached by no test. Neither is the import wiring: the holder living inside the unlocked graph, the route following the rows, and the file being chosen and read are each asserted in their own suites and nowhere crossed with the routing.

**Preferences** — `preferences.json` absent, empty, malformed or holding unknown keys all yield usable defaults rather than a startup failure, since the file is attacker-writable and the application must open regardless. No security-relevant field is read from it.

**Settings** — the screen is read against a policy and a preference document with every field off its default, so a control drawing anything of its own disagrees with the fixture in every field rather than in none. Each of the five policy controls opens on the value the policy carries and hands over one whole policy, asserted against a literal, so the field chosen and the four left alone are one assertion. A policy the caller does not move leaves the control where it stood, which is the screen holding no state of its own and is what makes a refused vault write show as a refusal rather than as a change. Each preference control opens on the stored value and hands over what was chosen. The tray controls are disabled and explained where no tray is available and enabled where one is. The header's statement is asserted as a literal written out in the test, and the notes on re-encryption, export, the missing tray and the About group are each asserted to repeat none of it, which is §9.8's "stated once". A password change is held back until a current password is typed, while the two new ones differ, and while the new one is short; the two arrays it hands over are the characters that were typed; a re-encryption hands over its own. A running derivation holds the timeout choices, the locking switches and the export.

An export reports in a slot of its own, since neither half of one is a vault write: a destination that cannot restrict the copy, a destination that could not be written and a vault that could not be read each produce their own message, and the three read cases asserted name a read rather than a write. Each slot is asserted empty for a failure belonging to the other.

Three of the five policy controls — the minimise lock, the focus-loss lock and the grace period — are asserted at the screen's callback and no further; only the idle timeout and the clipboard delay are followed to the vault file. `SettingsWork` zeroing the password arrays it was handed shares no test. A message is asserted for each of the eight failures a rewrite of the whole file reports — a wrong password, a lock during the change, another process holding the file, a read or write that failed, a vault past the size the writer will produce, a damaged file, a version the reader does not know, and a file that is not there — with the wrong-password and damaged-file messages kept apart in both directions. A rewrite reads the file and writes it back, so the failure that can be either half says so rather than naming one, and the damaged case claims nothing about whether the change landed. The export's read half asserts all three of its own: a file that is not there, a damaged one, and one that could not be read. The unencrypted export reports its request and holds a slot of its own, worded about the accounts rather than the vault. The import reports its request and holds a third, worded about the file rather than the vault, since a read that failed opens no preview to carry it; each of the three slots is asserted empty for the others' failures.

**Preference ownership** — the holder publishes a change and writes the whole document, leaving the fields the change did not name; a later change carries the field an earlier one set, which is the single-owner rule stated in §10.1 and the one case a write derived from a launch-time snapshot fails. A refused write reports the failure and leaves the change published, since it costs the next launch the setting rather than this one. The shell's half is crossed with the geometry recorder: a geometry reaching the file carries a theme and an ordering chosen since the window opened, and a preference reaching the file carries the geometry recorded before it.

**HOTP counter** — generating a code persists the incremented counter before returning it; a write failure leaves the stored counter unchanged and yields no code; a counter surviving a lock/unlock cycle; two successive generations produce consecutive counter values and different codes.

**Password change** — the vault opens under the new password and fails under the old; the DEK is unchanged, verified by comparing decrypted body bytes.

**Settings operations** — the session's side of the actions §9.8 drives, read back through the codec rather than off the session. A password change leaves the file opening under the new password and refusing the old, the session unlocked over the same entries under the policy the body carries, and every decoded key lending the bytes its base32 stands for; a wrong current password writes nothing and leaves the published state, the data key and the decoded keys where they were; a change against a locked vault reports `VaultClosed` and writes nothing. A rotation replaces the key the body is encrypted under while the password, the entries and the policy stand, and an entry still yields the published code its counter names, which is a rotation not disturbing the secrets. An entry written after either rewrite reaches the file the new password opens, and the one written after a rotation is sealed under the rotated key: a session holding the vault it had before the rewrite writes a body against a header the file no longer carries, which reverts the change rather than reporting anything. A policy change reaches the file and the published state, a lock scheduled after it waits the grace period the new policy names, and a refused write leaves the stored and the published policy in agreement and reports the write's own error. An export hands back the bytes on disk: the copy opens under the vault's own password, carries an entry added before it, opens under the new password after a change, comes out of a locked session, writes nothing, and reports `NoVaultFile` against no file. The policy fixtures differ from the defaults and from each other in every field, so a policy the session reports from anywhere other than the body it wrote disagrees in every field rather than in none.

Four things on those paths have no test. A lock landing mid-rewrite leaves the file rewritten and the operation reporting `VaultClosed`; two settings operations in flight at once serialise on the session's own lock; an export takes its copy behind that lock, which no assertion can separate from one taken beside it; and the zeroing of the KEK a rewrite derives is asserted in the codec's suite rather than through the session.

**QR round trip** — for every entry configuration in the URI test matrix, encode the entry to an `otpauth://` URI, render it through `QRCodeWriter`, decode the resulting symbol through `MultiFormatReader`, and assert the decoded payload equals the original URI byte for byte. The symbol is drawn to an image at several pixels a module for the decode, since the binarizer averages blocks rather than sampling points.

A URI reaches the encoder percent-encoded and so carries nothing outside ASCII, which leaves the character set the encoder is asked for standing on no entry configuration. It is asserted on the seam instead, over text holding characters outside Latin-1 as well as outside ASCII. Three further cases hold the parameters the symbol is read at: the error correction level the decode reports, a quiet zone two modules deep on every side with the finder pattern immediately inside it, and the symbol version staying at or below 10 for a 160-bit secret with a 64-character label, since larger symbols become hard to scan at the dialog's minimum size. Text past the format's capacity yields no symbol.

**UI harness** — the screens are `commonMain` and their tests are here, because the Compose test rule and the JUnit 4 machinery it comes with are JVM. One suite covers the harness alone: composing a node, reaching it by its text and driving it off-screen, so a source set that has lost the ability to do any of that says so once rather than through every screen at once.

**Password holder and field** — each editing primitive against the `CharArray` behind the field: insertion at the cursor, deletion, replacement of a selection, a paste landing whole, an edit addressing past the end of the text. The array is asserted zeroed after a deletion, a clear, a destroy, a growth into a larger array and a departure from composition; a destroyed holder takes no further character; the copy handed out is independent of the holder; and a rendering of the holder carries no character of the password. The masked field is driven through the coordinates a text field reports, since two runs of mask characters are indistinguishable and the cursor is what says which run changed: an edit with no render behind it is refused, an edit after a clear is refused until the next render, a second edit before the next render is measured against the first, and a render in the other form replaces the base. On screen the field shows one mask character per character typed and none of the characters until the reveal toggle asks for them.

**Theme** — the content colour follows the mode, which is what the background surface sets and what a theme carrying tokens alone would leave black in both. Every token read from the light and the dark mode in one composition, so one the theme fails to switch shows up as two equal readings: surface and text luminance moving in the direction the mode demands, the primary and both countdown colours differing between modes, an expiring countdown distinct from a running one and amber in both, the spacing steps ascending and identical across modes, and the code readout monospace.

**Create vault** — the recovery note on screen before anything is typed and still there once acknowledged; the create button held disabled by an unacknowledged note, a password below the minimum length, a differing confirmation and a running derivation; the strength meter reporting a common password weak and a long four-class one strong while blocking neither; the button and the Done action of a field each enforcing the same three rules and handing over the characters typed; and a message asserted for each of the nine failures a creation reports — a failed write, a path that already holds a vault, another process holding the file, a file gone by the read-back, a vault past the size the writer will produce, a version the reader does not know, a wrong password, a read-back that is damaged, and a lock that overtook the creation — with the wrong-password and damaged-file messages asserted never to stand in for one another. The creation is driven against a file that hands back altered bytes, which is what establishes that it opens the file rather than the buffer it sealed; §12 records the two branches its view admits and its path cannot reach.

**Unlock** — the field holding focus as the screen opens; the button and the Done action each handing over the password and each refusing an empty entry; the button held disabled for the length of a derivation and the Done action refusing a second one behind it; the progress indicator on screen for the derivation and absent otherwise. A message is asserted for each of the eight failures an unlock reports — a failed read, a file that is not there, a version the reader does not know, a failed tag, a structure that does not parse, a secret that does not decode, a wrong password, and a lock that overtook the derivation — with the wrong-password and damaged-file messages kept apart in both directions. The mapping words nothing else: a file past the size ceiling and another process holding the vault belong to a write, and no branch for either reaches this screen to be asserted. A failed attempt leaves the button live, the field taking characters and the next password reaching the caller on both routes, which is the refusal of a lockout in §9.3 as it stands on screen. The subtitle is asserted over each reason it reports, each reason it does not, and a vault not unlocked in this session; a tag every subtitle carries is read once in the positive and three times for absence, so what the absences look for is the tag a reported subtitle has.

### 13.3 Manual verification

Tray behaviour requires manual verification on GNOME (with and without a tray extension), KDE Plasma, Windows 11, and macOS.

The relock triggers of §8.3 are verified on a running window, since the collector reads state a headless test cannot produce: hiding to the tray, minimising, restoring onto a desktop that gives the window no focus, and leaving the window untouched for the idle interval, each against a grace period of 0 and of 30 seconds. What this catches and no unit test can is a window whose composition stops reporting when it leaves the screen, which would leave a hidden window unlocked.

The raise of §10.3 is verified by launching TAuth a second time while the first is running: against a window hidden to the tray, against one minimised, and against one standing behind another window, with the pointer left where it rests each time. The window comes forward in all three and the second launch ends on its own, and a window raised with nobody at the machine locks when its relock falls due. What this catches and no unit test can is a raise the desktop does not carry out and a raise the toolkit reports back as the user's own input.

Cross-platform vault portability is verified by creating a vault on one OS, copying it to the other two, and unlocking with the password on each.

The show-QR dialog is verified by scanning its output with at least three unrelated authenticators — Google Authenticator, Aegis or Raivo, and one desktop scanner — at the dialog's minimum size and on both a light and a dark system theme. Automated round-trip tests confirm the payload; only a real scanner confirms the rendering.

Argon2id timing is measured on the lowest-specification target machine to confirm the parameter choice in §6.5.

The access-control-list branch is verified on Windows, and only there. It is reached where a destination carries no POSIX modes, which on Linux is no ordinary mount: a FAT volume takes that branch and then offers no `AclFileAttributeView` either, so it exercises the refusal beside it rather than the entry being set. Two paths carry it — the vault store of §6.6 and the owner-only write of §9.9 — and both need a filesystem the JDK exposes that view on. A packaged build installed on Windows, with a vault created on an NTFS volume and an export written to one, is what reaches them.

The tray is verified on each desktop for the click its platform names: a single left click on Windows, macOS and KDE Plasma, a double left click on GNOME, and the menu on the secondary click throughout. What no unit test reaches is whether the desktop draws the icon at the size it asked for, which is the defect that took the tray off `java.awt.SystemTray` in the first place.

---

## 14. Milestones

**M1 — OTP core.** `Base32`, `Hmac` expect/actual, `Hotp`, `Totp`, `OtpAuthUri` covering both types, and the full test suite from §13.1. No UI. Deliverable: a green test run covering every RFC 4226 and RFC 6238 vector.

**M2 — Vault format.** `crypto` expect/actual set, `VaultHeader`, `VaultBody`, `VaultEntry`, `SecurityPolicy` as a field of the body, `VaultCodec`, `VaultStore`, `VaultPaths`, `VaultError`, and the tests from §13.1 and §13.2. No UI. Deliverable: create, write, read and round-trip a vault from tests. The store's POSIX branch is exercised here; its access-control-list branch is reached on no ordinary Linux mount and is verified on Windows with the packaged artifacts, as §13.3 states.

**M3a — Shell infrastructure.** `Preferences` and `PreferencesStore`, `ClipboardService`, the single-instance mechanism of §10.3, and tray availability with the fallback §10.2 describes. Deliverable: a preference file that survives a restart, a clipboard that clears only the string it placed, a lock file and loopback listener that answer whether another instance holds the vault, and a correct answer to whether this desktop has a tray. The role a launch takes from that mechanism is claimed by the shell of M4.

**M3 — Session and unlocked UI.** `VaultSession` including `scheduleLock` and `cancelScheduledLock`, `SessionState`, `LockReason`, the code ticker, create-vault screen, unlock screen, account list with live TOTP codes and generate-on-request HOTP rows, the HOTP persist-before-display path, add-account by URI and manual entry, edit including counter, delete, and the secret disclosure gate stated at the head of §9, built as a component rather than at its one call site. Deliverable: a usable authenticator. Delete has no recovery path until export arrives in M4.

**M4 — Tray, lifecycle and settings.** Tray construction and menu, hide-to-tray, the relock triggers of §8.3, grace period, idle timeout, the lifecycle behaviour `SecurityPolicy` governs, the single-instance role taken at startup so that a second launch raises the first window and exits, and the settings screen of §9.8 including change master password, re-encrypt, and encrypted export. The raise half reads the window visibility this milestone introduces, and §10.3 requires it to raise without counting as user presence.

**M4b — Errors narrowed to the operation that reports them.** A signature over the whole of `VaultError` admits every case any operation can produce, so a read admits the cases only a write reports and an operation on an existing entry admits the cases only a new one reports. A screen mapping such a signature carries a branch per case in the hierarchy, of which a handful are reachable; the compiler enforces that a branch exists and nothing holds its sentence to the operation it describes, which is how a message comes to name the wrong file or the wrong verb, and how the branch that would have caught it goes untested because no path reaches it.

Each case declares the operations it belongs to, so a read and a write each get a sealed view over the cases they can produce, and `VaultStore`, `VaultCodec` and `VaultSession` return the view that fits. §4's rule that distinct failures get distinct types, applied to the operation rather than only to the cause. The cases keep their names and their payloads; what changes is which of them a signature admits.

The message mappings of §9 follow: each `when` shrinks to the cases its screen's operation can reach, every branch is then a branch a test can drive, and a sentence naming a read cannot be reached by a write. Deliverable: every mapping admits only what the steps of its own operation report, each branch it carries is asserted, and the branches a view admits that its path cannot reach are the three §12 names and no others.

**M5 — QR, plaintext export, packaging.** ZXing image decode for import and `QRCodeWriter` for the show-QR dialog (§9.7), plaintext export, import with duplicate detection, DMG/MSI/DEB configuration, icons for all three platforms, and verification of the packaged artifacts on each OS.

**M6 — the primary view.** The window a TOTP authenticator is used through is opened many times a day for a few seconds each, and the whole of an interaction is: appear, find one account, put its code on the clipboard, leave. This milestone makes the interface that shape. The window is small, resizable within bounds, minimises and does not maximise, since nothing it draws benefits from a full screen. The account list is compact, offers a density choice, and puts the code where the eye lands.

A face is bundled for all three platforms rather than taken from each, so the application reads the same everywhere: the code keeps the monospace §13.2 asserts, and every other numeral — the counter, the countdown seconds — is set in tabular figures so a changing digit moves nothing beside it. The palette is a flat dark surface under a single accent, with the light scheme drawn from the same tokens.

An account is identified by its issuer and account name and by a generated mark: the initial over a colour derived from the two names. No brand icon is shown and none is fetched. The issuer is a field of an `otpauth://` URI that anything can write, so a real company's mark beside an account would be the application vouching for a string nothing authenticates, and fetching one would tell whoever serves it what accounts the vault holds.

The path from opening the window to a code on the clipboard is the measure: the search field takes focus as the window opens, the arrow keys move between accounts, and the keyboard reaches every action a pointer does — which is also the reordering §9.4 currently offers by drag alone. The countdown states its own expiry in more than colour, and a code changing is announced without interrupting what a screen reader is already saying.

This milestone revises §7's theme rules, §9.1 and §9.4's account list, and §10.1's window. It follows M5 because it redraws every screen the milestones before it complete, and a screen redrawn before its behaviour exists is drawn twice.

M1 and M2 have no dependency on Compose and are fully testable headless. M3a touches neither the session nor the vault, so it is buildable and testable ahead of M3; every relock trigger in M4 resolves to a call on the session M3 builds, and every M5 item is an entry point added to a screen M3 or M4 already has. M4b sits between them because it edits every signature that reports a failure and every mapping that renders one: taken after M5 it would touch the QR dialog, plaintext export and import as well, and taken before M4 it would have no settings screen to narrow.

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
- **`OpenVault` giving up its parsed body** (§8.4) once the session has decoded every secret out of it, so that the base32 text ends at the decode rather than at the lock. The handle carries the DEK and the body together, and the session keeps it for the key, so dropping the body means a handle that answers for one and not the other, and a write path that rebuilds the body from the session's own state. Rebuilding re-encodes each secret from its decoded bytes, which is a different string from the one imported wherever the import carried padding, lowercase or whitespace — §6.4 stores the text as imported so that an export reproduces the original URI.
- **Screen-region QR capture** (§9.5), pending a decision on the macOS Screen Recording prompt and a Wayland portal integration.
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
