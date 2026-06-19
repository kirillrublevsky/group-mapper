# Group Mapper

Deterministically assign a 64-bit (`long`) hashed identifier to one of a set of named
groups, where each group owns a contiguous fraction of the hash space. The canonical use
case is stable bucketing — A/B-test assignment, feature rollouts, or sharding — where a
given identifier must always land in the same group.

## The task

> We want to deterministically create groups from a set of data, based on a 64-bit
> (`long`) hash of an identifier. The groups correspond to a portion of the total
> possible set of hashed identifiers. For example:
>
> | Group | Range          | Share of identifiers |
> |-------|----------------|----------------------|
> | A     | `[0.0, 0.1)`   | 10%                  |
> | B     | `[0.1, 0.4)`   | 30%                  |
> | C     | `[0.4, 0.65)`  | 25%                  |
> | D     | `[0.65, 1.0)`  | 35%                  |
>
> Ranges are half-open: the lower bound is included, the upper bound excluded.
>
> Implement:
>
> ```java
> /**
>  * @param hashedIdentifier A 64-bit hash (e.g., from MurmurHash3 or MD5)
>  * @return The group name ("A","B","C", or "D"), or null if no group matches.
>  */
> public String toGroup(long hashedIdentifier) {
>     // Your logic here
> }
> ```

## Usage

```java
GroupMapper mapper = new GroupMapper(List.of(
        new Group("A", 0.0, 0.1),
        new Group("B", 0.1, 0.4),
        new Group("C", 0.4, 0.65),
        new Group("D", 0.65, 1.0)));

String group = mapper.toGroup(someHash); // "A" | "B" | "C" | "D", or null in a gap
```

The group set is supplied by the caller, so the same engine serves any split — the
A/B/C/D table is just one configuration, not baked into the algorithm.

## How it works

The problem splits cleanly into two steps.

### 1. Project the `long` onto `[0.0, 1.0)`

This is the only subtle part, because a Java `long` is **signed**: half of all values
are negative. The right mental model is that the 64 hash bits encode an **unsigned**
integer in `[0, 2^64)`, and the fraction we want is `unsigned(hash) / 2^64`.

The implementation ([`GroupMapper.toFraction`](src/main/java/com/example/groupmapper/GroupMapper.java)):

```java
static double toFraction(long hashedIdentifier) {
    return (hashedIdentifier >>> 11) * 0x1.0p-53;
}
```

- `>>> 11` is the **unsigned** right shift. It keeps the top 53 bits and treats the sign
  bit as ordinary data. A signed `>>` would copy the sign bit downward and bias the
  result — that is the classic bug this avoids.
- `0x1.0p-53` is `2^-53`. Scaling the top 53 bits by it maps `[0, 2^53)` onto `[0.0, 1.0)`.
- 53 bits is exactly a `double`'s mantissa width, so no precision a `double` can hold is
  thrown away. This is the same idiom `java.util.Random.nextDouble()` uses to produce a
  uniform double — and uniformity is precisely the property we need for the group shares
  to come out right.

**Why floating point rather than exact integer thresholds?** An alternative is to keep
everything in integers: precompute each boundary as `round(fraction * 2^64)` and compare
with `Long.compareUnsigned`. That is exact at full 64-bit resolution. I chose the
floating-point projection because:

- The boundaries themselves (`0.1`, `0.65`, …) are decimal fractions that aren't exactly
  representable in binary regardless, so "exactness" is largely theoretical here.
- 53 bits distinguishes ~9·10¹⁵ buckets — astronomically finer than any realistic group
  boundary. The residual ambiguity at a boundary is sub-ULP (~1e-16), deterministic, and
  has no measurable effect on the distribution (see the distribution test).
- It is the shorter, more idiomatic, more readable expression of intent.

### 2. Find the group containing the fraction

Groups are sorted by lower bound and validated to be non-overlapping at construction, so
a `GroupMapper` is **correct by construction**. Lookup is a binary search over the upper
bounds — O(log n) — followed by a single lower-bound check that lets a hash falling in a
gap return `null`:

```java
public String toGroup(long hashedIdentifier) {
    double fraction = toFraction(hashedIdentifier);
    // binary search for the first group whose upperBound > fraction, then confirm lowerBound
    ...
}
```

For the 4-group example a linear scan would be equivalent, but binary search costs nothing
in readability and scales to arbitrarily many groups.

## Design notes

- **`Group`** is an immutable `record` `(name, lowerBound, upperBound)` whose compact
  constructor enforces `0 ≤ lower < upper ≤ 1`. Half-open ranges `[lower, upper)` let
  adjacent groups share a boundary value without overlapping.
- **`GroupMapper`** rejects overlapping groups on construction; gaps are allowed and map
  to `null`.
- **Determinism**: `toGroup` is a pure function of the hash and the configured groups —
  same input, same output, every time.

## Project layout

```
src/main/java/com/example/groupmapper/Group.java         # immutable, validated range
src/main/java/com/example/groupmapper/GroupMapper.java   # projection + lookup, the toGroup entry point
src/test/java/com/example/groupmapper/GroupMapperTest.java
transcripts/claude-session.md                            # AI usage transcript
```

## Building and testing

Requires JDK 17+.

```bash
mvn test
```

The suite (17 tests) covers:

- boundary inclusivity (`[lower, upper)`, and a boundary shared by two adjacent groups going
  to the upper one);
- the sign-bit extremes (`0`, `-1`, `Long.MIN/MAX_VALUE`) and negative hashes generally — the
  proof there is no sign bias;
- determinism (same input → same output across many calls);
- gaps → `null` (both an interior gap and a leading gap), plus an empty configuration;
- a full-coverage partition that must *never* return `null` over 200k random hashes;
- validation failures (overlapping groups, malformed bounds, null elements) and acceptance of
  groups supplied out of order;
- a **distribution test** that runs 1,000,000 pseudo-random hashes and asserts each group's
  observed share is within ~1% of its configured share — the empirical proof that the
  projection is uniform and unbiased.

## Use of AI tools

This solution was developed with **Claude Code** (Anthropic). The assistant performed the
problem analysis, weighed the floating-point vs. integer-threshold projection (and chose
the former with the rationale above), wrote the production code, the JUnit suite, this
README, and the transcript, then compiled and ran the tests locally to confirm they pass.

During that run two test *expectations* were initially wrong (I had mis-estimated where
`Long.MIN_VALUE` and `Long.MAX_VALUE` land — both project to ~0.5, i.e. group C). The
tests caught it, the production code was correct, and the expectations were fixed. The full
reasoning and this iteration are recorded in [`transcripts/claude-session.md`](transcripts/claude-session.md).
