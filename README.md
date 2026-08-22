# TAuth

A desktop authenticator for time-based one-time passwords, for Linux, macOS and Windows.

Accounts live in one file, encrypted whole. A copy of that file taken from a disk, a backup or a synced folder is unreadable without the master password, and a changed byte is detected rather than decrypted. The vault is open only while you are looking at it: hiding the window to the tray zeroes the key in memory, and coming back costs one password entry.

Your master password cannot be recovered. There is no reset, no recovery code and no escrow — losing it loses every secret in the vault.

## What it does

- TOTP and HOTP accounts, added by pasting an `otpauth://` URI, reading a QR code out of an image file, or typing the details.
- Codes on one screen, with the keyboard alone: type to search, arrow to a row, Enter copies. Alt with the arrows reorders.
- A copied code leaves the clipboard on a delay you choose.
- Locks on idle, on minimize, on losing focus, or on being hidden to the tray — each configurable, and stored inside the encrypted vault so that editing a config file cannot switch them off.
- Shows an account back as a QR code, behind a password prompt.
- Exports an encrypted copy of the vault, or the accounts unencrypted for moving to another authenticator. Imports either.
- Optionally starts when you log in.

TAuth is offline. It has no account, no sync and no network client.

## Building and running

Gradle runs on JDK 21, pinned in `gradle/gradle-daemon-jvm.properties` and provisioned automatically if it is not already installed.

```bash
./gradlew :desktopApp:run                 # launch it
./gradlew check                           # tests, ktlint and detekt
./gradlew packageDistributionForCurrentOS # native installer for the host OS
```

`packageDistributionForCurrentOS` emits only the host's format: a `.deb` on Linux, a `.dmg` on macOS, an `.msi` on Windows.

## Where the vault lives

| Platform | Path |
|---|---|
| Linux | `$XDG_DATA_HOME/tauth/vault.tauth`, or `~/.local/share/tauth/vault.tauth` |
| macOS | `~/Library/Application Support/TAuth/vault.tauth` |
| Windows | `%APPDATA%\TAuth\vault.tauth` |

The file is created readable by its owner alone. Appearance and tray preferences sit beside it in a plaintext `preferences.json`; nothing governing when the vault locks is kept there.

## Cryptography

AES-256-GCM over the whole body, under a data key wrapped by a key derived from your password with Argon2id (m = 64 MiB, t = 3, p = 1). The header is bound to the body as associated data, so editing it fails the tag rather than redirecting the unwrap. `AGENTS.md` records the parameters and the threat model; changing either is a format version change.

## License

Apache-2.0, in `LICENSE`. The bundled Noto Sans and Noto Sans Mono are under the SIL Open Font License 1.1, in `desktopApp/resources/common/LICENSES-fonts.txt`, which installs beside the application.

## Documents

- `AGENTS.md` — the threat model, the cryptographic parameters, the invariants a change must not break, and the commands.
- `STYLE_GUIDE.md` — Kotlin and Compose conventions, error handling, testing.
- `FUTURE_PLANS.md` — work TAuth does not do, and what is verified nowhere.
- `SECURITY.md` — what to report, where to report it, and the limits that are not defects.
