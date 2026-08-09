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

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Expression;
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
 * where the T_j are the “metalog” basis polynomials in the probability p.
 * Common choices (n≥3) include:
 * T₀(p) = 1
 * T₁(p) = logit(p) = ln(p/(1−p))
 * T₂(p) = (p − 0.5)·logit(p)
 * T₃(p) = (p − 0.5)^2
 * T₄(p) = (p − 0.5)^2·logit(p)
 * …and higher-order combinations for added flexibility.
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
 *
 * Putting It All Together
 * -----------------------
 * The QP solved is:
 *
 * minimize ½‖Y·a − xData‖² (least-squares fit at data points)
 * subject to D·a ≥ ε (monotonicity)
 *
 * where:
 * • Y[i,j] = T_j(pData[i]) (basis at your observed quantiles)
 * • D[k,j] = Tʹ_j(gridP[k]) (derivatives on the enforce grid)
 *
 * No lower or upper bound constraints are applied in the unbounded version.
 */
class QPUnboundedConstrainedFitter {
    private final double[] pData, xData;
    private final int terms;
    private final double epsilon;
    private final double[] gridP;

    QPUnboundedConstrainedFitter(
        double[] pData,
        double[] xData,
        int terms,
        double epsilon,
        double[] gridP
    ) {
        if (pData.length != xData.length) {
            throw new IllegalArgumentException("pData and xData must match");
        }
        Metalog.validateInputs(terms);
        Metalog.validateInputs(gridP);
        this.pData   = pData.clone();
        this.xData   = xData.clone();
        this.terms   = terms;
        this.epsilon = epsilon;
        this.gridP   = gridP.clone();
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

        // 6) Monotonicity: D·a ≥ ε
        for (int k = 0; k < G; k++) {
            Expression con = model.addExpression("mono_" + k).lower(epsilon);
            for (int j = 0; j < n; j++) {
                con.set(vars[j], D[k][j]);
            }
        }

        // 7) Solve
        model.minimise();

        // 8) Return coefficients
        double[] a = new double[n];
        for (int j = 0; j < n; j++) {
            a[j] = vars[j].getValue().doubleValue();
        }
        return a;
    }
}
