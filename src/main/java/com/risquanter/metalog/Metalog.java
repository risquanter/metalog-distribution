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
    /**
     * Upper bound on the number of terms. Chosen by this library, not fixed by
     * Keelin (2016): the paper gives three to five terms for expert
     * elicitation and eight to twelve for data exploration, and no maximum.
     * Twenty leaves headroom above both while keeping the highest power of
     * {@code p − 0.5}, whose magnitude falls off as {@code 0.5^(terms/2)},
     * within a range where the least-squares normal matrix stays workable.
     */
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
     * Evaluates the probability density function (PDF) at the quantile of a
     * given percentile {@code p}: the density of the distribution at
     * {@code quantile(p)}, which is the reciprocal of the quantile function's
     * slope there.
     *
     * @param p the percentile in (0,1)
     * @return the PDF value at {@code quantile(p)}
     * @throws IllegalArgumentException if {@code p} is outside (0,1)
     */
    public double pdf(double p) {
        return 1.0 / quantileDensity(p);
    }

    /**
     * The derivative of the quantile function, {@code dQ/dp}, at {@code p}.
     * The density of the distribution at {@code quantile(p)} is its reciprocal.
     *
     * @param p the percentile in (0,1)
     * @return the slope of the quantile function at {@code p}
     * @throws IllegalArgumentException if {@code p} is outside (0,1)
     */
    double quantileDensity(double p) {
        validateInputs(p);
        double[] dT = basisDerivatives(p, terms);
        double dMdp = 0.0;
        for (int j = 0; j < terms; j++) {
            dMdp += a[j] * dT[j];
        }
        return dMdp;
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

        // T2 = φ · logit(p)
        if (terms > 2) {
            T[2] = φ * logit;
        }

        // T3 = φ. Keelin's n = 4 term is the one index that carries a power of
        // φ without a logit factor, so it sits outside the alternation below.
        if (terms > 3) {
            T[3] = φ;
        }

        // j ≥ 4: even = φ^(j/2), odd = φ^((j-1)/2) * logit
        for (int j = 4; j < terms; j++) {
            if ((j & 1) == 0) {
                // even j ≥ 4: pure power
                int power = j / 2;
                T[j] = FastMath.pow(φ, power);
            } else {
                // odd j ≥ 5: power × logit
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

        // dT3/dp = derivative of φ = 1
        if (terms > 3) {
            d[3] = 1.0;
        }

        // j ≥ 4: even = d/dp[φ^(j/2)] ; odd = d/dp[φ^((j-1)/2)*logit]
        for (int j = 4; j < terms; j++) {
            if ((j & 1) == 0) {
                // even j ≥ 4
                int power = j / 2;
                // derivative of φ^power = power * φ^(power-1)
                d[j] = power * FastMath.pow(φ, power - 1);
            } else {
                // odd j ≥ 5
                int power = (j - 1) / 2;
                double φk = FastMath.pow(φ, power);
                double φkm1 = FastMath.pow(φ, power - 1);
                // d[φ^power*logit] = power·φ^(power-1)·logit + φ^power·inv
                d[j] = power * φkm1 * logit + φk * inv;
            }
        }
        return d;
    }

    /** The two ends of the unit interval, each with its value of φ = p − 0.5. */
    enum Tail {
        LOWER(-0.5),
        UPPER(0.5);

        final double φ;

        Tail(double φ) {
            this.φ = φ;
        }
    }

    /**
     * The weight each basis derivative puts on {@code 1 / (p·(1−p))} at one end
     * of the unit interval.
     * <p>
     * A logit-bearing basis function {@code φ^k · logit(p)} has derivative
     * {@code k·φ^(k−1)·logit(p) + φ^k / (p·(1−p))}. The second part grows
     * without bound at both ends and every other part of every basis derivative
     * grows no faster than {@code logit}, so next to an endpoint the sign of the
     * quantile function's slope is the sign of {@code Σ a_j·w_j} with {@code w}
     * the array returned here, provided that sum is not zero. Pure-power basis
     * functions contribute zero.
     *
     * @param terms the number of terms
     * @param tail  which end of the interval
     * @return one weight per basis function
     * @throws IllegalArgumentException if {@code terms} is invalid
     */
    static double[] basisTailWeights(int terms, Tail tail) {
        validateInputs(terms);
        double[] w = new double[terms];
        if (terms > 1) {
            w[1] = 1.0;
        }
        if (terms > 2) {
            w[2] = tail.φ;
        }
        for (int j = 5; j < terms; j += 2) {
            w[j] = FastMath.pow(tail.φ, (j - 1) / 2);
        }
        return w;
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
