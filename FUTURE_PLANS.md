# TAuth — Future Plans

Work TAuth does not do. What it does is in the code, the tests, `AGENTS.md` and `STYLE_GUIDE.md`; nothing here describes the built system, and a section that lands moves out of this document rather than being marked done.

---

## 1. Unlock via a platform authenticator

The password-only design requires typing the master password on every unlock, and the lock lifecycle makes unlocks frequent by design — hiding to the tray discards the key. The improvement is an optional second unlock path backed by the operating system's credential store, reached through Touch ID on macOS, Windows Hello or the Windows user session on Windows, and the freedesktop Secret Service keyring on Linux.

### 1.1 Structure

Envelope encryption already separates the KEK from the DEK. The keyring path stores a second copy of the DEK, so either unlock path yields the same key and the body is never re-encrypted when the feature is toggled:

```
                    ┌──────────────────┐
  master password ──┤ Argon2id + salt  ├──► KEK (32 B)
                    └──────────────────┘        │
                                                │ AES-256-GCM unwrap
                                                ▼
  OS keyring entry ─────────────────────────► DEK (32 B) ──► AES-256-GCM ──► vault body
```

The master password remains mandatory. The keyring is a convenience path, never the only one, so a lost keyring entry, a re-enrolled fingerprint or a move to another machine never renders the vault unopenable.

### 1.2 Consequences to state in the UI when this lands

- Enabling keyring storage makes the platform authenticator sufficient to read every secret. The vault's security becomes the weaker of (master password, platform authenticator).
- On Linux, the freedesktop Secret Service default `login` collection is readable by any application running as the same user once the keyring is unlocked (CVE-2018-19358). This is a property of the platform, not of TAuth, and it must be stated at the point where the user enables the feature.
- On Windows without the Hello work in §1.6, and on Linux, no biometric or presence check occurs. The key is released to anyone with the user's logged-in session, so the settings copy must name the actual protection — "Windows user account", "system keyring" — rather than implying a fingerprint check.

### 1.3 File format impact

None. The header already carries `vaultId` for exactly this purpose. Enabling the feature adds one object to the header:

```json
"keyring": {
  "enabled": true,
  "service": "com.panda.tauth",
  "account": "vault-dek"
}
```

Header deserialisation already tolerates unknown keys, so a vault written with this block opens unchanged in a build that predates the feature. The keyring entry's account name incorporates `vaultId`, so two vaults on one machine never collide and a stale entry from a deleted vault is never applied to a new one.

### 1.4 Interface

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

`promptsForUserPresence` drives the UI wording required by §1.2. A `SecretStoreFactory` dispatches on `System.getProperty("os.name")` and probes availability once at startup. All store calls run off the UI thread and carry no timeout, since a call may legitimately block on a user-facing prompt.

New error cases: `KeyringUnavailable`, `KeyringEntryMissing`, and `KeyringCancelled`. The last is distinguished so that dismissing a Touch ID prompt returns silently to the password field instead of showing an error banner.

New operations: enable (requires an unlocked session; writes the DEK, then sets the header flag, so a failed write leaves the header untouched), disable (deletes the entry, clears the flag, warns if deletion failed), and repair (re-writes the entry when it is found missing at unlock time). Changing the master password does not touch the keyring entry, since the DEK is unchanged. Rotating the DEK must rewrite it.

New dependencies: `net.java.dev.jna:jna` and `jna-platform` (5.19.1 current), and on Linux `de.swiesend:secret-service` (3.0.0-beta, requires JDK 17+, pulls in `dbus-java`). The Linux dependency must be loaded reflectively or guarded by an OS check so that a missing D-Bus session on macOS or Windows never causes class initialisation failures. Packaging must then retain `jdk.unsupported`, which JNA requires on some paths.

### 1.5 Per-platform implementation

**Linux — freedesktop Secret Service.** `de.swiesend:secret-service` over D-Bus, reaching gnome-keyring, KWallet's Secret Service bridge, or KeePassXC's built-in provider. Availability probe: the session bus is reachable and `org.freedesktop.secrets` is a registered name; a headless session or missing `DBUS_SESSION_BUS_ADDRESS` yields unavailable. The secret is written to the default collection with attributes `{"application": "tauth", "vault": "<vaultId>"}`. `promptsForUserPresence = false`. KWallet support in the library is documented upstream as best-effort, so a failure on KDE must degrade to the password path rather than block startup. Storing in a non-default, always-locked collection is a hardening option that trades away the convenience the feature exists to provide, so it belongs behind a setting rather than as the default.

**macOS — Keychain with Touch ID.** Two tiers selected at runtime.

