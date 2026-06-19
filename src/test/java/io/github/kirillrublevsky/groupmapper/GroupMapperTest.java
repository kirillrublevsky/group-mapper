package io.github.kirillrublevsky.groupmapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GroupMapperTest {

    /** The example configuration from the task: A=10%, B=30%, C=25%, D=35%. */
    private static GroupMapper exampleMapper() {
        return new GroupMapper(List.of(
                new Group("A", 0.0, 0.1),
                new Group("B", 0.1, 0.4),
                new Group("C", 0.4, 0.65),
                new Group("D", 0.65, 1.0)));
    }

    /** Smallest hash (0) projects to fraction 0.0 -> the first group. */
    @Test
    void mapsSmallestHashToFirstGroup() {
        assertEquals("A", exampleMapper().toGroup(0L));
    }

    /** Largest unsigned hash (all bits set, i.e. -1L) projects just below 1.0 -> last group. */
    @Test
    void mapsLargestHashToLastGroup() {
        assertEquals("D", exampleMapper().toGroup(-1L));
    }

    /**
     * Negative longs are valid hashes (the sign bit is just data). They must map into
     * the upper half of the space, not produce negative fractions or nulls.
     */
    @Test
    void handlesNegativeHashesWithoutSignBias() {
        GroupMapper mapper = exampleMapper();
        // All of these have the sign bit set, so they live in the unsigned range [2^63, 2^64).
        assertEquals("C", mapper.toGroup(Long.MIN_VALUE));        // 0x8000... = unsigned 2^63 -> exactly 0.5
        assertEquals("C", mapper.toGroup(hashForFraction(0.5)));  // exactly 0.5
        assertEquals("D", mapper.toGroup(hashForFraction(0.8)));  // negative long, fraction 0.8 -> D
        assertEquals("D", mapper.toGroup(-1L));                   // 0xFFFF... -> just under 1.0
    }

    /** The fraction is always in [0.0, 1.0), including for the sign-bit extremes. */
    @Test
    void fractionStaysInUnitInterval() {
        for (long hash : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 123456789L}) {
            double f = GroupMapper.toFraction(hash);
            assertTrue(f >= 0.0 && f < 1.0, "fraction out of range for hash " + hash + ": " + f);
        }
    }

    /** Lower bounds are inclusive, upper bounds exclusive: a boundary belongs to the upper group. */
    @Test
    void boundaryIsInclusiveAtLowerExclusiveAtUpper() {
        // Construct hashes that project to exactly 0.1 and just below it.
        long atTenPercent = hashForFraction(0.1);
        long justBelowTenPercent = atTenPercent - 1;
        GroupMapper mapper = exampleMapper();
        assertEquals("B", mapper.toGroup(atTenPercent), "0.1 must belong to B, not A");
        assertEquals("A", mapper.toGroup(justBelowTenPercent), "just below 0.1 must stay in A");
    }

    /** Same input always yields the same group. */
    @Test
    void isDeterministic() {
        GroupMapper mapper = exampleMapper();
        long hash = 0x123456789ABCDEFL;
        String first = mapper.toGroup(hash);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, mapper.toGroup(hash));
        }
    }

    /** A hash that lands in a gap between groups maps to null. */
    @Test
    void returnsNullForUnmatchedHash() {
        GroupMapper mapper = new GroupMapper(List.of(
                new Group("X", 0.0, 0.25),
                new Group("Y", 0.75, 1.0))); // gap: [0.25, 0.75)
        assertNull(mapper.toGroup(hashForFraction(0.5)));
        assertEquals("X", mapper.toGroup(hashForFraction(0.1)));
        assertEquals("Y", mapper.toGroup(hashForFraction(0.9)));
    }

    /** Overlapping groups are rejected at construction time. */
    @Test
    void rejectsOverlappingGroups() {
        assertThrows(IllegalArgumentException.class, () -> new GroupMapper(List.of(
                new Group("A", 0.0, 0.5),
                new Group("B", 0.4, 1.0))));
    }

    /** Malformed individual groups are rejected. */
    @Test
    void rejectsInvalidGroupBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Group("bad", 0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Group("bad", -0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Group("bad", 0.5, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new Group("bad", 0.5, 0.4));
    }

    /**
     * The defining property: over many pseudo-random hashes, each group receives a
     * share of identifiers matching its configured width (within a small tolerance).
     * This is what proves the projection is uniform and free of sign bias.
     */
    @Test
    void distributionMatchesConfiguredShares() {
        GroupMapper mapper = exampleMapper();
        Map<String, Integer> counts = new HashMap<>();
        int samples = 1_000_000;
        Random random = new Random(42); // fixed seed -> reproducible
        for (int i = 0; i < samples; i++) {
            String group = mapper.toGroup(random.nextLong());
            counts.merge(group, 1, Integer::sum);
        }

        Map<String, Double> expected = Map.of("A", 0.10, "B", 0.30, "C", 0.25, "D", 0.35);
        for (Map.Entry<String, Double> e : expected.entrySet()) {
            double actual = counts.getOrDefault(e.getKey(), 0) / (double) samples;
            assertTrue(Math.abs(actual - e.getValue()) < 0.01,
                    "group " + e.getKey() + " expected ~" + e.getValue() + " but got " + actual);
        }
    }

    /** An empty configuration maps everything to null without error. */
    @Test
    void emptyMapperReturnsNull() {
        GroupMapper mapper = new GroupMapper(List.of());
        assertNull(mapper.toGroup(0L));
        assertNull(mapper.toGroup(-1L));
        assertNull(mapper.toGroup(123456789L));
    }

    /** A single group spanning the whole interval captures every possible hash. */
    @Test
    void fullCoverageNeverReturnsNull() {
        GroupMapper mapper = new GroupMapper(List.of(new Group("ALL", 0.0, 1.0)));
        Random random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            assertEquals("ALL", mapper.toGroup(random.nextLong()));
        }
        assertEquals("ALL", mapper.toGroup(0L));  // smallest fraction
        assertEquals("ALL", mapper.toGroup(-1L)); // largest fraction
    }

    /** When the groups fully partition [0,1), every hash matches exactly one group. */
    @Test
    void contiguousPartitionAlwaysMatches() {
        GroupMapper mapper = exampleMapper();
        Random random = new Random(99);
        for (int i = 0; i < 200_000; i++) {
            assertNotNull(mapper.toGroup(random.nextLong()));
        }
    }

    /** Groups supplied out of order are sorted internally and still map correctly. */
    @Test
    void acceptsGroupsInAnyOrder() {
        GroupMapper mapper = new GroupMapper(List.of(
                new Group("D", 0.65, 1.0),
                new Group("A", 0.0, 0.1),
                new Group("C", 0.4, 0.65),
                new Group("B", 0.1, 0.4)));
        assertEquals("A", mapper.toGroup(hashForFraction(0.05)));
        assertEquals("B", mapper.toGroup(hashForFraction(0.2)));
        assertEquals("C", mapper.toGroup(hashForFraction(0.5)));
        assertEquals("D", mapper.toGroup(hashForFraction(0.9)));
    }

    /** Adjacent groups may share a boundary value; it belongs to the upper group. */
    @Test
    void sharedBoundaryBelongsToUpperGroup() {
        GroupMapper mapper = new GroupMapper(List.of(
                new Group("low", 0.0, 0.5),
                new Group("high", 0.5, 1.0)));
        assertEquals("high", mapper.toGroup(hashForFraction(0.5)));
        assertEquals("low", mapper.toGroup(hashForFraction(0.5) - 1));
    }

    /** A hash below the first group's lower bound (a leading gap) maps to null. */
    @Test
    void leadingGapReturnsNull() {
        GroupMapper mapper = new GroupMapper(List.of(new Group("tail", 0.5, 1.0)));
        assertNull(mapper.toGroup(hashForFraction(0.2)));
        assertEquals("tail", mapper.toGroup(hashForFraction(0.7)));
    }

    /** Null elements in the group list are rejected rather than failing obscurely later. */
    @Test
    void rejectsNullGroupElements() {
        List<Group> withNull = new ArrayList<>();
        withNull.add(new Group("A", 0.0, 0.5));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new GroupMapper(withNull));
    }

    /**
     * Inverse of {@link GroupMapper#toFraction}: smallest hash whose fraction is >= the
     * target. Used only by tests to land on precise boundaries.
     */
    private static long hashForFraction(double fraction) {
        // toFraction = (hash >>> 11) * 2^-53, so hash >>> 11 = ceil(fraction * 2^53),
        // then shift back left by 11 bits.
        long topBits = (long) Math.ceil(fraction * 0x1.0p53);
        return topBits << 11;
    }
}
