/*
 * Copyright (C) 2025 Daniel Agota <danago@risquanter.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.risquanter.metalog;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link QPFitter}: the fit's preconditions, the density after
 * each boundedness transform, monotonicity to the endpoints, and the two ways a
 * fit is refused.
 */
class QPFitterTest {

    /** Expert triplet from the README: the 10th, 50th and 90th percentiles. */
    private static final double[] TRIPLET_P = {0.10, 0.50, 0.90};
    private static final double[] TRIPLET_X = {17.0, 24.0, 35.0};

    /** Bounds from the README example, both outside the triplet's values. */
    private static final double TRIPLET_LOWER = 16.0;
    private static final double TRIPLET_UPPER = 40.0;

    /** Nine observations from {@code ObservationalMetalogFitExample}, sorted. */
    private static final double[] OBSERVATIONS = {65, 78, 82, 85, 90, 110, 127, 143, 160};
    private static final double OBSERVATIONS_UPPER = 170.0;

    /** Five heavy-tail anchors whose unconstrained tails decrease. */
    private static final double[] HEAVY5_P = {0.05, 0.25, 0.5, 0.9, 0.99};
    private static final double[] HEAVY5_X = {0.2, 1, 5, 30, 200};

    /** Four heavy-tail anchors that admit no monotone four-term unbounded fit. */
    private static final double[] HEAVY4_P = {0.05, 0.5, 0.9, 0.99};
    private static final double[] HEAVY4_X = {0.2, 2, 16, 120};

    /**
     * Matches the margin of {@code QPFitter}'s default grid, which is also how
     * close to 0 and 1 the README tells callers to clamp.
     */
    private static final double MARGIN = 1e-12;

    /** Matches the interval count of {@code QPFitter}'s default grid. */
    private static final int GRID_INTERVALS = 100;

    /** Matches {@code QPFitter}'s default floor on the slope at grid points. */
    private static final double EPSILON = 1e-6;

    /** Interior percentiles at which pdf(p) is compared with 1/(dx/dp). */
    private static final double[] INTERIOR_P = {0.1, 0.3, 0.5, 0.7, 0.9};

    /** Uniform intervals in the monotonicity sweep, matching the fitter's own check. */
    private static final int SWEEP_INTERVALS = 10_000;

    /**
     * Central-difference step for dx/dp, relative to the distance from the
     * nearer endpoint. A fixed step would exceed p itself near 0; a relative one
     * keeps truncation error of order the step squared at every p.
     */
    private static final double RELATIVE_STEP = 1e-4;

    /** Midpoint-rule intervals for integrating the density over (0,1). */
    private static final int INTEGRATION_INTERVALS = 20_000;

    /**
     * Tolerance on the integral of the density. The integrand is 1 wherever the
     * density is correct, and a missing transform factor makes it 9.51 for the
     * README triplet lower-bounded at 16.
     */
    private static final double INTEGRAL_TOLERANCE = 1e-3;

    /**
     * Tolerance on pdf(p)·dx/dp = 1 at interior percentiles, where the
     * central difference at {@link #RELATIVE_STEP} is accurate to about 1e-8.
     */
    private static final double RECIPROCAL_TOLERANCE = 1e-5;

    /** Iteration cap under which the solver stops at state FEASIBLE. */
    private static final int EARLY_STOP_ITERATIONS = 50;

    private static double[] hazenPositions(int n) {
        return IntStream.range(0, n).mapToDouble(i -> (i + 0.5) / n).toArray();
    }

    private static double[] defaultGrid() {
        return IntStream.rangeClosed(0, GRID_INTERVALS)
                .mapToDouble(i -> MARGIN + (1 - 2 * MARGIN) * i / (double) GRID_INTERVALS)
                .toArray();
    }

    /** The four boundedness modes, each applied to a fresh fit of the triplet. */
    private static final Map<String, UnaryOperator<QPFitter.Options>> MODES = Map.of(
            "unbounded", o -> o,
            "lower-bounded", o -> o.lower(TRIPLET_LOWER),
            "upper-bounded", o -> o.upper(TRIPLET_UPPER),
            "fully bounded", o -> o.lower(TRIPLET_LOWER).upper(TRIPLET_UPPER));

