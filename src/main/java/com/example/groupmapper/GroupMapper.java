package com.example.groupmapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministically assigns a 64-bit hashed identifier to one of a fixed set of
 * {@link Group groups}, where each group owns a contiguous fraction of the hash space.
 *
 * <p>The mapping is a pure function of the hash and the configured groups: the same
 * input always yields the same group. This makes it suitable for stable bucketing such
 * as A/B-test assignment or sharding, where an identifier must land in the same group
 * on every evaluation.
 *
 * <h2>How a {@code long} becomes a fraction</h2>
 * A 64-bit hash is interpreted as an <em>unsigned</em> integer in {@code [0, 2^64)} and
 * projected onto {@code [0.0, 1.0)}. See {@link #toFraction(long)} for the details and
 * the reasoning behind the technique.
 *
 * <p>Groups must not overlap; gaps are permitted, in which case a hash landing in a gap
 * maps to {@code null}.
 */
public final class GroupMapper {

    /** Groups sorted ascending by {@link Group#lowerBound()}, with no overlaps. */
    private final List<Group> groups;

    /**
     * @param groups the groups to map into; must be non-null and mutually non-overlapping
     * @throws IllegalArgumentException if any two groups overlap
     */
    public GroupMapper(List<Group> groups) {
        Objects.requireNonNull(groups, "groups must not be null");
        List<Group> sorted = new ArrayList<>(groups);
        for (Group group : sorted) {
            Objects.requireNonNull(group, "groups must not contain null");
        }
        sorted.sort(Comparator.comparingDouble(Group::lowerBound));
        for (int i = 1; i < sorted.size(); i++) {
            Group previous = sorted.get(i - 1);
            Group current = sorted.get(i);
            if (current.lowerBound() < previous.upperBound()) {
                throw new IllegalArgumentException(
                        "groups overlap: " + previous + " and " + current);
            }
        }
        this.groups = List.copyOf(sorted);
    }

    /**
     * Maps the given hash to the name of the group whose range contains it.
     *
     * @param hashedIdentifier a 64-bit hash (e.g. from MurmurHash3 or MD5)
     * @return the matching group's name, or {@code null} if the hash falls in a gap
     *         between groups
     */
    public String toGroup(long hashedIdentifier) {
        double fraction = toFraction(hashedIdentifier);

        // Binary search for the first group whose upper bound is strictly above the
        // fraction; that is the only group that can possibly contain it.
        int low = 0;
        int high = groups.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (groups.get(mid).upperBound() <= fraction) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }

        if (low < groups.size()) {
            Group candidate = groups.get(low);
            // Confirm the lower bound too; this is what makes a hash in a gap return null.
            if (fraction >= candidate.lowerBound()) {
                return candidate.name();
            }
        }
        return null;
    }

    /**
     * Projects a 64-bit hash onto a uniformly distributed fraction in {@code [0.0, 1.0)}.
     *
     * <p>The hash is treated as an <em>unsigned</em> 64-bit value. We take its top 53
     * bits via the unsigned shift {@code >>> 11} (a signed {@code >>} would replicate
     * the sign bit and bias the result) and scale by {@code 2^-53}. 53 bits is exactly
     * the precision of a {@code double}'s mantissa, so no information a {@code double}
     * could hold is lost. This is the same idiom {@link java.util.Random#nextDouble()}
     * uses to produce a uniform double, which is precisely the distribution we need.
     *
     * @param hashedIdentifier the 64-bit hash
     * @return a fraction in {@code [0.0, 1.0)}
     */
    static double toFraction(long hashedIdentifier) {
        return (hashedIdentifier >>> 11) * 0x1.0p-53;
    }
}
