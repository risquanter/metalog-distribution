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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Proves the slope of a metalog quantile function positive in both tails.
 * <p>
 * With φ = p − 0.5, inv = 1/(p(1−p)) and logit = ln(p/(1−p)), every basis
 * derivative is a polynomial in φ times inv, logit or 1, so the slope is
 * <pre>
 * s(p) = A(φ)·inv + B(φ)·logit + D(φ),   B = A′
 * </pre>
 * where A collects the logit-bearing coefficients and D the pure powers.
 * A(∓½) is the coefficient of inv at each end, which the fitter's tail rows
 * force positive. Two proofs then cover each tail:
 * <ul>
 * <li>{@link #threshold}: below a probability q*, bounds on A, B and D alone
 * show the inv term wins, so s &gt; 0 on all of (0, q*].</li>
 * <li>From q* to {@link #TAIL_END}, each interval is accepted when the slope
 * at its ends, less what s′ can lose across it, stays positive; an interval
 * that fails is split at its geometric midpoint and retried.</li>
 * </ul>
 * For a polynomial P with coefficients c_i, sup|P| on [−½, ½] is bounded by
 * ‖P‖ = Σ|c_i|·0.5^i, and on a sub-interval of width w by the larger endpoint
 * value plus ‖P″‖·w²/8, the linear-interpolation error.
 */
final class TailCertificate {

    /** Where the certified tails end and the sampled body begins. */
    static final double TAIL_END = 1e-2;

    /**
     * Relative margin by which h(q) must stay below the tail coefficient, so
     * rounding in evaluating the closed forms cannot turn a bound into a
     * false certificate.
     */
    private static final double ROUNDING_MARGIN = 1e-9;

    /** Bisection depth at which an interval that is still not certified is reported. */
    private static final int MAX_DEPTH = 60;

    /** Total intervals examined across both tails before certification stops. */
    private static final int INTERVAL_BUDGET = 200_000;

    /** Smallest probability the threshold search considers, near the smallest normal double. */
    private static final double THRESHOLD_SEARCH_FLOOR = 1e-300;

    /** Bisection steps on ln q; sixty halvings of ln(1e-302) resolve q to a relative 1e-15. */
    private static final int THRESHOLD_SEARCH_STEPS = 60;

    private TailCertificate() {
    }

    /** The outcome of certifying both tails of one coefficient vector. */
    sealed interface Verdict {
        /** dQ/dp &gt; 0 is proved on both tails. */
        record Certified() implements Verdict { }

        /** dQ/dp ≤ 0 at {@code p}. */
        record Refuted(double p, double slope) implements Verdict { }

        /**
         * dQ/dp is positive at every point evaluated in {@code [p0, p1]} but
         * could not be proved positive throughout it within the depth or budget.
         */
        record Uncertified(double p0, double p1) implements Verdict { }
    }

    /**
     * Certifies dQ/dp &gt; 0 on {@code [lo, TAIL_END]} and {@code [1 − TAIL_END, hi]}
     * for the metalog with coefficients {@code a}.
     */
    static Verdict certify(double[] a, double lo, double hi) {
        Metalog metalog = new Metalog(a);
        double[] tail = tailPolynomial(a);
        double[] slopeOfTail = derivative(tail);
        double[] purePower = purePowerPolynomial(a);
        Bounds bounds = new Bounds(metalog, tail, slopeOfTail,
                norm(derivative(slopeOfTail)), norm(derivative(purePower)));
        int[] budget = {INTERVAL_BUDGET};

        double lowerStart = Math.max(lo, threshold(a, Metalog.Tail.LOWER));
        if (lowerStart < TAIL_END) {
            Verdict v = certifyInterval(bounds, lowerStart, TAIL_END, budget);
            if (!(v instanceof Verdict.Certified)) {
                return v;
            }
        }
        double upperStart = Math.max(1.0 - hi, threshold(a, Metalog.Tail.UPPER));
        if (upperStart < TAIL_END) {
            return certifyInterval(bounds, 1.0 - TAIL_END, 1.0 - upperStart, budget);
        }
        return new Verdict.Certified();
    }

    /** Method 1: the largest q ≤ TAIL_END such that dQ/dp &gt; 0 on (0, q] at the given tail; 0 if none. */
    static double threshold(double[] a, Metalog.Tail tail) {
        double[] polynomial = tailPolynomial(a);
        double coefficient = evaluate(polynomial, tail.φ) * (1.0 - ROUNDING_MARGIN);
        if (!(coefficient > 0.0)) {
            return 0.0;
        }
        double slopeBound = norm(derivative(polynomial));
        double purePowerBound = norm(purePowerPolynomial(a));
        if (h(TAIL_END, slopeBound, purePowerBound) < coefficient) {
            return TAIL_END;
        }
        if (h(THRESHOLD_SEARCH_FLOOR, slopeBound, purePowerBound) >= coefficient) {
            return 0.0;
        }
        double below = Math.log(THRESHOLD_SEARCH_FLOOR);
        double above = Math.log(TAIL_END);
        for (int i = 0; i < THRESHOLD_SEARCH_STEPS; i++) {
            double mid = 0.5 * (below + above);
            if (h(Math.exp(mid), slopeBound, purePowerBound) < coefficient) {
                below = mid;
            } else {
                above = mid;
            }
        }
        return Math.exp(below);
    }

    /** A(φ), as coefficients of ascending powers of φ. */
    static double[] tailPolynomial(double[] a) {
        double[] c = new double[a.length];
        if (a.length > 1) {
            c[0] += a[1];
        }
        if (a.length > 2) {
            c[1] += a[2];
        }
        for (int j = 5; j < a.length; j += 2) {
            c[(j - 1) / 2] += a[j];
        }
        return c;
    }

    /** D(φ), as coefficients of ascending powers of φ. */
    static double[] purePowerPolynomial(double[] a) {
        double[] c = new double[a.length];
        if (a.length > 3) {
            c[0] += a[3];
        }
        for (int j = 4; j < a.length; j += 2) {
            int k = j / 2;
            c[k - 1] += a[j] * k;
        }
        return c;
    }

    /**
     * The upper bound h(q) on everything in s(q)·q·(1−q) other than the tail
     * coefficient's own contribution; s &gt; 0 wherever h(q) is below that
     * coefficient.
     */
    private static double h(double q, double slopeBound, double purePowerBound) {
        return slopeBound * q
                + q * (1.0 - q) * (slopeBound * Math.log(1.0 / q) + purePowerBound);
    }

    /** What the interval certificate needs about one coefficient vector. */
    private record Bounds(
            Metalog metalog,
            double[] tail,
            double[] slopeOfTail,
            double secondDerivativeOfTailNorm,
            double purePowerDerivativeNorm) {
    }

    /** Method 2: proves s &gt; 0 on [p0, p1] by interval splitting, sharing the budget across calls. */
    private static Verdict certifyInterval(Bounds b, double p0, double p1, int[] budget) {
        Deque<double[]> pending = new ArrayDeque<>();
        pending.push(new double[] {p0, p1, 0});
        while (!pending.isEmpty()) {
            double[] interval = pending.pop();
            double x0 = interval[0];
            double x1 = interval[1];
            int depth = (int) interval[2];
            if (--budget[0] < 0) {
                return new Verdict.Uncertified(x0, x1);
            }
            double s0 = b.metalog().quantileDensity(x0);
            if (s0 <= 0.0) {
                return new Verdict.Refuted(x0, s0);
            }
            double s1 = b.metalog().quantileDensity(x1);
            if (s1 <= 0.0) {
                return new Verdict.Refuted(x1, s1);
            }
            if (s0 + s1 > slopeChangeBound(b, x0, x1) * (x1 - x0) * (1.0 + ROUNDING_MARGIN)) {
                continue;
            }
            if (depth >= MAX_DEPTH) {
                return new Verdict.Uncertified(x0, x1);
            }
            double mid = (x1 <= 0.5)
                    ? Math.sqrt(x0 * x1)
                    : 1.0 - Math.sqrt((1.0 - x0) * (1.0 - x1));
            pending.push(new double[] {mid, x1, depth + 1});
            pending.push(new double[] {x0, mid, depth + 1});
        }
        return new Verdict.Certified();
    }

    /**
     * An upper bound on |s′| over [x0, x1], from
     * s′ = 2·B·inv + 2·φ·A·inv² + B′·logit + D′, with inv, |logit| and |φ|
     * at their maxima, which on an interval inside one half of (0,1) are at an
     * endpoint.
     */
    private static double slopeChangeBound(Bounds b, double x0, double x1) {
        double φ0 = x0 - 0.5;
        double φ1 = x1 - 0.5;
        double tailBound = intervalBound(b.tail(), φ0, φ1);
        double slopeOfTailBound = intervalBound(b.slopeOfTail(), φ0, φ1);
        double inv = Math.max(1.0 / (x0 * (1.0 - x0)), 1.0 / (x1 * (1.0 - x1)));
        double logit = Math.max(Math.abs(Math.log(x0 / (1.0 - x0))), Math.abs(Math.log(x1 / (1.0 - x1))));
        double φ = Math.max(Math.abs(φ0), Math.abs(φ1));
        return 2.0 * slopeOfTailBound * inv
                + 2.0 * φ * tailBound * inv * inv
                + b.secondDerivativeOfTailNorm() * logit
                + b.purePowerDerivativeNorm();
    }

    /** sup |P| on [φ0, φ1]: the larger endpoint value plus the interpolation error ‖P″‖·w²/8. */
    private static double intervalBound(double[] c, double φ0, double φ1) {
        double w = φ1 - φ0;
        return Math.max(Math.abs(evaluate(c, φ0)), Math.abs(evaluate(c, φ1)))
                + norm(derivative(derivative(c))) * w * w / 8.0;
    }

    /** Bound on sup |P| over [−½, ½]: Σ |c_i|·0.5^i. */
    private static double norm(double[] c) {
        double sum = 0.0;
        double half = 1.0;
        for (double ci : c) {
            sum += Math.abs(ci) * half;
            half *= 0.5;
        }
        return sum;
    }

    private static double[] derivative(double[] c) {
        double[] d = new double[Math.max(1, c.length - 1)];
        for (int i = 1; i < c.length; i++) {
            d[i - 1] = i * c[i];
        }
        return d;
    }

    private static double evaluate(double[] c, double φ) {
        double sum = 0.0;
        for (int i = c.length - 1; i >= 0; i--) {
            sum = sum * φ + c[i];
        }
        return sum;
    }
}
