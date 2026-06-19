package io.github.kirillrublevsky.groupmapper;

import java.util.Objects;

/**
 * A named group that owns a contiguous, half-open slice {@code [lowerBound, upperBound)}
 * of the unit interval {@code [0.0, 1.0)}.
 *
 * <p>The slice is <em>half-open</em>: {@code lowerBound} is included and {@code upperBound}
 * is excluded. This lets adjacent groups share a boundary value without overlapping
 * (e.g. {@code [0.0, 0.1)} and {@code [0.1, 0.4)} meet at {@code 0.1}, which belongs to
 * the second group only).
 *
 * <p>Instances are immutable and validated on construction, so any {@code Group} that
 * exists is guaranteed to describe a well-formed range.
 *
 * @param name       the group's identifier (e.g. {@code "A"}); must be non-null
 * @param lowerBound inclusive lower bound, in {@code [0.0, 1.0)}
 * @param upperBound exclusive upper bound, in {@code (lowerBound, 1.0]}
 */
public record Group(String name, double lowerBound, double upperBound) {

    public Group {
        Objects.requireNonNull(name, "name must not be null");
        if (Double.isNaN(lowerBound) || Double.isNaN(upperBound)) {
            throw new IllegalArgumentException("bounds must not be NaN for group " + name);
        }
        if (lowerBound < 0.0 || upperBound > 1.0) {
            throw new IllegalArgumentException(
                    "group " + name + " must lie within [0.0, 1.0] but was ["
                            + lowerBound + ", " + upperBound + "]");
        }
        if (lowerBound >= upperBound) {
            throw new IllegalArgumentException(
                    "group " + name + " must have lowerBound < upperBound but was ["
                            + lowerBound + ", " + upperBound + ")");
        }
    }

    /**
     * @param fraction a value in {@code [0.0, 1.0)}
     * @return {@code true} if {@code fraction} falls in this group's half-open range
     */
    public boolean contains(double fraction) {
        return fraction >= lowerBound && fraction < upperBound;
    }
}
