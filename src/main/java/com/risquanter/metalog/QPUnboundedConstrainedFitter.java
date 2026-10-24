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
import java.util.TreeSet;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;


/**
 * QP-Unbounded Metalog Fitter
 *
 * Metalog Basis Functions
 * ------------------------
 * The quantile function Q(p) is represented as a linear combination of n basis
 * functions T_j(p):
 *
 * Q(p) = Σ_{j=0..n-1} a_j · T_j(p)
 *
 * where the T_j are the metalog basis functions, following Keelin (2016)
 * Eqn (6). Writing φ for (p − 0.5):
 * T₀(p) = 1
 * T₁(p) = logit(p) = ln(p/(1−p))
 * T₂(p) = φ·logit(p)
 * T₃(p) = φ
 * T₄(p) = φ²
 * T₅(p) = φ²·logit(p)
 * …thereafter alternating φ^k and φ^k·logit(p) for ascending k.
 *
 *
 * Monotonicity Constraint (dQ/dp ≥ ε)
 * -----------------------------------
 * Q(p) is enforced to be strictly increasing by requiring its derivative
 * on a grid of G points pₖ to satisfy:
 *
 * dQ/dp (pₖ) = Σ_j a_j · Tʹ_j(pₖ) ≥ ε
 *
 * where ε>0 is a small regularization constant (e.g. 1e−6). This turns the
 * monotonicity condition into G linear constraints in the QP:
 *
 * D[k]·a ≥ ε, D[k,j] = Tʹ_j(pₖ)
 *
 * Two more rows require the slope to be positive next to each end of the
 * unit interval, using the leading coefficient of dQ/dp there
 * ({@link Metalog#basisTailWeights}). Grid rows near the endpoints carry
 * coefficients of order 1/(p·(1−p)), up to 1e12, which the solver honours only
 * to relative precision; the tail rows state the same condition with
 * coefficients in [−1, 1].
 *
 * After the solve the slope is checked on a dense sweep of the grid's span.
 * Grid rows only sample the slope, and a fit the data do not admit can dip
 * between them; such a fit is refused with {@link MetalogInfeasibleException}.
 * In both tails, from the grid's ends to {@link TailCertificate#TAIL_END}, the
 * slope is then proved positive by {@link TailCertificate} rather than sampled.
 *
 *
 * Putting It All Together
 * -----------------------
 * The QP solved is:
 *
 * minimize ½‖Y·a − xData‖² (least-squares fit at data points)
 * subject to D·a ≥ ε (monotonicity on the grid)
 *            W·a ≥ ε (monotonicity in both tails)
 *
 * where:
 * • Y[i,j] = T_j(pData[i]) (basis at your observed quantiles)
 * • D[k,j] = Tʹ_j(gridP[k]) (derivatives on the enforce grid)
 * • W[t,j] = the tail weight of T_j at tail t
 *
 * No lower or upper bound constraints are applied in the unbounded version.
 */
class QPUnboundedConstrainedFitter {
    /**
     * The solver's own default iteration limit, read from ojAlgo 56.0.0's
     * {@code Optimisation.Options}. Passing it leaves the solver's behaviour
     * exactly as it is without the parameter.
     */
    private static final int SOLVER_DEFAULT_ITERATION_LIMIT = Integer.MAX_VALUE;

    /**
     * Uniform points in the post-solve slope check. 10 000 intervals resolve an
     * interior dip a hundredth of the interval wide with a hundred samples,
     * which is the narrowest dip a fit the data do not admit has been seen to
     * produce.
     */
    private static final int UNIFORM_CHECK_INTERVALS = 10_000;

    /** The largest distance from an endpoint at which the tail sampling starts. */
    private static final double TAIL_CHECK_START = 1e-2;

    /**
     * Tail points per decade in the post-solve slope check, spaced evenly in
     * the logarithm of the distance to the nearer endpoint. Twenty samples
     * every 0.115 in ln(1/δ); the narrowest far-tail dip a feasible
     * coefficient vector has been constructed to produce is about 0.15 wide
     * on that scale.
     */
    private static final int TAIL_CHECK_POINTS_PER_DECADE = 20;

    private final double[] pData, xData;
    private final int terms;
    private final double epsilon;
    private final double[] gridP;
    private final int iterationLimit;

    QPUnboundedConstrainedFitter(
        double[] pData,
        double[] xData,
        int terms,
        double epsilon,
        double[] gridP
    ) {
        this(pData, xData, terms, epsilon, gridP, SOLVER_DEFAULT_ITERATION_LIMIT);
    }

    QPUnboundedConstrainedFitter(
        double[] pData,
        double[] xData,
        int terms,
        double epsilon,
        double[] gridP,
        int iterationLimit
    ) {
        if (pData.length != xData.length) {
            throw new IllegalArgumentException("pData and xData must match");
        }
        for (int i = 0; i < pData.length; i++) {
            for (int j = i + 1; j < pData.length; j++) {
                if (pData[i] == pData[j]) {
                    throw new IllegalArgumentException(
                            "pData must be distinct: pData[" + i + "] and pData[" + j
                                    + "] are both " + pData[i]);
                }
            }
        }
        if (terms > pData.length) {
            throw new IllegalArgumentException(
                    "terms must not exceed the number of data points: terms="
                            + terms + ", data points=" + pData.length);
        }
        Metalog.validateInputs(terms);
        Metalog.validateInputs(gridP);
        this.pData   = pData.clone();
        this.xData   = xData.clone();
        this.terms   = terms;
        this.epsilon = epsilon;
        this.gridP   = gridP.clone();
        this.iterationLimit = iterationLimit;
    }

