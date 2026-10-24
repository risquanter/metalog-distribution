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


import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Metalog}: quantile and density evaluation, and the
 * basis functions and their derivatives checked against Keelin (2016) Eqn (6).
 */
class MetalogTest {

    /** Upper bound on the number of terms, mirroring {@code Metalog.MAX_TERMS}. */
    private static final int MAX_TERMS = 20;

    /**
     * Percentiles at which basis values are compared: spread over (0,1), both
     * sides of the median, and off any point where φ = p − 0.5 has a special
     * value.
     */
    private static final double[] SAMPLE_P = {0.05, 0.17, 0.3, 0.42, 0.5, 0.61, 0.74, 0.88, 0.95};

    /**
     * Tolerance for comparing two evaluations of the same closed form. Both
     * sides do the same few floating-point operations, so they agree to within
     * a few units of the last place on values of order one.
     */
    private static final double CLOSED_FORM_TOLERANCE = 1e-12;

    /**
     * Step for the central-difference derivative check. Truncation error grows
     * as the step squared and rounding error as machine epsilon over the step;
     * at 1e-7 both stay below {@link #FINITE_DIFFERENCE_TOLERANCE} for these
     * functions on {@link #SAMPLE_P}.
     */
    private static final double FINITE_DIFFERENCE_STEP = 1e-7;

    /** Relative tolerance for the central-difference derivative check. */
    private static final double FINITE_DIFFERENCE_TOLERANCE = 1e-5;

    /**
     * Percentile distance from an endpoint at which the tail weights are
     * checked. Close enough that the subleading part of each derivative, of
     * order {@code p·logit(p)}, is about 2e-8 there.
     */
    private static final double TAIL_CHECK_DISTANCE = 1e-9;

    /**
     * Tolerance for the tail-weight check: fifty times the subleading part at
     * {@link #TAIL_CHECK_DISTANCE}, so it pins the leading coefficient without
     * depending on the subleading one.
     */
    private static final double TAIL_WEIGHT_TOLERANCE = 1e-6;

    /**
     * Keelin (2016) Eqn (6) in 0-based indexing, written independently of the
     * library: 1, logit, φ·logit, φ, then φ^k and φ^k·logit for k = 2, 3, …
     */
    private static double[] keelinBasis(double p, int terms) {
        double φ = p - 0.5;
        double logit = Math.log(p / (1.0 - p));
        double[] t = new double[terms];
        for (int j = 0; j < terms; j++) {
            t[j] = switch (j) {
                case 0 -> 1.0;
                case 1 -> logit;
                case 2 -> φ * logit;
                case 3 -> φ;
                default -> (j % 2 == 0)
                        ? Math.pow(φ, j / 2)
                        : Math.pow(φ, (j - 1) / 2) * logit;
            };
        }
        return t;
    }

    /** The derivatives of {@link #keelinBasis} with respect to p, by hand. */
    private static double[] keelinDerivatives(double p, int terms) {
        double φ = p - 0.5;
        double logit = Math.log(p / (1.0 - p));
        double inv = 1.0 / (p * (1.0 - p));
        double[] d = new double[terms];
        for (int j = 0; j < terms; j++) {
            if (j <= 3) {
                d[j] = switch (j) {
                    case 0 -> 0.0;
                    case 1 -> inv;
                    case 2 -> logit + φ * inv;
                    default -> 1.0;
                };
            } else if (j % 2 == 0) {
                int k = j / 2;
                d[j] = k * Math.pow(φ, k - 1);
            } else {
                int k = (j - 1) / 2;
                d[j] = k * Math.pow(φ, k - 1) * logit + Math.pow(φ, k) * inv;
            }
        }
        return d;
    }

    /**
     * Quantile and density of the logistic metalog, Q(p) = logit(p), whose
     * density at Q(p) is p·(1−p).
     */
    @Test
    void testQuantileAndPDF() {
        double[] a = {0.0, 1.0};
        Metalog metalog = new Metalog(a);

        // Interior probability, away from the singularities at 0 and 1
        double p = 0.3;

        double expectedQuantile = Math.log(p / (1.0 - p));
        assertEquals(expectedQuantile,
                     metalog.quantile(p),
                     CLOSED_FORM_TOLERANCE,
                     "Quantile function should reduce to logit(p)");

        double expectedPdf = p * (1.0 - p);
        assertEquals(expectedPdf,
                     metalog.pdf(p),
                     CLOSED_FORM_TOLERANCE,
                     "PDF should be p*(1-p) for the logit metalog");
    }

