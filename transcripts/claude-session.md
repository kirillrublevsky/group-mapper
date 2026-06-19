# AI Transcript — Group Mapper

This file documents how an AI assistant (Claude Code, Anthropic) was used while
solving this task. It captures the reasoning, the key technical decisions, and the
back-and-forth that shaped the final implementation.

---

## 1. Task as given to the assistant

> Deterministically map a 64-bit (`long`) hashed identifier into named groups, where
> each group owns a contiguous half-open fraction of the `[0.0, 1.0)` space, e.g.
>
> | Group | Range          | Share |
> |-------|----------------|-------|
> | A     | `[0.0, 0.1)`   | 10%   |
> | B     | `[0.1, 0.4)`   | 30%   |
> | C     | `[0.4, 0.65)`  | 25%   |
> | D     | `[0.65, 1.0)`  | 35%   |
>
> Implement `String toGroup(long hashedIdentifier)` returning the group name, or
> `null` if no range matches.

---

## 2. Problem analysis (assistant reasoning)

The task decomposes into two independent sub-problems:

1. **Projection** — turn a 64-bit hash into a fraction `f ∈ [0.0, 1.0)`.
2. **Lookup** — find which group's range contains `f`.

### 2.1 The projection — and the trap

A Java `long` is **signed**: half of all values are negative. The naive
`hashedIdentifier / (double) Long.MAX_VALUE` is wrong on two counts — it ignores the
sign (negative hashes give negative fractions) and it uses the wrong denominator.

The correct mental model: the 64 hash bits represent an **unsigned** integer in
`[0, 2^64)`, and `f = unsigned(hash) / 2^64`.

Two correct ways to compute this were considered:

- **(A) Floating point, top 53 bits** — `(hashedIdentifier >>> 11) * 0x1.0p-53`.
  `>>>` is the *unsigned* right shift, so the sign bit is treated as just another
  data bit (no bias). This is exactly how `java.util.Random.nextDouble()` produces a
  uniform double in `[0, 1)`. A `double` has a 53-bit mantissa, so this keeps the full
  precision a `double` can hold.

- **(B) Pure integer thresholds** — precompute each boundary as
  `round(fraction * 2^64)` and compare with `Long.compareUnsigned`. Exact at full
  64-bit resolution, no floating point at all.

**Decision: (A).** Group boundaries are themselves given as decimal fractions
(`0.1`, `0.65`, …) which aren't exactly representable in binary anyway, so the
"exactness" advantage of (B) is largely theoretical here. (A) is shorter, is the
textbook idiom, and 53 bits of resolution distinguishes ~9·10¹⁵ buckets — astronomically
finer than any realistic group boundary. The sub-ULP (~1e-16) ambiguity at a boundary is
deterministic and negligible. The README records (B) as a documented alternative.

### 2.2 The lookup

Groups are sorted by lower bound and validated to be non-overlapping (gaps allowed →
`null`). Because they're sorted, a **binary search** over the upper bounds finds the
candidate group in O(log n), then a single lower-bound check confirms membership (this
is what lets gaps return `null` cleanly). For the 4-group example linear scan would be
fine, but binary search costs nothing in readability and scales.

---

## 3. Design decisions

- **`Group` as a `record`** (Java 17) — immutable value type `(name, lower, upper)`
  with a compact constructor that validates `0 ≤ lower < upper ≤ 1`.
- **`GroupMapper`** — constructed from a list of groups; constructor sorts them and
  rejects overlaps, so any `GroupMapper` instance is correct-by-construction. Exposes
  the required `String toGroup(long)`.
- **Determinism** — `toGroup` is pure: same hash + same config → same group, always.
- **Configurability** — the A/B/C/D split is just one configuration, supplied by the
  caller, not hard-coded into the algorithm.

---

## 4. Verification

- JUnit 5 tests cover: each example group, boundary inclusivity (`[lower, upper)`),
  the extreme hashes (`0`, `-1` = all-ones = largest unsigned, `Long.MIN/MAX_VALUE`),
  determinism, gap → `null`, validation failures, and a **distribution test**
  (~1,000,000 pseudo-random hashes land in each group within ~1% of the configured
  share) which is the real proof the projection is uniform and unbiased.

- The code was compiled with `javac` and the suite executed with the JUnit standalone
  console runner. After the second-pass hardening (§6) the suite grew to **17 tests, all
  passing**.

### 4.1 A real iteration the tests caught

On the first run, one test failed — but it was the *test expectation* that was wrong,
not the production code:

