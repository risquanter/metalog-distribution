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
   * The arrays are copied, so later changes to them do not reach the fit.
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
     * to 1e-6. It does not affect grid generation. A value that is not
     * positive is rejected by {@link #fit()}.
     *
     * @param eps the derivative floor, which must be positive
     * @return this builder
     */
    Options epsilon(double eps);

    /**
     * Sets a custom grid of percentiles for monotonicity constraints. The
     * array is copied.
     *
     * @param gridP the grid of percentiles
     * @return this builder
     * @throws IllegalArgumentException if {@code gridP} is null
     */
    Options grid(double[] gridP);

    /**
     * Sets the lower bound for the distribution.
     *
     * @param L the lower bound
     * @return this builder
     * @throws IllegalArgumentException if {@code L} is not finite, or an upper
     *                                  bound is already set and {@code L} is
     *                                  not below it
     */
    Options lower(double L);

    /**
     * Sets the upper bound for the distribution.
     *
     * @param U the upper bound
     * @return this builder
     * @throws IllegalArgumentException if {@code U} is not finite, or a lower
     *                                  bound is already set and {@code U} is
     *                                  not above it
     */
    Options upper(double U);

    /**
     * Performs the fit and returns a {@link Metalog} instance.
     * <p>
     * The returned quantile function is proved strictly increasing from the
     * grid's ends to 1e-2 in both tails, and checked at a dense sample of
     * [1e-2, 1 − 1e-2]: ten thousand uniform points across the grid's span. A
     * dip in that body narrower than the sampling is not detected. On a
     * bounded fit, a percentile so extreme that the quantile
     * rounds onto the bound itself gives a density of {@code Infinity} there,
     * because the slope in x underflows to zero while the slope in z stays
     * positive.
     *
     * @return the fitted Metalog distribution
     * @throws IllegalArgumentException    if the inputs are malformed on their
     *                                     own: a percentile outside (0,1),
     *                                     mismatched or non-finite values,
     *                                     repeated percentiles, more terms than
     *                                     data points, a non-positive epsilon,
     *                                     or a quantile on or outside a bound
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
    private Boundedness bounds = new Boundedness.Unbounded();

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
      if (gridP == null) {
        throw new IllegalArgumentException("gridP must not be null; omit grid(...) for the default");
      }
      this.gridP = gridP.clone();
      return this;
    }

    @Override
    public Options lower(double L) {
      bounds = switch (bounds) {
        case Boundedness.Unbounded u -> new Boundedness.Lower(L);
        case Boundedness.Lower l -> new Boundedness.Lower(L);
        case Boundedness.Upper u -> new Boundedness.Both(L, u.u());
        case Boundedness.Both b -> new Boundedness.Both(L, b.u());
      };
      return this;
    }

    @Override
    public Options upper(double U) {
      bounds = switch (bounds) {
        case Boundedness.Unbounded u -> new Boundedness.Upper(U);
        case Boundedness.Lower l -> new Boundedness.Both(l.l(), U);
        case Boundedness.Upper u -> new Boundedness.Upper(U);
        case Boundedness.Both b -> new Boundedness.Both(b.l(), U);
      };
      return this;
    }

    @Override
    public Metalog fit() {
      if (!(epsilon > 0.0)) {
        throw new IllegalArgumentException("epsilon must be positive, got " + epsilon);
      }

      // 1) default monotonicity grid, uniform on [margin, 1 − margin]
      if (this.gridP == null) {
        this.gridP = IntStream.rangeClosed(0, DEFAULT_GRID_INTERVALS)
            .mapToDouble(i -> DEFAULT_GRID_MARGIN
                + (1 - 2 * DEFAULT_GRID_MARGIN) * i / (double) DEFAULT_GRID_INTERVALS)
            .toArray();
      }

      // 2) transform x→z. The mode is captured by value so that a later
      // lower()/upper() on this builder does not reach a Metalog already
      // returned.
      final Boundedness mode = this.bounds;
      double[] z = new double[x.length];
      for (int i = 0; i < x.length; i++) {
        if (!mode.admits(x[i])) {
          throw new IllegalArgumentException(
              "x[" + i + "] = " + x[i] + " is outside the support of " + mode);
        }
        z[i] = mode.toZ(x[i]);
      }

      // 3) fit an unbounded metalog on (p, z)
      QPUnboundedConstrainedFitter qp = new QPUnboundedConstrainedFitter(
          p, z, terms, epsilon, gridP);
      double[] a = qp.fit();

      // 4) wrap the z-space metalog to invert the transform
      return new Metalog(a) {
        @Override
        public double quantile(double p) {
          return mode.toX(super.quantile(p));
        }

        @Override
        double quantileDensity(double p) {
          return super.quantileDensity(p) * mode.densityScale(quantile(p));
        }
      };
    }
  }
}