*Tier 1, biometric.* JNA bindings to `Security.framework`: `SecAccessControlCreateWithFlags(kCFAllocatorDefault, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlBiometryCurrentSet or kSecAccessControlOr or kSecAccessControlDevicePasscode, &error)`, then `SecItemAdd` / `SecItemCopyMatching` with `kSecClass = kSecClassGenericPassword`, `kSecAttrService`, `kSecAttrAccount`, `kSecAttrAccessControl`, and `kSecUseDataProtectionKeychain = true`. The data protection flag is mandatory: the legacy file-based keychain does not support `kSecAttrAccessControl`, and omitting it produces `errSecParam` or `-34018`. `kSecAccessControlBiometryCurrentSet` invalidates the entry when the enrolled fingerprint set changes; combining it with `kSecAccessControlDevicePasscode` via `kSecAccessControlOr` provides a passcode fallback so re-enrolment does not silently destroy the saved key.

*Tier 2, non-biometric.* The `security` command-line tool (`add-generic-password`, `find-generic-password -w`, `delete-generic-password`) against the login keychain. No Touch ID support exists on this path; access is governed by the login keychain's unlock state and the standard allow/always-allow ACL dialog. `promptsForUserPresence = false`.

The availability probe attempts tier 1 with a throwaway item and falls back to tier 2. **Tier 1 requires a code-signed application with a keychain-access-group entitlement, and therefore an Apple Developer account.** Without one, macOS users get tier 2 and no biometric prompt. This is the single largest external prerequisite for the feature and determines whether "Touch ID support" is deliverable at all.

CoreFoundation interop through JNA is the main implementation cost: `CFDictionaryCreate`, `CFStringCreateWithCString`, `CFDataCreate`, and disciplined `CFRelease` on every created reference. It is the largest native-interop surface the project would take on and warrants isolation behind a single file with its own tests.

**Windows — Credential Manager and DPAPI.** `Advapi32.CredWriteW` / `CredReadW` / `CredDeleteW` through `jna-platform`, which ships `CREDENTIAL` structure bindings. Written with `Type = CRED_TYPE_GENERIC`, `Persist = CRED_PERSIST_LOCAL_MACHINE`, `TargetName = "com.panda.tauth:vault-dek:<vaultId>"`. The DEK is additionally passed through `Crypt32.CryptProtectData` with `CRYPTPROTECT_UI_FORBIDDEN` and an entropy blob derived from the vault id, so a credential blob extracted on another machine or under another account is useless. `promptsForUserPresence = false`.

### 1.6 Windows Hello

`KeyCredentialManager` lives in `Windows.Security.Credentials`, a WinRT namespace with no supported JVM binding. Reaching it requires shipping a C++/WinRT helper DLL invoked over JNA or JNI, and a documented defect places the Hello prompt behind the calling window in JVM-hosted applications, requiring an explicit foreground-window handoff. This introduces a native build toolchain and a second signed binary to the distribution, so it is separate work from §1.5's Windows implementation and does not gate it.

### 1.7 Sequencing

Linux first: it is the development platform, requires no code signing, and exercises the whole `SecretStore` abstraction end to end. macOS tier 2, then tier 1 once a signing identity exists. Windows Credential Manager with DPAPI. Windows Hello last, if at all. The three platform implementations are mutually independent and can proceed in any order once the interface and the session-level unlock path exist.

Keyring behaviour cannot be meaningfully unit-tested; it depends on a live platform store and on user interaction. A written manual checklist covers, per OS: enable, relaunch, unlock via the store, disable, confirm the entry is gone from the platform's own credential UI, delete the entry externally and confirm graceful fallback, and confirm behaviour with the store locked or unavailable.

---

## 2. Other deferred items

- **Rollback detection.** Every vault TAuth writes stays authentic, so an older copy put back in place opens normally. Telling the current file from a past one needs a counter held where whoever can rewrite the vault cannot reach it, which on a single machine does not exist — a plaintext sidecar is rewritten in the same motion as the vault. It waits for a remote endpoint that can hold the counter.
- **`OpenVault` giving up its parsed body** once the session has decoded every secret out of it, so the base32 text ends at the decode rather than at the lock. The handle carries the DEK and the body together, and the session keeps it for the key, so dropping the body means a handle that answers for one and not the other, and a write path that rebuilds the body from the session's own state. Rebuilding re-encodes each secret from its decoded bytes, which is a different string from the one imported wherever the import carried padding, lowercase or whitespace — the body stores the text as imported so that an export reproduces the original URI.
- **The Windows Startup-tab divergence.** Windows keeps the enabled state of a startup entry separately from the entry, under `HKCU\…\Explorer\StartupApproved\Run`. Disabling TAuth in Task Manager's Startup tab leaves the `Run` value in place and untouched, so the setting reads as on while nothing launches. Reflecting it means reading an undocumented binary format, and writing it would override a choice the user made in the platform's own interface; what a fix should do is report the divergence, not resolve it.
- **A `--vault <path>` argument** overriding the resolved location, for a vault kept on removable media and for exercising the application against a scratch file. `main` takes no arguments, so every path comes from `VaultPaths`. Both the single-instance lock and the preferences file are resolved from the same directory, so an override has to move all three together or a second instance will take the lock of the first.
- **Screen-region QR capture.** It requires `java.awt.Robot` screen capture permission, which on macOS triggers a Screen Recording privacy prompt and on Wayland needs a portal integration.
- **System accent-colour following on Windows.**