    double[] fit() {
        int K = pData.length, n = terms, G = gridP.length;

        // 1) Y matrix (K×n)
        double[][] Y = new double[K][n];
        for (int i = 0; i < K; i++) {
            double[] T = Metalog.basisFunctions(pData[i], n);
            System.arraycopy(T, 0, Y[i], 0, n);
        }

        // 2) Build Q (n×n) and c (n) for ½ aᵀQa + cᵀa
        double[][] Q = new double[n][n];
        double[]   c = new double[n];
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < n; j++) {
                double yij = Y[i][j];
                c[j]    -= yij * xData[i];
                for (int k = 0; k < n; k++) {
                    Q[j][k] += yij * Y[i][k];
                }
            }
        }

        // 3) Derivative matrix D (G×n)
        double[][] D = new double[G][n];
        for (int k = 0; k < G; k++) {
            double[] dT = Metalog.basisDerivatives(gridP[k], n);
            System.arraycopy(dT, 0, D[k], 0, n);
        }

        // 4) Build ojAlgo model
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        Variable[] vars = new Variable[n];
        for (int j = 0; j < n; j++) {
            vars[j] = model.addVariable("a" + j);
        }

        // 5) Least‐squares objective
        Expression obj = model.addExpression("LS").weight(1.0);
        for (int j = 0; j < n; j++) {
            obj.set(vars[j], c[j]);
            for (int k = 0; k < n; k++) {
                obj.set(vars[j], vars[k], 0.5 * Q[j][k]);
            }
        }

        // 6) Monotonicity on the grid: D·a ≥ ε
        for (int k = 0; k < G; k++) {
            Expression con = model.addExpression("mono_" + k).lower(epsilon);
            for (int j = 0; j < n; j++) {
                con.set(vars[j], D[k][j]);
            }
        }

        // 7) Monotonicity in the tails: the leading term of dQ/dp at each end
        // must be positive. Grid rows cannot say this — at the endpoints their
        // coefficients reach 1e12 and the solver honours them only to relative
        // precision — so it is said exactly, with coefficients in [−1, 1].
        for (Metalog.Tail tail : Metalog.Tail.values()) {
            Expression con = model.addExpression("tail_" + tail).lower(epsilon);
            double[] w = Metalog.basisTailWeights(n, tail);
            for (int j = 0; j < n; j++) {
                con.set(vars[j], w[j]);
            }
        }

        // 8) Solve, and refuse a result the solver does not vouch for. A
        // non-optimal state says the solver stopped early, not that the data
        // admit no fit, so it is reported as a library-side failure.
        model.options.iterations_abort = iterationLimit;
        Optimisation.Result result = model.minimise();
        if (!result.getState().isOptimal()) {
            throw new IllegalStateException(
                    "the solver stopped before reaching an optimum: state "
                            + result.getState() + ", terms=" + n
                            + ", data points=" + K);
        }

        // 9) Coefficients
        double[] a = new double[n];
        for (int j = 0; j < n; j++) {
            a[j] = vars[j].getValue().doubleValue();
        }

        // 10) The grid rows only sample the slope; check it where the caller
        // can query, which is the span of the grid.
        Metalog fitted = new Metalog(a);
        for (double p : verificationGrid(gridP)) {
            double slope = fitted.quantileDensity(p);
            if (slope <= 0.0) {
                throw new MetalogInfeasibleException(n, K,
                        "dQ/dp = " + slope + " at p = " + p);
            }
        }

        // 11) Prove the slope positive in both tails, from the grid's ends to
        // TailCertificate.TAIL_END, where sampling alone cannot.
        double lo = Arrays.stream(gridP).min().orElseThrow();
        double hi = Arrays.stream(gridP).max().orElseThrow();
        switch (TailCertificate.certify(a, lo, hi)) {
            case TailCertificate.Verdict.Certified certified -> { }
            case TailCertificate.Verdict.Refuted r -> throw new MetalogInfeasibleException(n, K,
                    "dQ/dp = " + r.slope() + " at p = " + r.p());
            case TailCertificate.Verdict.Uncertified u -> throw new MetalogInfeasibleException(n, K,
                    "dQ/dp could not be proved positive on [" + u.p0() + ", " + u.p1() + "]");
        }
        return a;
    }

    /**
     * Where the fitted slope is checked after the solve: the span of the
     * constraint grid, sampled uniformly at {@link #UNIFORM_CHECK_INTERVALS}
     * intervals, plus {@link #TAIL_CHECK_POINTS_PER_DECADE} points per decade
     * from {@link #TAIL_CHECK_START} down to the grid's outermost end,
     * mirrored at the other end, where uniform spacing has no sample at all.
     */
    static double[] verificationGrid(double[] gridP) {
        double lo = Arrays.stream(gridP).min().orElseThrow();
        double hi = Arrays.stream(gridP).max().orElseThrow();
        TreeSet<Double> points = new TreeSet<>();
        for (int i = 0; i <= UNIFORM_CHECK_INTERVALS; i++) {
            points.add(lo + (hi - lo) * i / (double) UNIFORM_CHECK_INTERVALS);
        }
        double outermost = Math.min(lo, 1.0 - hi);
        for (double decade = TAIL_CHECK_START; decade >= outermost; decade /= 10.0) {
            for (int i = 0; i < TAIL_CHECK_POINTS_PER_DECADE; i++) {
                double p = decade * Math.pow(10.0, i / (double) TAIL_CHECK_POINTS_PER_DECADE);
                if (p >= lo) {
                    points.add(p);
                }
                if (1.0 - p <= hi) {
                    points.add(1.0 - p);
                }
            }
        }
        return points.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
