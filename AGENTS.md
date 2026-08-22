# AGENTS.md

## Project

TAuth is a desktop TOTP authenticator: Kotlin Multiplatform + Compose Multiplatform, targeting Linux, macOS and Windows. Accounts live in a single file encrypted end to end with a key derived from a master password. The vault is unlocked only while the window is on screen; hiding it to the tray zeroes the key.

The built system is described by its code and its tests. This file and @STYLE_GUIDE.md state what constrains a change to it; `FUTURE_PLANS.md` holds work TAuth does not do. Nothing else is a specification, so **the tests are the behavioral record**: a behavior worth keeping has a test naming it, and changing one means changing the test that states it, deliberately and in the same commit.

## Layout

| Path | Contents |
|---|---|
| `shared/src/commonMain` | OTP core, vault format, entry model, security policy, crypto declarations, Compose UI. Most code. |
| `shared/src/jvmMain` | Platform code: the `actual` crypto primitives, the vault store, path resolution. |
| `desktopApp/src/main` | Application shell: the window. |

Where new code goes, in order: pure logic → `commonMain`; needs a platform API → `expect` in `commonMain` + `actual` in `jvmMain`; is inherently the desktop shell → `:desktopApp`. Keep `java.*` imports out of `commonMain` — the `jvm()` target makes them compile, which is why the discipline has to be deliberate.

## Commands

```bash
./gradlew build                           # compile everything
./gradlew :shared:jvmTest                 # unit tests — the fast loop, use this
./gradlew :desktopApp:run                 # launch the app
./gradlew lintKotlin                      # ktlint, via the kotlinter plugin
./gradlew formatKotlin                    # auto-fix formatting
./gradlew :shared:detektCommonMainSourceSet  # detekt on one source set
./gradlew check                           # tests + lintKotlin + detekt
./gradlew packageDistributionForCurrentOS # native installer
```

The lint tasks are `lintKotlin`/`formatKotlin`, not `ktlintCheck`/`ktlintFormat` — this project uses the kotlinter plugin, which is configuration-cache clean on Gradle 9. Run `formatKotlin` rather than hand-formatting.

Bare `./gradlew detekt` is misleading in `:shared`: the aggregate task is NO-SOURCE in a KMP module because the analyzable tasks are per-source-set. `check` wires up the right ones — use it, or name a source-set task directly.

