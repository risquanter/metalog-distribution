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

import org.apache.commons.math3.util.FastMath;

/**
 * Implements the Metalog distribution as described in Keelin (2016),
 * supporting quantile and PDF evaluation via basis function expansion.
 * <p>
 * This class provides:
 * <ul>
 * <li>Quantile evaluation via {@link #quantile(double)}</li>
 * <li>PDF evaluation via {@link #pdf(double)}</li>
 * <li>Explicit basis functions and their derivatives</li>
 * </ul>
 * <p>
 * The Metalog distribution is parameterized by a coefficient vector {@code a}
 * and supports up to {@value #MAX_TERMS} terms. Basis functions follow
 * Keelin's Eqn (6), using logit and centered percentiles.
 *
 * @author Daniel Agota &lt;danago@risquanter.com&gt;
 *
 * Based on: Keelin, T.W. (2016). "The Metalog Distributions."
 * Decision Analysis, Vol. 13, No. 4, pp. 243-277.
 * Available at: https://doi.org/10.1287/deca.2016.0338
 */
public class Metalog {
    private static final int MAX_TERMS = 20;

    private final double[] a; // coefficients a₀…a_{terms-1}
    private final int terms; // number of terms in use

    /**
     * Constructs a Metalog distribution with the given coefficient vector.
     *
     * @param coefficients the expansion coefficients {@code a₀…a_{n−1}}
     * @throws IllegalArgumentException if {@code coefficients} is null or has
     *                                  invalid length
     */
    public Metalog(double[] coefficients) {
        if (coefficients == null) {
            throw new IllegalArgumentException("Coefficient array must not be null");
        }
        if (coefficients.length < 2 || coefficients.length > MAX_TERMS) {
            throw new IllegalArgumentException(
                    "Number of terms must be between 2 and " + MAX_TERMS
                            + ", got " + coefficients.length);
        }
        this.a = coefficients.clone();
        this.terms = a.length;
    }

    /**
     * Evaluates the quantile function Q(p) for a given percentile {@code p}.
     *
     * @param p the percentile in (0,1)
     * @return the corresponding quantile value
     * @throws IllegalArgumentException if {@code p} is outside (0,1)
     */
    public double quantile(double p) {
        if (p <= 0.0 || p >= 1.0) {
            throw new IllegalArgumentException("p must be in (0,1), got " + p);
        }
        double[] T = basisFunctions(p, terms);
        double q = 0.0;
        for (int j = 0; j < terms; j++) {
            q += a[j] * T[j];
        }
        return q;
    }

    /**
     * Evaluates the probability density function (PDF) at a given percentile
     * {@code p}.
     *
     * @param p the percentile in (0,1)
     * @return the PDF value at {@code p}
     * @throws IllegalArgumentException if {@code p} is outside (0,1)
     */
    public double pdf(double p) {
        if (p <= 0.0 || p >= 1.0) {
            throw new IllegalArgumentException("p must be in (0,1), got " + p);
        }
        double[] dT = basisDerivatives(p, terms);
        double dMdp = 0.0;
        for (int j = 0; j < terms; j++) {
            dMdp += a[j] * dT[j];
        }
        return 1.0 / dMdp;
    }

    /**
     * Computes the basis functions T₀…T_{terms−1} for a given {@code p}.
     *
     * @param p     the percentile in (0,1)
     * @param terms the number of terms to compute
     * @return an array of basis function values
     * @throws IllegalArgumentException if {@code p} or {@code terms} are invalid
     */
    public static double[] basisFunctions(double p, int terms) {
        validateInputs(terms);
        validateInputs(p);

        double φ = p - 0.5;
        double logit = FastMath.log(p / (1.0 - p));
        double[] T = new double[terms];

        // T0 = 1
        T[0] = 1.0;

        // T1 = logit(p)
        if (terms > 1) {
            T[1] = logit;
        }

        // T2 = φ * logit(p) ← special case for first “power×logit”
        if (terms > 2) {
            T[2] = φ * logit;
        }

        // j ≥ 3: even = φ^(j/2), odd = φ^((j-1)/2) * logit
        for (int j = 3; j < terms; j++) {
            if ((j & 1) == 0) {
                // even j ≥ 4: pure power
                int power = j / 2;
                T[j] = FastMath.pow(φ, power);
            } else {
                // odd j ≥ 3: power × logit
                int power = (j - 1) / 2;
                T[j] = FastMath.pow(φ, power) * logit;
            }
        }
        return T;
    }

    /**
     * Computes the derivatives of the basis functions with respect to {@code p}.
     *
     * @param p     the percentile in (0,1)
     * @param terms the number of terms to compute
     * @return an array of basis function derivatives
     * @throws IllegalArgumentException if {@code p} or {@code terms} are invalid
     */
    public static double[] basisDerivatives(double p, int terms) {
        validateInputs(terms);
        validateInputs(p);

        double φ = p - 0.5;
        double logit = FastMath.log(p / (1.0 - p));
        double inv = 1.0 / (p * (1.0 - p));
        double[] d = new double[terms];

        // dT0/dp = 0
        d[0] = 0.0;

        // dT1/dp = 1/[p(1-p)]
        if (terms > 1) {
            d[1] = inv;
        }

        // dT2/dp = derivative of φ*logit = 1*logit + φ*inv
        if (terms > 2) {
            d[2] = logit + φ * inv;
        }

        // j ≥ 3: even = d/dp[φ^(j/2)] ; odd = d/dp[φ^((j-1)/2)*logit]
        for (int j = 3; j < terms; j++) {
            if ((j & 1) == 0) {
                // even j ≥ 4
                int power = j / 2;
                // derivative of φ^power = power * φ^(power-1)
                d[j] = power * FastMath.pow(φ, power - 1);
            } else {
                // odd j ≥ 3
                int power = (j - 1) / 2;
                double φk = FastMath.pow(φ, power);
                double φkm1 = FastMath.pow(φ, power - 1);
                // d[φ^power*logit] = power·φ^(power-1)·logit + φ^power·inv
                d[j] = power * φkm1 * logit + φk * inv;
            }
        }
        return d;
    }

    /**
     * Validates input parameters for percentile / probabilities.
     *
     * @param p the percentile
     * @throws IllegalArgumentException if percentile or probability inputs are out
     *                                  of bounds
     */
    public static void validateInputs(double p) {
        if (p <= 0.0 || p >= 1.0) {
            throw new IllegalArgumentException("p must be in (0,1), got " + p);
        }
    }

    /**
     * Validates term count.
     *
     * @param terms the number of terms
     * @throws IllegalArgumentException if term count is out of bounds
     */
    public static void validateInputs(int terms) {
        if (terms < 2 || terms > MAX_TERMS) {
            throw new IllegalArgumentException(
                    "terms must be in [2," + MAX_TERMS + "], got " + terms);
        }
    }

    /**
     * Validates an array of percentile/probability values.
     *
     * @param p array of percentiles/probabilities to validate
     * @throws IllegalArgumentException if any value is out of bounds
     */
    public static void validateInputs(double[] p) {
        for (double v : p) {
            validateInputs(v);
        }
    }
}
