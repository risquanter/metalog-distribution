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
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TailCertificate}: it refutes vectors with a tail dip,
 * certifies every fit the suite produces, and never certifies a vector a fine
 * scan finds decreasing.
 */
class TailCertificateTest {

    /** Matches the margin of {@code QPFitter}'s default grid. */
    private static final double MARGIN = 1e-12;

    /** Matches the interval count of {@code QPFitter}'s default grid. */
    private static final int GRID_INTERVALS = 100;

    /** Matches {@code QPFitter}'s default floor on the slope at grid points. */
    private static final double EPSILON = 1e-6;

    /** Log-spaced points per tail in the independent scan, from 1e-2 down to the margin. */
    private static final int SCAN_POINTS = 2_400;

    /** Seeded vectors per randomised test. */
    private static final int VECTORS = 500;

    /** Points per tail in the threshold soundness check. */
    private static final int THRESHOLD_CHECK_POINTS = 1_000;

    /**
     * The review's constructed vector: both tail coefficients positive, slope
     * positive at every grid row and every sweep point, negative near
     * 1 − 3.4e-6.
     */
    private static final double[] REVIEW_VECTOR = {0, 0.5 + Math.exp(-12.6), -1, 10.599};

    private static double[] defaultGrid() {
        return IntStream.rangeClosed(0, GRID_INTERVALS)
                .mapToDouble(i -> MARGIN + (1 - 2 * MARGIN) * i / (double) GRID_INTERVALS)
                .toArray();
    }

    private static TailCertificate.Verdict certify(double[] a) {
        return TailCertificate.certify(a, MARGIN, 1.0 - MARGIN);
    }

    /** The smallest slope at log-spaced points in both tails, from 1e-2 to the margin. */
    private static double scanMinimum(double[] a) {
        Metalog m = new Metalog(a);
        double low = Math.log10(TailCertificate.TAIL_END);
        double high = Math.log10(MARGIN);
        double min = Double.MAX_VALUE;
        for (int i = 0; i <= SCAN_POINTS; i++) {
            double q = Math.pow(10.0, low + (high - low) * i / SCAN_POINTS);
            min = Math.min(min, Math.min(m.quantileDensity(q), m.quantileDensity(1.0 - q)));
        }
        return min;
    }

    /** Fits through the package-private fitter, so the coefficients are the z-space ones. */
    private static double[] fit(double[] p, double[] x, int terms, Boundedness bounds) {
        double[] z = Arrays.stream(x).map(bounds::toZ).toArray();
        return new QPUnboundedConstrainedFitter(p, z, terms, EPSILON, defaultGrid()).fit();
    }

    @Test
    void refutesTheReviewVector() {
        TailCertificate.Verdict v = certify(REVIEW_VECTOR);
        TailCertificate.Verdict.Refuted r = assertInstanceOf(TailCertificate.Verdict.Refuted.class, v);
        assertTrue(r.p() > 1.0 - TailCertificate.TAIL_END, "refuted at p=" + r.p());
        assertTrue(r.slope() <= 0.0);
    }

    @Test
    void certifiesEveryFitInTheSuite() {
        double[] tripletP = {0.10, 0.50, 0.90};
        double[] tripletX = {17.0, 24.0, 35.0};
        Boundedness[] tripletModes = {
            new Boundedness.Unbounded(),
            new Boundedness.Lower(16.0),
            new Boundedness.Upper(40.0),
            new Boundedness.Both(16.0, 40.0)};
        for (Boundedness mode : tripletModes) {
            assertInstanceOf(TailCertificate.Verdict.Certified.class,
                    certify(fit(tripletP, tripletX, 3, mode)), mode.toString());
        }

        double[] observations = {65, 78, 82, 85, 90, 110, 127, 143, 160};
        double[] hazen = IntStream.range(0, observations.length)
                .mapToDouble(i -> (i + 0.5) / observations.length).toArray();
        for (int terms : new int[] {4, 9}) {
            assertInstanceOf(TailCertificate.Verdict.Certified.class,
                    certify(fit(hazen, observations, terms, new Boundedness.Both(0.0, 170.0))),
                    "nine observations, terms=" + terms);
        }

        assertInstanceOf(TailCertificate.Verdict.Certified.class,
                certify(fit(new double[] {0.05, 0.25, 0.5, 0.9, 0.99},
                        new double[] {0.2, 1, 5, 30, 200}, 5, new Boundedness.Lower(0.0))),
                "five heavy-tail anchors");
    }

    @Test
    void neverCertifiesAConstructedDip() {
        Random random = new Random(7);
        int dips = 0;
        for (int i = 0; i < VECTORS; i++) {
            double c3 = 2 + 18 * random.nextDouble();
            double tailCoefficient = Math.exp(-6 - 12 * random.nextDouble());
            double a2 = -(0.5 + random.nextDouble());
            double[] a = {0, tailCoefficient - 0.5 * a2, a2, c3};
            double scan = scanMinimum(a);
            boolean certified = certify(a) instanceof TailCertificate.Verdict.Certified;
            if (scan <= 0.0) {
                dips++;
                assertFalse(certified, "certified a vector with a tail dip: " + Arrays.toString(a));
            }
            if (certified) {
                assertTrue(scan > 0.0, "certified, yet the scan finds " + scan + ": " + Arrays.toString(a));
            }
        }
        assertTrue(dips > 0, "the family produced no dip, so the test checks nothing");
    }

    @Test
    void thresholdIsSound() {
        Random random = new Random(11);
        int checked = 0;
        while (checked < VECTORS) {
            int terms = 2 + random.nextInt(8);
            double[] a = new double[terms];
            for (int j = 0; j < terms; j++) {
                a[j] = random.nextGaussian() * (j == 1 ? 1.0 : 0.4);
            }
            a[1] = Math.abs(a[1]) + 0.05;
            Metalog m = new Metalog(a);
            for (Metalog.Tail tail : Metalog.Tail.values()) {
                double q = TailCertificate.threshold(a, tail);
                if (q <= MARGIN) {
                    continue;
                }
                double low = Math.log10(q);
                double high = Math.log10(MARGIN);
                for (int i = 0; i <= THRESHOLD_CHECK_POINTS; i++) {
                    double d = Math.pow(10.0, low + (high - low) * i / THRESHOLD_CHECK_POINTS);
                    double p = (tail == Metalog.Tail.LOWER) ? d : 1.0 - d;
                    assertTrue(m.quantileDensity(p) > 0.0,
                            "threshold " + q + " at the " + tail + " tail, slope at p=" + p
                                    + " is " + m.quantileDensity(p) + ": " + Arrays.toString(a));
                }
            }
            checked++;
        }
    }
}
