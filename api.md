# core/api Review & Pre-Release Remediation Plan

## Context

Boson is preparing a release. `core/api` is the module that holds the common APIs and
core implementations shared by every Boson module, and it is also the public, third-party
facing SDK surface (custom services and clients build against it). Defects, inconsistent
conventions, and weak docs here are expensive: once published they become a compatibility
contract that is hard to change.

This document is a **code-review report** of `core/api`, with concrete findings (file:line),
followed by a **recommended remediation plan** for the release. It is intended to be acted
on after you decide the scope (review-only vs. review + fix).

### Coverage / scope of this review
Deeply read (whole-file): the root package `io.bosonnetwork` (Id, Value, PeerInfo, NodeInfo,
Node, Identity, Result, UserProfile, CryptoContext, NodeConfiguration, Network, LookupOption,
Version, NodeFactory, BosonException/ExpiredException), the `crypto` package (Signature,
CryptoBox, CryptoIdentity, Hash, CryptoException), and sampled `identifier` (DIDURL),
`database` (Filter), and `web` (CwtAuthHandler).
**Not yet deeply reviewed** (recommend a second pass if desired): the rest of `identifier`
(DIDDocument, Card, Credential, VerifiableCredential, Resolver, builders), `cwt` (SignedCwt),
`json`, `utils`, `vertx`, `service`, `metrics`.

---

## P0 — Correctness bugs (fix before release)

1. **`Result.setValue` fall-through** — `Result.java:132-140`. The colon-style `switch` has no
   `break`: setting the IPv4 value falls through and **also overwrites the IPv6 value** (and the
   `IPv6` case has no body effect of its own). `getValue`/`get` use arrow-style `switch` so only
   this writer is wrong. Fix: arrow switch or add `break`.

2. **`NodeInfo` equals/hashCode contract violation** — `NodeInfo.java:217-232`. `hashCode()`
   includes `version`; `equals()` excludes it. Two equal `NodeInfo` (same id+addr, different
   version) get different hash codes → broken behavior in `HashMap`/`HashSet`. Worse, `version`
   is mutable (`setVersion`), so the hash changes after insertion. Fix: make both use the same
   fields (drop `version` from `hashCode`, or include it in `equals`), and reconsider mutability.

3. **`NONCE_BYTES` is a public *mutable* static** — `Value.java:56` and `PeerInfo.java:68`
   (`public static int NONCE_BYTES = 24;`). Any code (including third parties) can reassign it and
   globally break nonce validation/signing across the system. Fix: `public static final int`.

4. **`UserProfile.fromCard` stores the literal string `"null"`** — `UserProfile.java:99-101`.
   `String.valueOf(claims.get(NAME))` returns `"null"` (not a null reference) when a claim is
   absent, contradicting `getName()`/`getAvatar()`/`getBio()` JavaDoc ("or null if not set").
   Fix: read claims as `(String) claims.get(...)` or guard for null.

5. **`PeerInfo.getNodeSignature()` leaks the internal array** — `PeerInfo.java:288-290` (and the
   constructor at `:109` stores `nodeSig` without cloning). Every sibling byte[] getter clones;
   this one returns the live array, so callers can mutate signed state. Fix: clone on the way in
   and out, matching `getSignature()`.

6. **CWT scope splitting treats the delimiter as a regex** — `CwtAuthHandler.java:135`
   (`scope.split(delimiter)`). Default `" "` is fine, but any custom delimiter set via
   `scopeDelimiter(...)` containing regex metacharacters (`.`, `|`, `$`, `(`…) silently changes
   scope parsing → potential scope-enforcement bypass or denial. Fix: `Pattern.quote(delimiter)`
   or split literally.

7. **`CryptoBox.isDestroyed()` always returns `false`** — `CryptoBox.java:587-591`. Violates the
   `Destroyable` contract; callers cannot detect a closed box, risking use-after-close of native
   memory. Fix: track a local `destroyed` boolean and report it.

---

## P1 — Security / crypto concerns (review with a crypto lens before release)

8. **`Signature.derive` folds the KDF context through MD5** — `Signature.java:210-213` and
   `:401-404` (duplicated). The context string is MD5-hashed then folded 16→8 bytes
   (`hashBytes[i] + hashBytes[i+8]`). Two distinct contexts can collide to the same 8-byte KDF
   context and, with the same `subKeyId`, derive the **same** key — undermining domain
   separation. Also fails in FIPS environments (MD5 disabled → wrapped `RuntimeException`).
   Recommend: derive the 8-byte context deterministically without MD5 (e.g., truncate a SHA-256
   or document/justify the construction), and de-duplicate the helper.
   _(Decision: flag only — do not change without crypto-design sign-off; may affect already-derived keys / compatibility.)_