    private static double slope(Metalog m, double p) {
        double h = RELATIVE_STEP * Math.min(p, 1.0 - p);
        return (m.quantile(p + h) - m.quantile(p - h)) / (2 * h);
    }

    /** Percentiles spanning (0,1) uniformly and every decade down to the margin. */
    private static double[] endpointSweep() {
        TreeSet<Double> s = new TreeSet<>();
        for (int i = 1; i < SWEEP_INTERVALS; i++) {
            s.add(i / (double) SWEEP_INTERVALS);
        }
        for (double d = 1e-2; d >= MARGIN; d /= 10.0) {
            s.add(d);
            s.add(1.0 - d);
        }
        s.add(MARGIN);
        s.add(1.0 - MARGIN);
        return s.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void fitterRejectsMoreTermsThanDataPoints() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 4).fit());
        assertTrue(e.getMessage().contains("terms=4"), e.getMessage());
        assertTrue(e.getMessage().contains("data points=3"), e.getMessage());

        assertDoesNotThrow(() -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).fit());
    }

    @Test
    void fitterRejectsAnchorsOutsideTheBounds() {
        IllegalArgumentException atLower = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).lower(TRIPLET_X[0]).fit());
        assertTrue(atLower.getMessage().contains("x[0]"), atLower.getMessage());

        IllegalArgumentException atUpper = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).upper(TRIPLET_X[2]).fit());
        assertTrue(atUpper.getMessage().contains("x[2]"), atUpper.getMessage());

        IllegalArgumentException reversed = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3)
                        .lower(TRIPLET_UPPER).upper(TRIPLET_LOWER).fit());
        assertTrue(reversed.getMessage().contains("lower bound must be below upper bound"),
                reversed.getMessage());
    }

    @Test
    void fitterRejectsNonFiniteInput() {
        double[] infiniteAnchor = {TRIPLET_X[0], TRIPLET_X[1], Double.POSITIVE_INFINITY};
        assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, infiniteAnchor, 3).lower(TRIPLET_LOWER).fit());
        assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).lower(Double.NEGATIVE_INFINITY));
        IllegalArgumentException nanBound = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).lower(Double.NaN));
        assertTrue(nanBound.getMessage().contains("lower bound"), nanBound.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).epsilon(-1.0).fit());
        assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).epsilon(Double.NaN).fit());
    }

    @Test
    void fitterRejectsRepeatedPercentiles() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(new double[] {0.1, 0.1, 0.9}, new double[] {1, 1, 5}, 3).fit());
        assertTrue(e.getMessage().contains("pData[0]") && e.getMessage().contains("pData[1]"),
                e.getMessage());
    }

    @Test
    void gridNullIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QPFitter.with(TRIPLET_P, TRIPLET_X, 3).grid(null));
    }

    @Test
    void verificationGridCoversBothTailsOfAnAsymmetricGrid() {
        double[] asymmetric = IntStream.rangeClosed(0, GRID_INTERVALS)
                .mapToDouble(i -> 0.3 + (1.0 - MARGIN - 0.3) * i / (double) GRID_INTERVALS)
                .toArray();
        double[] sweep = QPUnboundedConstrainedFitter.verificationGrid(asymmetric);
        boolean upperTailSampled = Arrays.stream(sweep)
                .anyMatch(p -> p > 1.0 - 1e-11 && p < 1.0 - MARGIN);
        assertTrue(upperTailSampled, "no sweep point between 1 - 1e-11 and the grid's upper end");
    }

    @Test
    void returnedMetalogIsUnaffectedByLaterBoundChanges() {
        QPFitter.Options options = QPFitter.with(TRIPLET_P, TRIPLET_X, 3);
        Metalog unbounded = options.fit();
        double[] before = Arrays.stream(INTERIOR_P).map(unbounded::quantile).toArray();
        options.lower(TRIPLET_LOWER).fit();
        double[] after = Arrays.stream(INTERIOR_P).map(unbounded::quantile).toArray();
        assertArrayEquals(before, after, 0.0);
    }

    @Test
    void boundedFitDensityIntegratesToOne() {
        MODES.forEach((name, mode) -> {
            Metalog m = mode.apply(QPFitter.with(TRIPLET_P, TRIPLET_X, 3)).fit();
            double sum = 0.0;
            for (int i = 0; i < INTEGRATION_INTERVALS; i++) {
                double p = (i + 0.5) / INTEGRATION_INTERVALS;
                sum += m.pdf(p) * slope(m, p);
            }
            assertEquals(1.0, sum / INTEGRATION_INTERVALS, INTEGRAL_TOLERANCE, name);
        });
    }

    @Test
    void boundedFitPdfIsReciprocalOfQuantileSlope() {
        MODES.forEach((name, mode) -> {
            Metalog m = mode.apply(QPFitter.with(TRIPLET_P, TRIPLET_X, 3)).fit();
            for (double p : INTERIOR_P) {
                assertEquals(1.0, m.pdf(p) * slope(m, p), RECIPROCAL_TOLERANCE,
                        name + " at p=" + p);
            }
        });
    }

    @Test
    void boundedFitQuantileDensityIsTheSlopeOfItsQuantile() {
        MODES.forEach((name, mode) -> {
            Metalog m = mode.apply(QPFitter.with(TRIPLET_P, TRIPLET_X, 3)).fit();
            for (double p : INTERIOR_P) {
                double numeric = slope(m, p);
                assertEquals(numeric, m.quantileDensity(p), RECIPROCAL_TOLERANCE * numeric,
                        name + " at p=" + p);
            }
        });
    }

    @Test
    void builderIsUnaffectedByLaterChangesToItsArrays() {
        double[] p = TRIPLET_P.clone();
        double[] x = TRIPLET_X.clone();
        QPFitter.Options options = QPFitter.with(p, x, 3);
        x[0] = 0.0;
        Metalog changedAfterWith = options.fit();
        Metalog original = QPFitter.with(TRIPLET_P, TRIPLET_X, 3).fit();
        for (double q : INTERIOR_P) {
            assertEquals(original.quantile(q), changedAfterWith.quantile(q), 0.0, "p=" + q);
        }
    }

    @Test
    void highOrderBoundedFitIsMonotoneToTheEndpoints() {
        Metalog m = QPFitter.with(hazenPositions(OBSERVATIONS.length), OBSERVATIONS, 9)
                .lower(0.0).upper(OBSERVATIONS_UPPER).fit();
        for (double p : endpointSweep()) {
            assertTrue(m.quantileDensity(p) > 0.0, "slope at p=" + p + " is " + m.quantileDensity(p));
        }
    }

    @Test
    void heavyTailLowerBoundedFitIsMonotoneToTheEndpoints() {
        Metalog m = QPFitter.with(HEAVY5_P, HEAVY5_X, 5).lower(0.0).fit();
        for (double p : endpointSweep()) {
            assertTrue(m.quantileDensity(p) > 0.0, "slope at p=" + p + " is " + m.quantileDensity(p));
        }
    }

    @Test
    void fitterRejectsAnchorsNoMonotoneFitAdmits() {
        MetalogInfeasibleException e = assertThrows(MetalogInfeasibleException.class,
                () -> QPFitter.with(HEAVY4_P, HEAVY4_X, 4).fit());
        assertEquals(4, e.terms());
        assertEquals(4, e.dataPoints());
    }

    @Test
    void fitterRefusesAResultTheSolverDidNotFinish() {
        double[] p = hazenPositions(OBSERVATIONS.length);
        Boundedness bounds = new Boundedness.Both(0.0, OBSERVATIONS_UPPER);
        double[] z = Arrays.stream(OBSERVATIONS).map(bounds::toZ).toArray();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new QPUnboundedConstrainedFitter(p, z, 9, EPSILON, defaultGrid(),
                        EARLY_STOP_ITERATIONS).fit());
        assertTrue(e.getMessage().contains("FEASIBLE"), e.getMessage());

        assertDoesNotThrow(() -> new QPUnboundedConstrainedFitter(p, z, 9, EPSILON, defaultGrid(),
                Integer.MAX_VALUE).fit());
    }
}