---

## 3. Verification not yet reached

### 3.1 Verified nowhere

Stated rather than left to be discovered:

- **Anything visual.** Compose's test APIs read the semantics tree, so an unpainted background and an unrenderable field populate it identically. §3.2 and a person looking are the only verification of a rendering. The settings groups' surfaces and the unlock screen's centring are covered by nothing else.
- **The two access-control-list branches**, in `VaultStore` and `OwnerOnlyFile`. Reached only where a filesystem exposes `AclFileAttributeView`, which on Linux is no ordinary mount: a FAT volume takes that branch and then offers no view either, so it exercises the refusal beside it rather than the entry being set.
- **DMG and MSI.** jpackage emits only the host format.
- **The Windows login item.** The record is a registry value written through `reg.exe`, which no other host has; what the tests reach is the argv it is given and the reading of what `reg query` prints. The two file-backed records are exercised against a temp directory on any host.
- **Four session paths**: a lock landing mid-rewrite, two settings operations in flight at once, an export taking its copy behind the session lock, and the zeroing of the KEK a rewrite derives.
- **Two routing fallbacks**: leaving the edit destination when the account it named is deleted underneath it, and the import wiring crossed with routing.
- **`SettingsWork` zeroing the password arrays it was handed.**
- **The zeroing inside the password check and the preview code**, which is not observable from outside the codec.

### 3.2 Manual verification

Tray behaviour on GNOME (with and without a tray extension), KDE Plasma, Windows 11 and macOS: the click each platform names — a single left click on Windows, macOS and KDE Plasma, a double on GNOME — the menu on the secondary click throughout, and whether the desktop draws the icon at the size it asked for.

The relock triggers on a running window, since the collector reads state a headless test cannot produce: hiding to the tray, minimising, restoring onto a desktop that gives the window no focus, and leaving the window untouched for the idle interval, each against a grace period of 0 and of 30 seconds. What this catches is a window whose composition stops reporting when it leaves the screen, which would leave a hidden window unlocked.

The raise by launching TAuth a second time while the first runs: against a window hidden to the tray, one minimised, and one standing behind another, with the pointer left where it rests each time. The window comes forward in all three, the second launch ends on its own, and a window raised with nobody at the machine locks when its relock falls due.

Starting at login, per OS, from a packaged install — the only build where the setting is offered at all: turn it on, log out and back in, confirm TAuth is running and its window is not on screen; move the installation and confirm the next launch rewrites the record; turn it off and confirm the record is gone from the platform's own list.

Cross-platform vault portability: create a vault on one OS, copy it to the other two, and unlock with the password on each.

The show-QR dialog scanned with at least three unrelated authenticators — Google Authenticator, Aegis or Raivo, and one desktop scanner — at the dialog's minimum size and on both a light and a dark system theme. Automated round-trip tests confirm the payload; only a real scanner confirms the rendering.

Argon2id timing on the lowest-specification target machine, confirming the parameter choice `AGENTS.md` records.

The access-control-list branch on Windows, and only there: a packaged build installed with a vault created on an NTFS volume and an export written to one.

---

## 4. References for the work above

- [swiesend/secret-service — Secret Service API for Java](https://github.com/swiesend/secret-service)
- [Apple — `SecAccessControlCreateWithFlags` and data protection keychain discussion](https://developer.apple.com/forums/thread/721649)
- [Implementing Windows Hello from Java — KeyCredentialManager obstacles](https://blog.purejava.org/posts/KeyCredentialManager/)
- [Windows Hello — Microsoft Learn](https://learn.microsoft.com/en-us/windows/apps/develop/security/windows-hello)
- [Azure SDK for Java — `WindowsCredentialApi` JNA bindings reference](https://azuresdkartifacts.blob.core.windows.net/azure-sdk-for-java/test-coverage/azure-identity/com.azure.identity.implementation/WindowsCredentialApi.java.html)