9. **`Hash` exposes MD5 as public API with no warning** — `Hash.java:122-160`; `md5()` JavaDoc
   even says "the current thread's MD5" (inaccurate — it returns a fresh instance). Document MD5
   as non-cryptographic/legacy or remove from the public surface.

10. **Decrypt errors leak libsodium C symbol names** — `CryptoBox.java:537,555,572`
    ("crypto_box_open_easy_afternm: failed", etc.). External developers don't know these. Replace
    with human-readable messages (e.g., "Decryption failed: invalid ciphertext or auth tag").

11. **`CryptoContext` replay protection only blocks the *immediately previous* nonce** —
    `CryptoContext.java:158`. The JavaDoc says "prevent replay attacks"; it only blocks an exact
    repeat of the last nonce. Tighten the wording (and the check-then-set has a TOCTOU race; class
    is documented as not thread-safe for decrypt).

12. **`CryptoContext` is not `AutoCloseable`** — `CryptoContext.java:186` has `close()` but the
    class doesn't implement `AutoCloseable`, so it can't be used in try-with-resources even though
    it owns a native `CryptoBox`. Implement `AutoCloseable` (and ideally `Destroyable`).

13. **Key `bytes()` returns a shared, un-cloned internal array** — `Signature.java:78-83,186-191`,
    `CryptoBox.java:93-98,193-198`, and `Id.bytes()` `Id.java:375`. For private keys this exposes
    mutable secret material. `Id` is documented "immutable and thread-safe" yet `bytes()`
    contradicts it. At minimum, scope `bytes()` to internal use or clone; document the secret
    handling explicitly.

14. **`Filter` validates `column` but not `paramName` / IN-map keys** — `Filter.java:405,430-432`
    interpolate `paramName` and `params.keySet()` straight into the SQL template. If those derive
    from untrusted input, the `#{...}` template can be corrupted. Apply `validateColumn`-style
    checks to parameter names too.

15. **Auth handler echoes internal error text to clients** — `CwtAuthHandler.java:106`
    (`new HttpException(401, err.getMessage())`). Minor info leak; prefer a generic 401 message.

---

## P2 — API design & consistency (improves third-party DX and maintainability)

16. **Null-argument exception convention is inconsistent** — `Id` throws `NullPointerException`,
    while `NodeInfo`/`PeerInfo`/`Value` throw `IllegalArgumentException` for null. Pick one (NPE is
    the JDK convention) and apply across the public types.

17. **`encrypt` parameter name differs across the hierarchy** — `Identity.encrypt(Id receiver,…)`
    vs `Node.encrypt(Id recipient,…)`. Unify on one term.

18. **Lookup return conventions are mixed** — `findNode` → `Result<NodeInfo>`, `findValue` →
    `Value` or `null`, `findPeer` → `PeerInfo`/`null`/`List`. Document the null-vs-wrapper rules,
    or unify. External developers will trip on this.

19. **`Value.of(Id,byte[])` (immutable) skips validation** — `Value.java:177` bypasses the
    null/empty-data checks the other `of(...)` factories enforce, allowing a silently-invalid
    Value. It also collides conceptually with the `publicKey`-first overloads (overload ambiguity).
    Add validation and/or rename for clarity.

20. **`Id.bytes()` public** — see #13; reconsider exposure given the immutability contract. Note
    `Id.ThreeWayComparator` is `@Deprecated` (`Id.java:87`) with no `@deprecated` tag or stated
    replacement.

21. **`NodeInfo.matches()` doc says "identical" but logic is OR** — `NodeInfo.java:210-215` returns
    true if id **or** address matches. Fix the JavaDoc to describe the partial-match semantics.

22. **`CwtAuthHandler.scopeDelimiter` mutates shared state** — `CwtAuthHandler.java:206-210`
    mutates `this.delimiter` and returns `this`, while `withScope`/`withScopes` are copy-on-write.
    A handler is typically registered once and shared across requests, so this is also a
    thread-safety hazard. Make it copy-on-write too (and `delimiter` final).

23. **`CryptoIdentity` (and `Signature.KeyPair`/`CryptoBox.KeyPair`) are not `Destroyable`** — no
    way to wipe held secret material. Consider implementing `Destroyable`.

24. **Minor visibility/encapsulation**: `Value.Builder.recipient` is package-private while peers
    are private (`Value.java:624`); builder `data(...)`/`extra(...)` store caller arrays without
    a defensive copy until `build()`.

