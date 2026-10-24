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
 * Internally always solves a monotonicity-constrained QP on z, then wraps the
 * fitted unbounded Metalog so that {@code quantile(p)} inverts the transform
 * and {@code pdf(p)} returns the density of x.
 *
 * @author Daniel Agota &lt;danago@risquanter.com&gt;
 */
public class QPFitter {

  /**
   * Intervals in the default monotonicity grid, giving 101 points. 101 is as
   * dense as the active-set solver handles at high term counts — it stalls at
   * 201 points with nine terms — and denser grids do not remove an interior
   * violation of a fit the data do not admit; the post-solve check in
   * {@link QPUnboundedConstrainedFitter} catches those instead.
   */
  private static final int DEFAULT_GRID_INTERVALS = 100;

  /**
   * Distance of the default grid's endpoints from 0 and 1. README tells
   * callers to clamp percentiles to this margin, so the grid spans everything
   * that can be queried.
   */
  private static final double DEFAULT_GRID_MARGIN = 1e-12;

  /** Default floor on the fitted quantile function's slope at grid points. */
  private static final double DEFAULT_EPSILON = 1e-6;

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
     * Sets the floor on the fitted quantile function's derivative at every
     * grid point, which is what makes the fit strictly increasing. Defaults
     * to 1e-6. It does not affect grid generation.
     *
     * @param eps the derivative floor, which must be positive
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
     * @throws IllegalArgumentException    if the inputs are malformed on their
     *                                     own, including more terms than data
     *                                     points
     * @throws MetalogInfeasibleException  if no strictly increasing metalog
     *                                     with the requested number of terms
     *                                     fits the quantiles
     * @throws IllegalStateException       if the solver stops before reaching
     *                                     an optimum
     */
    Metalog fit();
  }

  private static class Builder implements Options {
    private final double[] p, x;
    private final int terms;
    private double epsilon = DEFAULT_EPSILON;
    private double[] gridP = null;
    private Double lower = null, upper = null;

    Builder(double[] p, double[] x, int terms) {
      this.p = p.clone();
      this.x = x.clone();
      this.terms = terms;
    }

    @Override
    public Options epsilon(double eps) {
      this.epsilon = eps;
      return this;
    }

    @Override
    public Options grid(double[] gridP) {
      this.gridP = gridP.clone();
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
      // 1) default monotonicity grid, uniform on [margin, 1 − margin]
      if (this.gridP == null) {
        this.gridP = IntStream.rangeClosed(0, DEFAULT_GRID_INTERVALS)
            .mapToDouble(i -> DEFAULT_GRID_MARGIN
                + (1 - 2 * DEFAULT_GRID_MARGIN) * i / (double) DEFAULT_GRID_INTERVALS)
            .toArray();
      }

      // 2) transform x→z
      Boundedness bounds = Boundedness.of(lower, upper);
      double[] z = new double[x.length];
      for (int i = 0; i < x.length; i++) {
        if (!bounds.admits(x[i])) {
          throw new IllegalArgumentException(
              "x[" + i + "] = " + x[i] + " is outside the support of " + bounds);
        }
        z[i] = bounds.toZ(x[i]);
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
          return bounds.toX(mZ.quantile(p));
        }

        @Override
        double quantileDensity(double p) {
          return mZ.quantileDensity(p) * bounds.densityScale(quantile(p));
        }
      };
    }
  }
}
