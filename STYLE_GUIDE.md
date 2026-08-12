# TAuth — Style Guide

Conventions for Kotlin and Compose Multiplatform code in this repository. Where this document is silent, follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

---

## 1. Formatting

Kotlin official style, enforced by ktlint rather than by review:

- 4-space indent, no tabs.
- 120-column limit.
- Opening brace at the end of the line that opens the construct; closing brace on its own line, aligned with the construct.
- Trailing commas on multi-line parameter lists, argument lists, and collection literals. They keep diffs to one line when a parameter is added.
- No semicolons. No redundant `: Unit`. No braces around a simple string-template variable (`"$name"`, not `"${name}"`).
- Expression bodies for single-expression functions: `fun code(): String = format(digits)`.

IntelliJ's **Kotlin style guide** preset matches this. Set it once under Settings → Editor → Code Style → Kotlin → "Set from… → Kotlin style guide".

ktlint runs through the kotlinter Gradle plugin and takes its rules from `.editorconfig`, so the IDE and the build read the same file. `./gradlew formatKotlin` fixes what is mechanically fixable; `./gradlew lintKotlin` reports the rest. Do not hand-format around ktlint; if a rule is wrong for this project, change it in `.editorconfig` and say why in the commit.

Static analysis is detekt, configured in `config/detekt/detekt.yml` on top of its default ruleset. detekt's formatting ruleset is deliberately absent so the two tools cannot disagree about the same line: kotlinter owns formatting, detekt owns code smells. It runs without type resolution while [detekt/detekt#9602](https://github.com/detekt/detekt/issues/9602) is open, so type-aware rules are skipped; the Kotlin compiler remains the type checker.

## 2. Naming

Standard Kotlin conventions apply. The project-specific points:

- **Files.** One top-level class per file, named for it. Files holding several top-level declarations get a descriptive name — `VaultPaths.kt`, not `VaultUtils.kt`. `Util`, `Helper`, `Manager` and `Common` are not acceptable file or class names; if no better name exists, the file is doing more than one thing.
- **Platform files.** Files with top-level declarations in a platform source set carry the source-set suffix: `Aead.kt` in `commonMain`, `Aead.jvm.kt` in `jvmMain`. This is a Kotlin convention, not a preference.
- **Acronyms.** Two letters uppercase (`IOStream`); three or more capitalise the first only (`TotpGenerator`, `QrEncoder`, `OtpAuthUri`, `HttpClient`). `TOTP`, `QR` and `URI` do not appear in identifiers in those forms.
- **Backing properties.** `private val _entries` exposed as `val entries: List<VaultEntry> get() = _entries`. Used for every mutable collection or flow exposed read-only.
- **Constants.** `const val` in screaming snake case, declared in a `companion object` or at file top level, never inline as a magic number. Cryptographic sizes in particular are named: `NONCE_BYTES = 12`, `TAG_BITS = 128`, `DEK_BYTES = 32`.
- **Booleans.** Prefixed `is`, `has`, `should`, or `can`. `isLocked`, not `locked`.

## 3. Source sets and `expect`/`actual`

The rule for where code goes, in order:

1. If it is pure logic with no platform API, it goes in `commonMain`. This is the default and most code lands here.
2. If it needs a platform API, define an `expect` declaration in `commonMain` and the `actual` in `jvmMain`. The `expect` declaration carries the KDoc if any; the `actual` does not repeat it.
3. Only code that is inherently the desktop application shell — window, tray, single-instance, AWT clipboard, file dialogs — belongs in `:desktopApp`.

`expect`/`actual` is for platform primitives (AEAD, KDF, HMAC, CSPRNG, base64, filesystem), not for feature switching. An `expect` declaration with one `actual` that differs by behaviour rather than by platform capability is an interface in disguise; use an interface.

Keep `commonMain` free of `java.*` imports. The `jvm()` target makes them compile, and that is exactly why the discipline has to be deliberate: an accidental `java.util.Base64` in `commonMain` compiles today and blocks an Android or iOS target later.

## 4. Error handling

Failure is a return value, not a thrown exception.

```kotlin
sealed interface VaultError {
    data object WrongPassword : VaultError
    data object IntegrityFailure : VaultError
    data class Corrupt(val detail: String) : VaultError
    data class Io(val cause: Throwable) : VaultError
}
```

`VaultError` is a **sealed interface, not an `Exception` subclass**. It is never thrown.

- Every fallible operation returns `Outcome<T, E>`. `kotlin.Result` cannot carry a non-`Throwable` error, which `VaultError` deliberately is.
- The compiler forces the UI to handle each case: a `when` over a sealed hierarchy with no `else` branch fails to compile when a case is added. Adding an error case must break the build at every site that maps errors to messages.
- Exceptions are caught at the boundary where they originate — JDK and third-party APIs throw — and converted immediately into a `VaultError`. `IOException` never propagates past `VaultStore`; `GeneralSecurityException` never propagates past the `crypto` package.
- `Result.getOrThrow()` and `!!` do not appear outside tests.
- A caught exception is never discarded. It goes into `VaultError.Io(cause)` or is logged with its stack trace.

Distinct failures get distinct types. `WrongPassword` and `IntegrityFailure` are separate cases because they mean different things to the user, and collapsing them into one error would let the UI tell someone to retype their password when the real problem is a damaged file.

## 5. Nullability and immutability