detekt runs **without type resolution**, so rules that need type information are skipped rather than run, and skipped without a message. That is deliberate: [detekt/detekt#9602](https://github.com/detekt/detekt/issues/9602) makes type-aware analysis misfire on KMP `expect`/`actual`. Type checking is the compiler's job and is unaffected. Dead-code detection is not: `UnusedPrivateProperty`, `UnusedPrivateFunction` and `style>UnusedImport` need type information, so an unused private property or private function fails no task. An import whose name appears nowhere else is failed by ktlint's `no-unused-imports` instead (@STYLE_GUIDE.md, formatting); an import of a symbol from the file's own package is not, since the name is used and only the import is redundant, so nothing in `check` reports one. Do not switch type resolution on to chase a finding; see `build.gradle.kts`.

Prefer `:shared:jvmTest` over `build` while iterating. The vault format and OTP core are fully testable headless and most work needs no UI run.

## Threat model

What the encryption is for. A change that weakens a row of the first table is a security defect; a row of the second is a limit to state plainly rather than to design around.

| Threat | Defence |
|---|---|
| Vault file copied from disk, backup, or a synced folder | Whole-file AES-256-GCM; key derived with Argon2id |
| Offline brute force of the master password | Argon2id with memory-hard parameters fixed by the format version |
| Tampering with any byte of the vault file, including metadata | GCM authentication tag covers the body; the header is bound as associated data |
| Editing the header to weaken or redirect the unwrap | The CRC fails before a key is derived; a repaired CRC fails the unwrap or the body's tag |
| Another user account on the same machine reading the vault | POSIX mode `0600` / Windows ACL restricted to the owner |
| Shoulder-surfing of an unattended unlocked window | Relock on hide-to-tray, on minimize, and on idle timeout |
| Silent weakening of the lock policy by editing a config file | Lock triggers and timeouts live in the vault body, under the GCM tag |
| Casual recovery of secrets from a memory dump after locking | Key material held in `ByteArray`, zeroed on lock; decoded secrets never converted to `String` |

Not defended against: code execution as the same OS user while the vault is unlocked, since key material is in the process heap by necessity; a vault replaced by an older copy of itself, which stays authentic and so is indistinguishable from the current one; kernel-level keyloggers and screen capture; heap paged to swap, as the JVM exposes no `mlock`; and physical access with the window open.

Two consequences shape the UI. The master password is unrecoverable — no reset, no recovery code, no escrow — and the create screen says so. Whoever can write the vault file can destroy it, and TAuth keeps no spare copy, so the backup path is export, and it is the user's to take.

## Cryptographic parameters

Format version 1. The file records none of these: each reader uses the parameters its version names, so strengthening them is a format version change and a vault written under version 1 keeps opening at version 1's cost.

| Purpose | Algorithm | Parameters |
|---|---|---|
| Password → KEK | Argon2id | version 0x13 (19), m = 65536 KiB (64 MiB), t = 3, p = 1, 16-byte random salt, 32-byte output |
| KEK → DEK wrap | AES-256-GCM | 12-byte random nonce, 128-bit tag, empty AAD |
| DEK → body | AES-256-GCM | 12-byte random nonce, 128-bit tag, AAD = file prefix |
| Random material | `java.security.SecureRandom` | default provider, no seeding |

The Argon2id figures exceed the OWASP minimum and cost about 175 ms on the machine they were taken on: Ubuntu, 20 logical cores, 16 GB RAM, Eclipse Temurin JDK 21, BouncyCastle `Argon2BytesGenerator`, JIT warmed, median of five runs. They scale with core speed and memory bandwidth rather than core count, since p = 1. The budget is set by the lock lifecycle rather than by the unlock screen: hiding to the tray discards the key, so a derivation is paid every time the window comes back, and a low-end laptop running 2–3× slower puts these at 400–500 ms. Re-take the timings before changing the parameters, not after.

The two-level hierarchy — password to KEK to DEK to body — makes a password change an O(1) header rewrite rather than a full re-encryption, and is what the deferred keyring path in `FUTURE_PLANS.md` attaches to. Replacing the DEK is a separate operation, because a password change alone leaves a leaked DEK working.

## Style

**Follow @STYLE_GUIDE.md.** It covers formatting, naming, source-set placement, error handling, coroutines, Compose, comments, security-critical code, testing and Gradle.

The points most often got wrong here:

- **Errors are return values.** `VaultError` is a sealed interface, never thrown, never an `Exception` subclass. Fallible operations return `Outcome<T, E>`, with `E` a sealed interface of its own naming the cases that operation reports — `VaultUnlockError`, `EntryAddError`, `FileWriteError` — which each case implements alongside `VaultError`, so a `when` over one is exhaustive without an `else`. Exceptions from the JDK are caught where they arise and converted at once.
- **Comment the trap, and a rule stated in this file is not one.** The invariants below are declared here; a comment repeating one at the site that obeys it adds nothing. A comment earns its place by stopping the next reader making a wrong change — naming the obvious alternative that is wrong, or a behavior nothing at the call site reveals. Explaining a decision nobody is about to reverse is not that. @STYLE_GUIDE.md's comment rules give the whole of it.
- **Trailing commas, 120 columns, Kotlin official style.** Run `formatKotlin` rather than hand-formatting.

## Writing

Applies to `FUTURE_PLANS.md`, `STYLE_GUIDE.md`, this file, code comments and KDoc.

**Present state.** Describe what the system is, not how it came to be. A reader with no knowledge of the project's history must not be able to tell which parts were written first, changed, or reconsidered. Edits leave no trace of the edit.

**No changelog framing.** Cut "now", "previously", "used to", "instead of", "moved to", "retained", "no longer", "as before" and any sentence whose subject is a decision rather than the system. Write "the counter is stored per entry", not "the counter is now stored per entry". Removed material is deleted, not annotated as removed. Deferred material is stated as deferred in its own section, not narrated as having been dropped.

**No defensiveness.** State the decision and its reason once. Do not pre-empt objections, argue against alternatives nobody proposed, hedge a factual claim, or explain why an earlier option was worse. A tradeoff worth recording is recorded as a tradeoff, in one sentence, without apology.

**Exceptions.** Commit messages describe change by nature — write them in the imperative about what the commit does. Comments recording an upstream defect name the tracker and the observable symptom, because that is present state, not history.

## Non-negotiable invariants

Breaking one of these is a security defect, not a style problem. This is where they are declared, the parameters above fix the numbers, and @STYLE_GUIDE.md's security-critical rules give the coding rules that follow. They are not repeated as comments at the sites that obey them.

- A fresh 12-byte nonce on **every** vault write. Nonces are generated inside the codec and never accepted as a parameter.
- Header bytes read from disk are reused verbatim as AEAD associated data, never re-serialized.
- Key material and decoded secrets are `ByteArray`/`SecureBytes`, never `String`. The master password is `CharArray`.
- The KEK is zeroed immediately after unwrapping the DEK; the DEK is zeroed on lock, on every path including errors.
- `SecureRandom` only. `kotlin.random.Random` does not appear in `crypto/` or `vault/`.
- Secrets are never logged, never in an exception message, never in a `toString()`.

Changes under `crypto/` or `vault/` need a test that fails before and passes after.

## Testing

Backticked behavioral test names. One subject per test. Spec vectors as individual cases so a failure names the vector. No mocking framework — constructor-injected interfaces with handwritten fakes, a fixed `Clock`, a temp directory.

Tests never touch the real vault path. Tests are deterministic: no sleeps, no wall-clock reliance, no unseeded randomness.

The RFC 6238 Appendix B vectors in `TotpTest` use **a different seed per algorithm** (20/32/64 bytes) — the RFC's own prose says otherwise and is wrong; see errata 2866. `T` is 64-bit big-endian, and the `20000000000` vector exists to catch a 32-bit implementation.

Specifications the code implements: [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html) and its [errata](https://errata.rfc-editor.org/rfc6238) (2866, 5132, 8672), [RFC 4226](https://www.rfc-editor.org/rfc/rfc4226), [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648), the [Key Uri Format](https://github.com/google/google-authenticator/wiki/Key-Uri-Format) and [draft-linuxgemini-otpauth-uri](https://datatracker.ietf.org/doc/draft-linuxgemini-otpauth-uri/), the [XDG Base Directory Specification](https://specifications.freedesktop.org/basedir/latest/), and the [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

## Commits and PRs

- Conventional commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `build:`, `chore:`. Scope with the area where it helps: `feat(vault):`, `fix(totp):`.
- Subject in the imperative, under 72 characters. Body explains why when the diff does not.
- Branch from `main` as `feat/<short-name>` or `fix/<short-name>`. Never commit directly to `main`.
- `./gradlew check` passes before a commit is offered.
- Commit or push only when asked.

## Boundaries

- **Never** weaken a crypto parameter, widen an error type, or relax an invariant above to make a test pass. Fix the test or raise the problem.
- **Never** add a dependency without adding it to `gradle/libs.versions.toml` first, and check whether the JDK or an existing dependency already covers it.
- **Never** write a real TOTP secret, master password or vault file into the repository, into a test fixture, or into a log. Test secrets are the RFC's published seeds.
- Do not add keyring, Touch ID, Windows Hello or Secret Service code. That work is deferred and specified in `FUTURE_PLANS.md`.
- Do not restructure modules or introduce an architecture layer (DI framework, navigation library, MVI framework) without asking. The structure is deliberate.
- Ask before deleting or rewriting a file you did not create in this session.
