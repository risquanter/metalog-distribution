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
 * Thrown by {@link QPFitter.Options#fit()} when no strictly increasing metalog
 * with the requested number of terms fits the given quantiles.
 * <p>
 * This is a property of the data and the term count together, not of either
 * alone: the same quantiles usually admit a fit at a lower term count, or with
 * a bound. It is distinct from {@link IllegalArgumentException}, which the
 * fitter throws for inputs that are malformed on their own — a percentile
 * outside (0,1), mismatched array lengths, or more terms than data points —
 * and from {@link IllegalStateException}, which it throws when the solver
 * stops before reaching an optimum.
 */
public final class MetalogInfeasibleException extends RuntimeException {
    private final int terms;
    private final int dataPoints;

    MetalogInfeasibleException(int terms, int dataPoints, String detail) {
        super("no strictly increasing " + terms + "-term metalog fits these "
                + dataPoints + " points: " + detail);
        this.terms = terms;
        this.dataPoints = dataPoints;
    }

    /**
     * The number of terms that was requested.
     *
     * @return the requested term count
     */
    public int terms() {
        return terms;
    }

    /**
     * The number of quantile data points that were supplied.
     *
     * @return the data point count
     */
    public int dataPoints() {
        return dataPoints;
    }
}