- `val` by default. `var` requires a reason that is evident from the surrounding code.
- Immutable collection types in signatures: `List`, `Set`, `Map`. `MutableList` appears in local scope and behind backing properties, never in a public parameter or return type.
- Prefer absent over nullable: an empty list, not a nullable list. Nullability should mean "this genuinely has no value", not "not loaded yet" — model the latter with a sealed state type.
- `?:` for defaults, `?.let` for scoped work on a nullable. Nested `?.let` chains more than two deep are a sign the nullability should have been resolved earlier.
- `lateinit` is not used. It converts a compile-time guarantee into a runtime crash.

## 6. Coroutines

- Suspend functions do not decide their own dispatcher at the call site; they use `withContext` internally so callers can call them from anywhere. Argon2id derivation and all file I/O wrap themselves in `withContext(Dispatchers.Default)` or `Dispatchers.IO`.
- No `GlobalScope`. Every coroutine belongs to a scope with a defined lifetime — the application scope, or a `CoroutineScope` owned by `VaultSession` and cancelled on lock.
- Cancellation is cooperative and must be preserved: never catch `CancellationException`, and never swallow it inside a broad `catch (e: Exception)`. Use `runCatching` only where the block cannot suspend, or rethrow `CancellationException` explicitly.
- State is exposed as `StateFlow`, never as a mutable flow. `private val _state = MutableStateFlow(...)` with `val state: StateFlow<T> = _state.asStateFlow()`.
- Timers are coroutines with `delay`, not `java.util.Timer` or `ScheduledExecutorService`, so that scope cancellation stops them deterministically.

## 7. Compose

- Composables are `@Composable fun PascalCase()` returning `Unit`.
- Every composable that draws takes `modifier: Modifier = Modifier` as its first optional parameter, applied to its outermost layout node and nowhere else.
- State hoisting: composables take state and emit events (`onCopyClick: () -> Unit`), and do not reach into the session or the repository themselves. Screen-level composables wire the two together.
- No business logic in composables. Code generation, formatting and validation live in `:shared` outside `ui/` and are called from there.
- `remember` for values that are expensive and derived; `derivedStateOf` when a computed value depends on other state and would otherwise recompose too often.
- Never launch work in composition. Use `LaunchedEffect` with a key that genuinely identifies the work.
- Colours, spacing and typography come from the theme. No hardcoded `Color(0xFF...)` or raw `.dp` spacing constants in screen code; add a theme token instead. The exception is §9.7's QR dialog, which is required to be dark-on-light regardless of theme and says so in a comment.

## 8. Comments and documentation

Comments are the exception, not the norm. Names, types and structure carry the meaning.

- No KDoc requirement, including on public declarations. A function whose name and signature explain it gets no doc comment.
- Write a comment when the code cannot say it itself: a non-obvious invariant, an ordering requirement, a workaround for a platform defect, or a reason that would otherwise be lost.
- **Comment every security-critical invariant.** These are not self-evident from the code and breaking one is silent:
  - why a nonce is generated fresh on every write,
  - why the header bytes read from disk are reused verbatim as AAD instead of re-serialised,
  - why the KEK is zeroed immediately after unwrapping,
  - why a secret is held as `ByteArray` and never converted to `String`.
- Cite the external spec where code implements one: `// RFC 4226 §5.3`, `// RFC 6238 errata 8672: T is 64-bit`. Cite only documents that are immutable and independently hosted, so the pointer stays true.
- A comment stands on its own. A citation is a footnote to a statement, never the statement itself; a comment whose whole content is a cross-reference says nothing once the target moves.
- Never restate the code (`// increment the counter`). Never leave commented-out code; git has it.
- `TODO(...)` must name what unblocks it. A bare `// TODO` is noise.

## 9. Security-critical code

These rules override convenience and are not subject to local judgment.

- Key material and decoded secrets are `ByteArray` or `SecureBytes`, never `String`. The master password is `CharArray` from the text field to the KDF call.
- `SecureBytes.destroy()` zeroes and invalidates; every code path that finishes with key material calls it, including error paths. Use `use { }` where the scope allows.
- Nonces are generated inside the codec, never passed in. Any API that accepts a nonce as a parameter is a bug waiting to reuse one.
- `SecureRandom` is the only randomness source for salts, nonces, keys and identifiers. `kotlin.random.Random` does not appear in the `crypto` or `vault` packages.
- Secrets are never logged, never interpolated into an exception message, and never placed in a `toString()`. Data classes carrying secret material override `toString()` to redact.
- No comparison of secret-derived bytes with `==` or `contentEquals` where timing matters; use a constant-time comparison.
- Changes to `crypto/` or `vault/` need a test that fails before the change and passes after, or they do not go in.

## 10. Testing

- Test functions use backticked names describing behaviour: ``@Test fun `wrong password fails at unwrap, not body decryption`() { }``.
- One assertion subject per test. A test that needs "and" in its name is two tests.
- Spec vectors are individual test cases, not a loop over a table, so a failure names the exact vector rather than an index.
- No mocking framework. Dependencies are constructor-injected interfaces with hand-written fakes; a fixed `Clock` for time, a temp directory for the filesystem.
- Tests do not read or write outside a temp directory, and do not touch the real vault path.
- Tests are deterministic. No `Random` without a fixed seed, no reliance on wall-clock time, no sleeps — advance an injected clock instead.

## 11. Gradle

- Every dependency and version goes through `gradle/libs.versions.toml`. No inline `"group:artifact:version"` strings in a build script.
- Version catalog aliases use dashes, referenced in Kotlin with dots: `libs.kotlinx.serialization.json`.
- `implementation` by default; `api` only when a type genuinely appears in `:shared`'s public signatures.
- Build scripts are Kotlin DSL and stay declarative. Logic that grows past a few lines becomes a convention plugin in `buildSrc`.