```
handlesNegativeHashesWithoutSignBias()  expected: <D> but was: <C>
```

I had guessed that `Long.MIN_VALUE` (`0x8000…0`) would land high in the space. In fact
its unsigned value is exactly `2^63`, so it projects to **exactly 0.5** → group C
`[0.4, 0.65)`. Likewise `Long.MAX_VALUE` is just under `2^63`, projecting to ~0.4999 →
also C. The production projection was correct all along; I corrected the expectations
and strengthened the test to also exercise a negative hash that lands in group D, so it
genuinely demonstrates "no sign bias" rather than just "doesn't crash". This is exactly
why the sign-bit extremes are in the suite.

---

## 5. Optimality analysis

Is this the optimal solution? The conclusion, after weighing the alternatives, is **yes —
effectively optimal** for the problem as stated. The reasoning:

### 5.1 Complexity

- **`toGroup` (the hot path)** is O(log n) in the number of groups, branch-light, and
  **allocates nothing** — it computes one `double` and runs an integer binary search over
  an array. This is the operation that runs per identifier, so it is the one that matters.
- **Construction** is O(n log n): one sort plus a single linear overlap scan. It runs once.

For comparison-based range lookup, O(log n) per query is the asymptotic optimum; you cannot
do better without exploiting extra structure (see below).

### 5.2 Alternatives considered, and why they aren't better

- **Linear scan instead of binary search.** For the 4-group example it is equivalent and
  arguably has better constant factors (cache-friendly, no mispredicts). It was rejected
  only because binary search costs nothing in readability and scales to many groups — a
  deliberate "no-regret" choice, not a measured win.
- **Exact integer thresholds** (`round(fraction · 2^64)` + `Long.compareUnsigned`). This is
  the only approach that is *more* precise — exact at full 64-bit resolution with no
  floating point. It was rejected because the boundaries themselves (`0.1`, `0.65`, …) are
  decimal fractions that are not exactly representable anyway, so the precision gain is
  theoretical, while the code is fiddlier (`fraction · 2^64` overflows `long` and needs care
  near 1.0). Recorded in the README as the documented alternative.
- **`TreeMap.floorEntry`.** Same O(log n) query, but you still must store the upper bound to
  distinguish a real match from a gap, so it is no simpler than the explicit sorted array —
  and it adds per-query autoboxing. No improvement.
- **O(1) bucket table.** Only possible if boundaries align to a fixed grid (e.g. all are
  multiples of 0.05). The task allows arbitrary boundaries, so a general O(1) table isn't
  available without quantising the boundaries — a correctness trade we don't want.

### 5.3 The projection is the genuinely optimal part

`(hash >>> 11) * 0x1.0p-53` is not just convenient — it is **exact** (no rounding): the top
53 bits fit a `double`'s mantissa precisely, and scaling by a power of two is lossless. It
is also the canonical JDK idiom (`Random.nextDouble()`), so it is uniform by construction.
There is no faster or more accurate way to obtain a uniform `double` fraction from 64 hash
bits.

**Verdict:** the design sits at the right point — optimal asymptotics on the hot path, an
allocation-free exact projection, and the only "more precise" alternative buys nothing
measurable while costing clarity.

---

## 6. Second-pass deep review (edge-case hardening)

A dedicated re-review pass was run specifically hunting for edge cases. The projection and
lookup logic held up under scrutiny (the exactness of `toFraction`, the strictly-ascending
upper-bound invariant the binary search relies on, `fraction == upperBound` going to the
next group, empty/NaN/Infinity handling). One real gap was found and fixed, and coverage
was widened:

- **Fix:** a `null` element inside the groups list previously failed obscurely during the
  sort; it is now rejected up front with a clear `"groups must not contain null"` message.
- **New tests (10 → 17):** empty configuration → `null`; a single full-coverage group that
  must *never* return `null` over 100k hashes; the partition property (200k random hashes
  always match when groups tile `[0,1)`); groups supplied out of order; a boundary shared by
  two adjacent groups belonging to the upper one; a leading gap → `null`; and null-element
  rejection.

---

## 7. How AI was used (summary)

The assistant did the full reasoning above, chose technique (A) with an explicit
rationale over (B), wrote `Group`, `GroupMapper`, the JUnit suite, the README, and
this transcript, then compiled and ran the tests to confirm everything passes. On
request it then performed an explicit **optimality analysis** (§5) and a **second-pass
edge-case review** (§6) that hardened the null-element handling and grew the suite to 17
tests. The human reviewed the technical choices (especially the signed→unsigned
projection and the optimality trade-offs) and the final structure.