---

## P3 — Documentation, headers, and code quality

25. **10 source files are missing the MIT license header** (every other file has it). Add headers:
    `crypto/CachedCryptoIdentity.java`, `crypto/PasswordHash.java`, `crypto/Hash.java`,
    `crypto/Random.java`, `crypto/CryptoIdentity.java`, `crypto/CryptoException.java`,
    `vertx/ObservableReadStream.java`, `identifier/VouchBuilder.java`,
    `identifier/VerifiablePresentationBuilder.java`, `identifier/VerifiablePresentation.java`.

26. **Dead commented-out code** — `CryptoIdentity.java:135-138,163-166` (old replay-protection
    snippets). Remove before release.

27. **`Id.hashCode` operator precedence is almost certainly unintended** — `Id.java:733`. Because
    `+` binds tighter than `|`, the seed `0x6030A` only adds to the first shifted term, then the
    rest is OR-combined: `(0x6030A + (a<<24)) | (b<<16) | …`. Deterministic (so not a contract
    violation) but not the intended `seed + combined`. Also the `if (hashCode == 0)` cache sentinel
    recomputes whenever the hash legitimately equals 0.

28. **JavaDoc/code mismatches & vague docs**:
    - `NodeInfo.java:32` typo "THis class represent…".
    - `NodeConfiguration.databaseUri()` doc says default `null` but returns `"jdbc:sqlite:node.db"`
      (`NodeConfiguration.java:125-128`).
    - `Node.findPeer(Id)` doc says "list of PeerInfo" but returns a single `PeerInfo`
      (`Node.java:249`); several `@return` docs say only "completion of the operation"
      (getValue/removeValue/getPeer) without describing the result; `Node.kadNode` doc leaks the
      impl name "KadNode" (`Node.java:454`).
    - `Identity.encrypt(Id,byte[],byte[])` doc "may also incorporate the supplied nonce" is vague
      (`Identity.java:88`).
    - `Network` doc typos "true is the address…" (`Network.java:72,80`); `Network.of(InetAddress)`
      silently returns `IPv6` for a null/unknown address (`Network.java:105`).
    - `LookupOption` `ARBITRARY` name is unintuitive; `LOCAL` doc "reserved for future use" reads as
      contradictory (`LookupOption.java:42`).

29. **Utility-class hygiene** — `Version` and `Hash` have no private constructor (instantiable);
    `Version.build` does no null/length validation and uses the platform-default charset
    (`Version.java:48`), with a redundant double-mask `(v & 0xFF00) | (v & 0xFF)`.

30. **`DIDURL` incomplete/encapsulation** — `toURI()` can throw because path/query/fragment are not
    percent-encoded (TODO at `DIDURL.java:72`); fields are non-final (effectively immutable but not
    enforced).

31. **Style** — mixed tabs/spaces indentation in `CryptoContext.java` (space-indented JavaDoc vs
    tab-indented code); stray extra spaces before `return this;` in `UserProfile.Builder`.

---

## Recommended remediation plan (advisory)

**Phase A — P0 correctness (small, safe, high value).** Fix items 1–7. These are localized edits
with existing tests in `core/api/src/test` (`NodeInfoTests`, `ValueTests`, `PeerInfoTests`,
`UserProfileTests`, `CwtAuthHandlerTest`). Add/extend tests proving each fix (e.g., a Result
IPv4-set-doesn't-touch-IPv6 test; NodeInfo equals/hashCode consistency test; a
`scopeDelimiter(".")` test).

**Phase B — P1 crypto.** Treat #8 (MD5 KDF context) as the key decision — confirm intended
behavior with whoever owns the crypto design before changing, since it may affect already-derived
keys/compatibility. Apply #10–#14 which are low-risk.

**Phase C — P2 API consistency.** Decide the conventions (null handling #16, lookup returns #18,
naming #17) now, because they are breaking to change post-release. Bundle these.

**Phase D — P3 docs/headers/cleanup.** Mechanical: add the 10 license headers, remove dead code,
fix JavaDoc mismatches, add private constructors. Safe to do in one sweep.

**Suggested optional second review pass** over the un-reviewed packages (identifier DID/VC,
cwt SignedCwt, json, utils, service) before tagging the release.

## Verification
- `cd core && ./mvnw -pl api test` (run the `core/api` test module) after each phase.
- Add targeted unit tests for items 1, 2, 4, 5, 6 (each is a one-assertion regression test).
- For #7 (`isDestroyed`), assert `box.isDestroyed()` is true after `close()`.
- For crypto changes (#8), add a test that two different context strings derive different keys.