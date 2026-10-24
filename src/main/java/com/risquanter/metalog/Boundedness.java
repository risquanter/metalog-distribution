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

/**
 * Keelin's four boundedness modes, each as the transform between a value
 * {@code x} of the distribution and the value {@code z} an unbounded metalog
 * is fitted to.
 * <p>
 * Every transform is strictly increasing, so a metalog that is strictly
 * increasing in {@code z} is strictly increasing in {@code x}.
 */
sealed interface Boundedness {

    /** Forward transform x → z, applied to the fitted data. */
    double toZ(double x);

    /** Inverse transform z → x, applied at query time. */
    double toX(double z);

    /**
     * Factor by which the density of z is divided to give the density of x at
     * the same percentile, namely dx/dz expressed in terms of x.
     */
    double densityScale(double x);

    /** Whether x lies strictly inside the support, where toZ is finite. */
    boolean admits(double x);

    /** z = x. */
    record Unbounded() implements Boundedness {
        public boolean admits(double x) {
            return Double.isFinite(x);
        }

        public double toZ(double x) {
            return x;
        }

        public double toX(double z) {
            return z;
        }

        public double densityScale(double x) {
            return 1.0;
        }
    }

    /** z = ln(x − l). */
    record Lower(double l) implements Boundedness {
        public boolean admits(double x) {
            return x > l;
        }

        public double toZ(double x) {
            return Math.log(x - l);
        }

        public double toX(double z) {
            return l + Math.exp(z);
        }

        public double densityScale(double x) {
            return x - l;
        }
    }

    /** z = −ln(u − x). */
    record Upper(double u) implements Boundedness {
        public boolean admits(double x) {
            return x < u;
        }

        public double toZ(double x) {
            return -Math.log(u - x);
        }

        public double toX(double z) {
            return u - Math.exp(-z);
        }

        public double densityScale(double x) {
            return u - x;
        }
    }

    /** z = logit((x − l)/(u − l)). */
    record Both(double l, double u) implements Boundedness {
        public boolean admits(double x) {
            return x > l && x < u;
        }

        public double toZ(double x) {
            double y = (x - l) / (u - l);
            return Math.log(y / (1.0 - y));
        }

        public double toX(double z) {
            return l + (u - l) / (1.0 + Math.exp(-z));
        }

        public double densityScale(double x) {
            return (x - l) * (u - x) / (u - l);
        }
    }

    /**
     * Selects the mode the two optional bounds describe.
     *
     * @throws IllegalArgumentException if both bounds are given and the lower
     *                                  one is not below the upper one
     */
    static Boundedness of(Double lower, Double upper) {
        if (lower != null && upper != null) {
            if (!(lower < upper)) {
                throw new IllegalArgumentException(
                        "lower bound must be below upper bound: lower=" + lower
                                + ", upper=" + upper);
            }
            return new Both(lower, upper);
        }
        if (lower != null) {
            return new Lower(lower);
        }
        if (upper != null) {
            return new Upper(upper);
        }
        return new Unbounded();
    }
}
