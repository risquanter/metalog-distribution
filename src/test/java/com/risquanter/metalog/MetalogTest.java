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
 * Unit tests for the Metalog distribution implementation.
 *
 * <p>
 * We cover:
 * <ul>
 *   <li>Core quantile & PDF logic via the analytic logit example (a0=0, a1=1).</li>
 *   <li>Positive-PDF property over (0,1) for both
 *       <ul>
 *         <li>Option A: known analytic coefficients</li>
 *         <li>Option B: coefficients obtained from MetalogFitter</li>
 *       </ul>
 *   </li>
 * </ul>
 * </p>
 */
class MetalogTest {

    /**
     * Test 1: Quantile & PDF for the simple logit metalog:
     *   M(p) = ln(p / (1 - p)),  so a0=0, a1=1.
     * This verifies our basis-function assembly, sum, and derivative logic.
     */
    @Test
    void testQuantileAndPDF() {
        // Analytic logit coefficients: M(p) = a0 + a1*logit(p) = logit(p)
        double[] a = {0.0, 1.0};
        Metalog metalog = new Metalog(a);

        // Pick an interior probability to avoid singularities at p=0 or p=1
        double p = 0.3;

        // Expected quantile = logit(p) = ln(p / (1 - p))
        double expectedQuantile = Math.log(p / (1.0 - p));
        assertEquals(expectedQuantile,
                     metalog.quantile(p),
                     1e-12,
                     "Quantile function should reduce to logit(p)");

        // Expected PDF = p (1 - p)
        double expectedPdf = p * (1.0 - p);
        assertEquals(expectedPdf,
                     metalog.pdf(p),
                     1e-12,
                     "PDF should be p*(1-p) for the logit metalog");
    }

    /**
     * Test 2A (Option A): Verify PDF strictly > 0 using the analytic logit coefficients.
     *
     * Rationale:
     * For M(p) = logit(p), we know f(p) = p(1-p), which is positive on (0,1).
     * We sample p from 0.001 to 0.998 in steps of 0.001 to avoid endpoints.
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
     * Test 2B (Option B): Verify PDF strictly > 0 using coefficients fitted
     * from synthetic logit data via MetalogFitter.
     *
     * Rationale:
     * We generate a small set of known logit-quantile pairs at
     * p ∈ {0.1, 0.3, 0.5, 0.7, 0.9}, fit a 2-term metalog, then verify PDF positivity.
     */
    @Test
    void testPdfPositiveFittedCoefficients() {
        // 1) Synthetic probability grid and analytic logit quantiles
        double[] pVals = {0.1, 0.3, 0.5, 0.7, 0.9};
        double[] xVals = new double[pVals.length];
        for (int i = 0; i < pVals.length; i++) {
            xVals[i] = Math.log(pVals[i] / (1.0 - pVals[i]));  // logit(p)
        }

        // 2) Fit a 2-term metalog (should recover [a0≈0, a1≈1])
        Metalog metalog = QPFitter.with(pVals, xVals, 2).fit();

        for (double p = 0.001; p < 0.999; p += 0.001) {
            double pdf = metalog.pdf(p);
            assertTrue(pdf > 0.0,
                       String.format("Expected positive pdf at p=%.3f but got %.6f", p, pdf));
        }
    }
}
