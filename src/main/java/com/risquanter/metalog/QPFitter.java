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

import java.util.stream.IntStream;

/**
 * Fluent Metalog QP-fitter that fits a Metalog distribution to quantile data
 * using monotonicity-constrained quadratic programming (QP) in transformed
 * z-space.
 * <p>
 * Supports all four boundedness modes via Keelin's transforms:
 * <ul>
 * <li>Unbounded: z = x</li>
 * <li>Lower-bounded: z = ln(x − L)</li>
 * <li>Upper-bounded: z = −ln(U − x)</li>
 * <li>Fully-bounded: z = logit((x − L)/(U − L))</li>
 * </ul>
 * <p>
 * The resulting Metalog is wrapped to invert the transform at query time.
 * Internally always solves a pure monotonicity-only QP on z, then wraps
 * the raw unbounded Metalog to invert the transform in quantile(p).
 *
 * @author Daniel Agota &lt;danago@risquanter.com&gt;
 */
public class QPFitter {

  /**
   * Private constructor to prevent direct instantiation.
   * Use {@link #with(double[], double[], int)} to create instances.
   */
  private QPFitter() {
  }

  /**
   * Begins a fluent fitting session for the given percentiles and quantiles.
   *
   * @param p     the percentiles (must be in (0,1))
   * @param x     the corresponding quantile values
   * @param terms the number of terms to fit
   * @return a fluent {@link Options} builder
   */
  public static Options with(double[] p, double[] x, int terms) {
    return new Builder(p, x, terms);
  }

  /**
   * Fluent builder interface for configuring the Metalog fit.
   */
  public interface Options {

    /**
     * Sets the epsilon used for grid generation and numerical stability.
     *
     * @param eps the epsilon value
     * @return this builder
     */
    Options epsilon(double eps);

    /**
     * Sets a custom grid of percentiles for monotonicity constraints.
     *
     * @param gridP the grid of percentiles
     * @return this builder
     */
    Options grid(double[] gridP);

    /**
     * Sets the lower bound for the distribution.
     *
     * @param L the lower bound
     * @return this builder
     */
    Options lower(double L);

    /**
     * Sets the upper bound for the distribution.
     *
     * @param U the upper bound
     * @return this builder
     */
    Options upper(double U);

    /**
     * Performs the fit and returns a {@link Metalog} instance.
     *
     * @return the fitted Metalog distribution
     */
    Metalog fit();
  }

  private static class Builder implements Options {
    private final double[] p, x;
    private final int terms;
    private double epsilon = 1e-6;
    private double[] gridP = null;
    private Double lower = null, upper = null;

    Builder(double[] p, double[] x, int terms) {
      this.p = p;
      this.x = x;
      this.terms = terms;
    }

    @Override
    public Options epsilon(double eps) {
      this.epsilon = eps;
      return this;
    }

    @Override
    public Options grid(double[] gridP) {
      this.gridP = gridP;
      return this;
    }

    @Override
    public Options lower(double L) {
      this.lower = L;
      return this;
    }

    @Override
    public Options upper(double U) {
      this.upper = U;
      return this;
    }

    @Override
    public Metalog fit() {
      // 1) build default gridP on [ε … 1−ε]
      if (this.gridP == null) {
        int G = 100;
        double eps = 1e-12;
        this.gridP = IntStream.rangeClosed(0, G)
            .mapToDouble(i -> eps + (1 - 2 * eps) * i / (double) G)
            .toArray();
      }

      // 2) transform x→z
      double[] z = new double[x.length];
      if (lower != null && upper != null) {
        double L = lower, U = upper, span = U - L;
        for (int i = 0; i < x.length; i++) {
          double y = (x[i] - L) / span;
          z[i] = Math.log(y / (1 - y)); // logit
        }
      } else if (lower != null) {
        double L = lower;
        for (int i = 0; i < x.length; i++) {
          z[i] = Math.log(x[i] - L);
        }
      } else if (upper != null) {
        double U = upper;
        for (int i = 0; i < x.length; i++) {
          z[i] = -Math.log(U - x[i]);
        }
      } else {
        System.arraycopy(x, 0, z, 0, x.length);
      }

      // 3) fit an unbounded metalog on (p, z)
      QPUnboundedConstrainedFitter qp = new QPUnboundedConstrainedFitter(
          p, z, terms, epsilon, gridP);
      double[] a = qp.fit();
      Metalog mZ = new Metalog(a);

      // 4) wrap mZ to invert the transform
      return new Metalog(a) {
        @Override
        public double quantile(double p) {
          double zz = mZ.quantile(p);
          if (lower != null && upper != null) {
            double L = lower, U = upper;
            double frac = 1.0 / (1.0 + Math.exp(-zz)); // logistic
            return L + frac * (U - L);
          } else if (lower != null) {
            return lower + Math.exp(zz);
          } else if (upper != null) {
            return upper - Math.exp(-zz);
          } else {
            return zz;
          }
        }
      };
    }
  }
}
