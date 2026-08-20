# Security

## Reporting a vulnerability

Report privately, before it is public: open a [security advisory](https://github.com/bismitpanda/TAuth/security/advisories/new), or write to **contact@bismitpanda.com**.

Include what you did, what happened, and the version from the About group. A proof of concept helps; a vault file that demonstrates it helps more, provided it holds no account you use.

TAuth is maintained by one person. Expect an acknowledgement within a week and no fixed timetable beyond that. A report that turns out to be real gets credited in the release that fixes it, unless you would rather it did not.

## What is supported

The most recent release. There is no long-term branch and no backporting: a fix goes into the next tag.

## What TAuth defends

The vault is one file, encrypted whole with AES-256-GCM under a key derived from the master password with Argon2id (m = 64 MiB, t = 3, p = 1). The header is bound to the body as associated data, so editing it fails the tag rather than redirecting the unwrap. `AGENTS.md` carries the parameters and the full threat model.

A finding against any of these is a vulnerability:

- Reading vault contents, or any part of an account's secret, without the master password.
- Altering a vault file so that a changed byte is decrypted rather than detected.
- Recovering key material after the vault locks — the key is zeroed on lock, on every path.
- Reaching a secret through a channel that is meant to be gated: the clipboard, the QR dialog, an export, a log line, or an error message.
- Weakening the lock policy from outside the vault. Lock triggers live in the encrypted body precisely so that editing a plaintext file cannot switch them off.
- Deriving a key with parameters weaker than the format version names.

## What TAuth does not defend

These are limits of the design, not defects. A report about one of them is not a vulnerability:

- **Code running as you while the vault is unlocked.** Key material is in the process heap by necessity. A keylogger, a screen capture or a debugger attached to the process all defeat it.
- **A vault replaced by an older copy of itself.** Every file TAuth writes stays authentic, so a rollback is indistinguishable from the current vault. Detecting it needs an anchor a single machine does not have.
- **Heap paged to swap.** The JVM exposes no `mlock`; swap encryption belongs to the operating system.
- **Physical access with the window open and the vault unlocked.**
- **A weak master password.** Argon2id raises the cost of a guess; it does not fix a guessable one.
- **Deletion.** Integrity protection detects tampering. It does not prevent destruction, and TAuth keeps no spare copy — the backup path is export, and it is yours to take.

## Release artifacts are not signed

The `.deb`, `.dmg` and `.msi` on the releases page carry no code signature. macOS Gatekeeper and Windows SmartScreen will say so. Verify what you download against the checksum GitHub shows for the asset, and prefer building from source if that is not enough for you.

## Cryptography

Third-party review is welcome, particularly of the file format and the key hierarchy. The format is defined by `VaultCodec` and its tests rather than by prose. Note that `FUTURE_PLANS.md` records an unbuilt keyring path: it is not in any release, and reports against it are premature.