    /**
     * The logistic metalog's density p·(1−p) is positive at every sampled
     * percentile from 0.001 to 0.998.
     */
    @Test
    void testPdfPositiveAnalyticCoefficients() {
        double[] analyticCoefficients = {0.0, 1.0};
        Metalog metalog = new Metalog(analyticCoefficients);

        for (double p = 0.001; p < 0.999; p += 0.001) {
            double pdf = metalog.pdf(p);
            assertTrue(pdf > 0.0,
                       String.format("Expected positive pdf at p=%.3f but got %.6f", p, pdf));
        }
    }

    /**
     * A two-term fit to exact logistic quantiles at five percentiles has a
     * positive density at every sampled percentile.
     */
    @Test
    void testPdfPositiveFittedCoefficients() {
        double[] pVals = {0.1, 0.3, 0.5, 0.7, 0.9};
        double[] xVals = new double[pVals.length];
        for (int i = 0; i < pVals.length; i++) {
            xVals[i] = Math.log(pVals[i] / (1.0 - pVals[i]));
        }

        Metalog metalog = QPFitter.with(pVals, xVals, 2).fit();

        for (double p = 0.001; p < 0.999; p += 0.001) {
            double pdf = metalog.pdf(p);
            assertTrue(pdf > 0.0,
                       String.format("Expected positive pdf at p=%.3f but got %.6f", p, pdf));
        }
    }

    @Test
    void basisMatchesKeelinEquationSix() {
        for (int terms = 2; terms <= MAX_TERMS; terms++) {
            for (double p : SAMPLE_P) {
                double[] expected = keelinBasis(p, terms);
                double[] actual = Metalog.basisFunctions(p, terms);
                for (int j = 0; j < terms; j++) {
                    assertEquals(expected[j], actual[j], CLOSED_FORM_TOLERANCE,
                            "basis index " + j + " at p=" + p + ", terms=" + terms);
                }
            }
        }
    }

    @Test
    void derivativesMatchKeelinEquationSix() {
        for (int terms = 2; terms <= MAX_TERMS; terms++) {
            for (double p : SAMPLE_P) {
                double[] expected = keelinDerivatives(p, terms);
                double[] actual = Metalog.basisDerivatives(p, terms);
                for (int j = 0; j < terms; j++) {
                    assertEquals(expected[j], actual[j], CLOSED_FORM_TOLERANCE,
                            "derivative index " + j + " at p=" + p + ", terms=" + terms);
                }
            }
        }
    }

    @Test
    void basisColumnsAreDistinct() {
        for (int terms = 2; terms <= MAX_TERMS; terms++) {
            for (int i = 0; i < terms; i++) {
                for (int j = i + 1; j < terms; j++) {
                    assertFalse(agreeEverywhere(terms, i, j, false),
                            "basis columns " + i + " and " + j + " are equal, terms=" + terms);
                    assertFalse(agreeEverywhere(terms, i, j, true),
                            "derivative columns " + i + " and " + j + " are equal, terms=" + terms);
                }
            }
        }
    }

    private static boolean agreeEverywhere(int terms, int i, int j, boolean derivatives) {
        for (double p : SAMPLE_P) {
            double[] v = derivatives
                    ? Metalog.basisDerivatives(p, terms)
                    : Metalog.basisFunctions(p, terms);
            if (Math.abs(v[i] - v[j]) > CLOSED_FORM_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    @Test
    void derivativesAgreeWithFiniteDifferences() {
        double h = FINITE_DIFFERENCE_STEP;
        for (int terms = 2; terms <= MAX_TERMS; terms++) {
            for (double p : SAMPLE_P) {
                double[] up = Metalog.basisFunctions(p + h, terms);
                double[] down = Metalog.basisFunctions(p - h, terms);
                double[] analytic = Metalog.basisDerivatives(p, terms);
                for (int j = 0; j < terms; j++) {
                    double numeric = (up[j] - down[j]) / (2 * h);
                    assertEquals(numeric, analytic[j],
                            FINITE_DIFFERENCE_TOLERANCE * Math.max(1.0, Math.abs(numeric)),
                            "derivative index " + j + " at p=" + p + ", terms=" + terms);
                }
            }
        }
    }

    @Test
    void basisTailWeightsAreTheDerivativesLeadingCoefficients() {
        for (int terms = 2; terms <= MAX_TERMS; terms++) {
            for (Metalog.Tail tail : Metalog.Tail.values()) {
                double p = switch (tail) {
                    case LOWER -> TAIL_CHECK_DISTANCE;
                    case UPPER -> 1.0 - TAIL_CHECK_DISTANCE;
                };
                double scale = p * (1.0 - p);
                double[] d = Metalog.basisDerivatives(p, terms);
                double[] w = Metalog.basisTailWeights(terms, tail);
                for (int j = 0; j < terms; j++) {
                    assertEquals(w[j], d[j] * scale, TAIL_WEIGHT_TOLERANCE,
                            "tail weight index " + j + " at the " + tail + " tail, terms=" + terms);
                }
            }
        }
    }
}
