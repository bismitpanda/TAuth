# AGENTS.md

## Project

TAuth is a desktop TOTP authenticator: Kotlin Multiplatform + Compose Multiplatform, targeting Linux, macOS and Windows. Accounts live in a single file encrypted end to end with a key derived from a master password. The vault is unlocked only while the window is on screen; hiding it to the tray zeroes the key.

`IMPLEMENTATION_PLAN.md` is the specification. It defines the file format byte by byte, the crypto parameters, the lock lifecycle and the UI. **Read the relevant section before writing code in that area, and follow it.** If the plan is wrong, say so and get it changed — do not silently diverge.

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

Bare `./gradlew detekt` is misleading in `:shared`: the aggregate task is NO-SOURCE in a KMP module because the analysable tasks are per-source-set. `check` wires up the right ones — use it, or name a source-set task directly.

detekt runs **without type resolution**, so rules that need type information are skipped rather than run, and skipped without a message. That is deliberate: [detekt/detekt#9602](https://github.com/detekt/detekt/issues/9602) makes type-aware analysis misfire on KMP `expect`/`actual`. Type checking is the compiler's job and is unaffected. Dead-code detection is not: `UnusedPrivateProperty`, `UnusedPrivateFunction` and `style>UnusedImport` need type information, so an unused private property or private function fails no task. A dead import is failed by ktlint's `no-unused-imports` instead (@STYLE_GUIDE.md §1). Do not switch type resolution on to chase a finding; see `build.gradle.kts`.

Prefer `:shared:jvmTest` over `build` while iterating. The vault format and OTP core are fully testable headless and most work needs no UI run.

## Style

**Follow @STYLE_GUIDE.md.** It covers formatting, naming, source-set placement, error handling, coroutines, Compose, comments, security-critical code, testing and Gradle.

The points most often got wrong here:

- **Errors are return values.** `VaultError` is a sealed interface, never thrown, never an `Exception` subclass. Fallible operations return `Outcome<T, VaultError>`. Exceptions from the JDK are caught where they arise and converted at once.
- **Comments are minimal** — but every security invariant gets one. Nonce freshness, AAD binding, key zeroing and the no-`String`-for-secrets rule are not self-evident and break silently.
- **Trailing commas, 120 columns, Kotlin official style.** Run `formatKotlin` rather than hand-formatting.

## Writing

Applies to `IMPLEMENTATION_PLAN.md`, `STYLE_GUIDE.md`, this file, code comments and KDoc.

**Present state.** Describe what the system is, not how it came to be. A reader with no knowledge of the project's history must not be able to tell which parts were written first, changed, or reconsidered. Edits leave no trace of the edit.

**No changelog framing.** Cut "now", "previously", "used to", "instead of", "moved to", "retained", "no longer", "as before" and any sentence whose subject is a decision rather than the system. Write "the counter is stored per entry", not "the counter is now stored per entry". Removed material is deleted, not annotated as removed. Deferred material is stated as deferred in its own section, not narrated as having been dropped.

**No defensiveness.** State the decision and its reason once. Do not pre-empt objections, argue against alternatives nobody proposed, hedge a factual claim, or explain why an earlier option was worse. A tradeoff worth recording is recorded as a tradeoff, in one sentence, without apology.

**Exceptions.** Commit messages describe change by nature — write them in the imperative about what the commit does. Comments recording an upstream defect name the tracker and the observable symptom, because that is present state, not history.

## Non-negotiable invariants

Breaking one of these is a security defect, not a style problem. They are stated in full in @STYLE_GUIDE.md §9 and specified in `IMPLEMENTATION_PLAN.md` §6.5 and §8.4.

- A fresh 12-byte nonce on **every** vault write. Nonces are generated inside the codec and never accepted as a parameter.
- Header bytes read from disk are reused verbatim as AEAD associated data, never re-serialised.
- Key material and decoded secrets are `ByteArray`/`SecureBytes`, never `String`. The master password is `CharArray`.
- The KEK is zeroed immediately after unwrapping the DEK; the DEK is zeroed on lock, on every path including errors.
- `SecureRandom` only. `kotlin.random.Random` does not appear in `crypto/` or `vault/`.
- Secrets are never logged, never in an exception message, never in a `toString()`.

Changes under `crypto/` or `vault/` need a test that fails before and passes after.

## Testing

Backticked behavioural test names. One subject per test. Spec vectors as individual cases so a failure names the vector. No mocking framework — constructor-injected interfaces with hand-written fakes, a fixed `Clock`, a temp directory.

Tests never touch the real vault path. Tests are deterministic: no sleeps, no wall-clock reliance, no unseeded randomness.

The RFC 6238 Appendix B vectors in `IMPLEMENTATION_PLAN.md` §5.4 use **a different seed per algorithm** (20/32/64 bytes) — the RFC's own prose says otherwise and is wrong; see errata 2866. `T` is 64-bit big-endian, and the `20000000000` vector exists to catch a 32-bit implementation.

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
- Do not add keyring, Touch ID, Windows Hello or Secret Service code. That work is deferred and specified in `IMPLEMENTATION_PLAN.md` §16.
- Do not restructure modules or introduce an architecture layer (DI framework, navigation library, MVI framework) without asking. The plan's structure is deliberate.
- Ask before deleting or rewriting a file you did not create in this session.
