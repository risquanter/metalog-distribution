# Plan: Metalog basis conformance and fit preconditions

**Status:** Approved 2026-10-24, all four continuations included; implemented. **Plan code:** `MB`.

**Specification reference:** Keelin, T.W. (2016), "The Metalog Distributions",
*Decision Analysis* 13(4), 243–277, Equation (6), cited by
`src/main/java/com/risquanter/metalog/Metalog.java` lines 37–39.

One epic, landed whole: the basis correction, the fit precondition, the density
transform, the monotonicity enforcement in the continuation section, the tests
that pin all four, and the comment sweep over every file the change touches.

---

## Objective

Three defects make the library return a wrong answer without signalling it, and
one documentation gap hides where a constant came from. All four are in the
three files under `src/main/java/com/risquanter/metalog/`.

1. **The basis at index 3 is wrong.** `Metalog.basisFunctions` produces
   `(p − 0.5) · logit(p)` at index 3, an exact duplicate of index 2, and
   produces no pure `(p − 0.5)` term at any index.
   `Metalog.basisDerivatives` mirrors it. Index 3 is the only wrong index;
   every index from 0 to 19 otherwise matches Equation (6).

2. **The fitter accepts more terms than it has data points.**
   `QPUnboundedConstrainedFitter`'s constructor compares the two array lengths
   to each other and neither to the term count, so 20 terms against 3 data
   points is accepted and returns one arbitrary member of an infinite set of
   exact interpolants.

3. **`pdf` returns the wrong density after a bounded fit.** `QPFitter.fit`
   returns an anonymous `Metalog` that overrides `quantile` to invert the
   `x → z` transform and does not override `pdf`, so `pdf` reports the density
   of `z`. Measured on a lower-bounded fit with lower bound 16.0, the reported
   density is larger than the true density by exactly the factor `x − 16.0` at
   every percentile checked, and integrates to 9.51 rather than 1.

4. **`MAX_TERMS = 20` has no stated origin.** Nothing in Equation (6) or
   elsewhere in Keelin (2016) fixes 20. The paper's guidance is three to five
   terms for expert elicitation and eight to twelve for data exploration. The
   bound is this library's choice and the class comment restates the value
   without saying so.

Defect 1 is the reason the other two matter together rather than separately: a
caller who asks for four or more terms today gets a fit from the wrong function
space, and a caller who uses a bounded fit's `pdf` gets a density that is not a
density. Both are silent.

---

## Specification alignment

This repository keeps no decision records. The authority is the cited paper, so
the conformance statement names the published basis and the sources it was
checked against.

**Equation (6), in the paper's own 1-based term numbering.** `α` is the
cumulative probability and `a_n` the coefficient of the `n`-th term:

```
M_n(α) = a1 + a2·ln(α/(1−α))                          for n = 2
       = M_2(α) + a3·(α−0.5)·ln(α/(1−α))              for n = 3
       = M_3(α) + a4·(α−0.5)                          for n = 4
       = M_{n−1}(α) + a_n·(α−0.5)^((n−1)/2)           for odd n ≥ 5
       = M_{n−1}(α) + a_n·(α−0.5)^(n/2−1)·ln(α/(1−α)) for even n ≥ 6
```

The `n = 4` term is the only one that breaks the alternation between a pure
power of `(α − 0.5)` and that power multiplied by the logit. The loop in
`basisFunctions` starts at index 3 and applies the alternation there, which is
exactly one index too early.

**In this library's 0-based index `j`, writing `φ` for `p − 0.5`:**

| `j` | Equation (6) | shipped |
|---|---|---|
| 0 | `1` | `1` |
| 1 | `logit(p)` | `logit(p)` |
| 2 | `φ · logit(p)` | `φ · logit(p)` |
| 3 | `φ` | `φ · logit(p)` — wrong |
| 4 | `φ²` | `φ²` |
| 5 | `φ² · logit(p)` | `φ² · logit(p)` |
| 6 | `φ³` | `φ³` |
| 7 | `φ³ · logit(p)` | `φ³ · logit(p)` |

**What the shipped basis actually spans.** For `terms = n` it spans Keelin's
`n`-term space with the `φ` direction deleted, plus one duplicate column. For
`terms = 4` that is Keelin's three-term space, so a four-term fit returns the
best three-term curve. For `terms ≥ 5` it is not any Keelin space of any order:
`terms = 5` spans `{1, logit, φ·logit, φ²}`, which omits `φ` while including
`φ²`. The returned curve is still monotone, because monotonicity is enforced on
the grid whatever the basis sits in, so nothing fails loudly.

**Sources the basis was verified against**, the paper itself being paywalled
and not read:

- Khokhlov, V. (2021), *Conditional Value at Risk and Partial Moments for the
  Metalog Distributions*, arXiv:2102.10999, which reproduces the five cases
  above under the words "Keelin (2016) defined the metalog distribution family
  using its quantile function as follows".
- The Wikipedia article *Metalog distribution*, which states the same five
  cases and states the least-squares precondition as `n ≥ k` data points for
  `k` terms with the design matrix of rank `k`.
- `pymetalog`, the reference Python implementation, whose quantile builder adds
  `a[3] * f` at index 3 with `f = y − 0.5` and whose derivative vector carries
  the literal `1` at the same index.

All three agree with each other and with a per-index numerical comparison
against a from-scratch implementation of Equation (6) over `terms` 2 to 20.

**The precondition in defect 2 is the paper's own, not a preference.** Keelin
fits by least squares, and the solution `a = (YᵀY)⁻¹Yᵀz` requires `YᵀY` to be
invertible, which requires at least as many data points as terms. Below that
the zero-residual set is a subspace rather than a point. The monotonicity
constraints trim that subspace and give no general reason it collapses to one
point, so which curve the caller receives is a property of the solver rather
than of the inputs.

**The density transforms in defect 3 are Keelin's four boundedness modes
differentiated.** Writing `g(p)` for `1 / (dM/dp)`, which is what `Metalog.pdf`
returns, `L` for the lower bound and `U` for the upper:

| Mode | Transform in `QPFitter` | Density of `x` |
|---|---|---|
| Unbounded | `z = x` | `g(p)` |
| Lower-bounded | `z = ln(x − L)` | `g(p) / (x − L)` |
| Upper-bounded | `z = −ln(U − x)` | `g(p) / (U − x)` |
| Fully bounded | `z = logit((x − L)/(U − L))` | `g(p) · (U − L) / ((x − L)·(U − x))` |

Each factor is `dz/dx` for that mode. The fully bounded case follows from
`dx/dz = (U − L)·σ(1 − σ)` with `σ = (x − L)/(U − L)`.

---

## Exact signatures

### 1. `Metalog.basisFunctions` — index 3 and the loop bound

Replacing lines 130–146 of `src/main/java/com/risquanter/metalog/Metalog.java`.
The two branch bodies inside the loop are unchanged; only the starting index
and the inserted case are new.

```java
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
```

### 2. `Metalog.basisDerivatives` — index 3 and the loop bound

Replacing lines 175–195 of the same file.

```java
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
```

### 3. `MAX_TERMS` — the origin clause

Replacing line 42 of the same file.

```java
    /**
     * Upper bound on the number of terms. Chosen by this library, not fixed by
     * Keelin (2016): the paper gives three to five terms for expert
     * elicitation and eight to twelve for data exploration, and no maximum.
     * Twenty leaves headroom above both while keeping the highest power of
     * {@code p − 0.5}, whose magnitude falls off as {@code 0.5^(terms/2)},
     * within a range where the least-squares normal matrix stays workable.
     */
    private static final int MAX_TERMS = 20;
```

### 4. `QPUnboundedConstrainedFitter` — the term-count precondition

Added to the existing package-private constructor, after the array-length
check. The constructor's parameter list does not change.

```java
        if (pData.length != xData.length) {
            throw new IllegalArgumentException("pData and xData must match");
        }
        if (terms > pData.length) {
            throw new IllegalArgumentException(
                    "terms must not exceed the number of data points: terms="
                            + terms + ", data points=" + pData.length);
        }
        Metalog.validateInputs(terms);
        Metalog.validateInputs(gridP);
```

`Metalog.validateInputs(int)` is not touched. Its other two callers,
`basisFunctions` and `basisDerivatives`, hold no data points, so a range check
is the only check available to them.

This constructor is the boundary for the check even though it is
package-private. The public entry, `QPFitter.with(p, x, terms)`, returns a
builder whose `lower`, `upper` and `grid` are still unset, so the full input
set does not exist until `fit()` runs. Validating at `with` would validate a
fraction of the inputs one call early and leave the rest unvalidated.

### 5. `Boundedness` — the transform as one sealed type

New file, `src/main/java/com/risquanter/metalog/Boundedness.java`.
Package-private, so no type is added to the published surface.

```java
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

    record Unbounded() implements Boundedness { }

    record Lower(double l) implements Boundedness { }

    record Upper(double u) implements Boundedness { }

    record Both(double l, double u) implements Boundedness { }

    /** Selects the mode the two optional bounds describe. */
    static Boundedness of(Double lower, Double upper) { ... }
}
```

`QPFitter.Builder.fit()` then builds one `Boundedness` and uses it three times,
replacing the three-branch null ladder that exists twice today at lines
155–173 and 186–196 and would otherwise be written a third time for `pdf`:

```java
      Boundedness bounds = Boundedness.of(lower, upper);

      double[] z = new double[x.length];
      for (int i = 0; i < x.length; i++) {
          z[i] = bounds.toZ(x[i]);
      }

      QPUnboundedConstrainedFitter qp = new QPUnboundedConstrainedFitter(
          p, z, terms, epsilon, gridP);
      double[] a = qp.fit();
      Metalog mZ = new Metalog(a);

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
```

`densityScale` returns `1.0` for `Unbounded`, `x − l` for `Lower`, `u − x` for
`Upper`, and `(x − l)·(u − x)/(u − l)` for `Both`, which are the four factors
tabulated in the specification-alignment section.

A sealed type rather than the null ladder for one reason beyond the
duplication: `lower` and `upper` being two nullable `Double`s makes the four
modes implicit in a pair of null tests, and three places have to agree on the
same test order. The four cases are named once and matched once.

### 6. `QPFitter.Options.epsilon` — the javadoc correction

The current javadoc at lines 65–71 says epsilon is "used for grid generation
and numerical stability". It is used only as the lower bound on the
monotonicity constraints. Grid generation uses a separate literal `1e-12` at
line 147.

```java
    /**
     * Sets the floor on the fitted quantile function's derivative at every
     * grid point, which is what makes the fit strictly increasing. Defaults
     * to 1e-6. It does not affect grid generation.
     *
     * @param eps the derivative floor, which must be positive
     * @return this builder
     */
    Options epsilon(double eps);
```

### 7. `QPUnboundedConstrainedFitter` — the class comment's basis list

The list at lines 34–40 omits the pure `(p − 0.5)` term and so shifts every
index below it. Replacing those lines:

```java
 * where the T_j are the metalog basis functions, following Keelin (2016)
 * Eqn (6). Writing φ for (p − 0.5):
 * T₀(p) = 1
 * T₁(p) = logit(p) = ln(p/(1−p))
 * T₂(p) = φ·logit(p)
 * T₃(p) = φ
 * T₄(p) = φ²
 * T₅(p) = φ²·logit(p)
 * …thereafter alternating φ^k and φ^k·logit(p) for ascending k.
```

---

## File inventory

The file inventory lives in its own document beside this one,
`PLAN-MB-METALOG-BASIS-CORRECTNESS-INVENTORY.md`, which the approval hook reads
and only the user writes, through `.claude/bin/approve-inventory`. Only its
bullet lines authorize an edit.

---

## Open decisions

### `MB-D-1` — the default monotonicity grid's endpoints — ruled 2026-10-24

**Ruling.** The endpoints stay at `1e-12`. Given before the check was run; the
check then showed the choice costs nothing.

**The check, run.** Six input sets were fitted twice on the corrected basis,
once with the grid endpoints at `1e-12` and once at `1e-6`, 101 points each,
and the two quantile functions compared over `(1e-12, 1 − 1e-12)`:

| Inputs | terms | max difference | solver state, both |
|---|---|---|---|
| README triplet, unbounded | 3 | `0` | `OPTIMAL` |
| README triplet, bounded `[16, 40]` | 3 | `0` | `OPTIMAL` |
| four heavy-tail anchors, unbounded | 4 | `7.6e-4` | `OPTIMAL` |
| four heavy-tail anchors, lower-bounded at 0 | 4 | `0` | `OPTIMAL` |
| nine observations, bounded `[0, 170]` | 4 | `0` | `OPTIMAL` |
| nine observations, bounded `[0, 170]` | 9 | `7.9e-3` | `OPTIMAL` |

The differences are in the fifth or sixth significant digit of the
coefficients, which is solver tolerance. The endpoint row's scale does not move
the fit. The grid endpoints are not changed, and no further decision rests on
them.

**What the check exposed instead** is in the continuation section below: the
constraint at the endpoint row is not actually honoured by the solver, and
nothing is enforced between grid points. That is a defect in its own right and
is handled there.

---

## Continuation — monotonicity enforcement, 2026-10-24

Found while running the `MB-D-1` check. In scope by the plan's stated ambition:
the numerical and implementation defects reported by the consuming project's
agent and those discovered on the way. Approved with `MB-D-2` ruled. It adds one
public type, named in the signatures below.

### What was found

The fitter enforces `dQ/dp ≥ ε` on 101 grid points and returns the solver's
coefficients without reading the solver's state or checking the result. Three
input sets, fitted on the corrected basis, show three distinct failures:

| Inputs | terms | at the 101 grid points | between them | solver said |
|---|---|---|---|---|
| nine observations, bounded `[0, 170]` | 9 | one violation: slope `−2053` at `p = 1 − 1e-12` | same point | `OPTIMAL` |
| five heavy-tail anchors, lower-bounded at 0 | 5 | one violation: slope `−0.02` at `p = 1e-12` | slope `−8.6` at `p = 1e-11` | `OPTIMAL` |
| four heavy-tail anchors, unbounded | 4 | none | slope `−0.013` at `p ≈ 0.325` | `OPTIMAL` |

**The endpoint rows are not honoured.** At `p = 1e-12` the logit basis
function's derivative is `1e12`, so that row's coefficients are of order
`1e12` while the interior rows are of order one. When the row is active the
solver solves it as an equality inside a linear system whose conditioning is
set by that scale, and satisfies it to about `1e-9` relative — which is
thousands in absolute slope. The first two rows of the table are that effect.

**Nothing is enforced between grid points.** The grid is uniform with spacing
`0.01`, so the whole of `(0.99, 1 − 1e-12)` holds no constraint. The second
row's `−8.6` at `p = 1e-11` sits in the mirror gap. The third row is an
interior dip one hundredth wide, and its residual sum of squares is `580`: the
data admit no monotone four-term fit, the quadratic programme is compromising,
and the compromise is handed back as a success.

**The solver's state is never read.** `model.minimise()` returns an
`Optimisation.Result` whose `getState()` is discarded at
`QPUnboundedConstrainedFitter.java:149`. A `FAILED` or `UNEXPLORED` result
would be returned as coefficients.

### The mathematics of the tails

Every logit-bearing basis function `φ^k · logit(p)` has derivative

```
k · φ^(k−1) · logit(p)  +  φ^k / (p · (1 − p))
```

The second part grows like `1/(1−p)` as `p → 1` and like `1/p` as `p → 0`.
Every other part of every basis derivative — the first part above, and the
derivatives of the pure powers — is bounded or grows only like `logit`, which
is slower than `1/p`. So next to either endpoint the sign of `dQ/dp` is the
sign of

```
C₁ = Σ_{j logit-bearing} a_j · (+½)^k_j      as p → 1
C₀ = Σ_{j logit-bearing} a_j · (−½)^k_j      as p → 0
```

provided the sum is not zero. The logit-bearing indices in 0-based numbering
are `1` (`k = 0`), `2` (`k = 1`) and every odd `j ≥ 5` (`k = (j − 1)/2`).
Requiring `C₀ ≥ ε` and `C₁ ≥ ε` is therefore an exact statement that the
quantile function is eventually increasing at both ends, expressed as two rows
whose coefficients lie in `[−1, 1]`. The endpoint grid rows, which try to say
the same thing with coefficients of `1e12`, become inactive once these hold.

### Remedies measured

| Configuration | nine obs., 9 terms | five anchors, 5 terms | four anchors, 4 terms |
|---|---|---|---|
| 101 uniform, as today | `−2053` | `−8.6` | `−0.013` |
| 101 uniform + the two tail rows | `+0.18` | `+1.1` | `−0.013` |
| 201 uniform + the two tail rows | did not return in 25 s | — | `−0.0033` |
| 1001 uniform + the two tail rows | — | — | `−0.00013` |
| 101 uniform + 60 log-spaced tail rows | did not return in 100 s | — | — |

Values are the minimum slope over a dense sweep of `(1e-12, 1 − 1e-12)`. The
two tail rows fix both tail failures exactly, at the same residual, in `53 ms`
warm against `24 ms` today. Denser grids are not a remedy: the active-set
solver does not return at 201 points with nine terms, and no density removes
the interior dip of a fit the data do not admit — it shrinks and persists. So
the grid stays at 101 points, and the interior case is caught after the solve
rather than prevented by more rows.

### Exact signatures

#### 8. `Metalog.Tail` and `Metalog.basisTailWeights` — the two tail rows

Added to `src/main/java/com/risquanter/metalog/Metalog.java`, package-private,
beside `basisDerivatives`.

```java
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
```

#### 9. `Metalog.quantileDensity` — the slope, named once

`pdf` computes `dQ/dp` inline and returns its reciprocal. The post-solve check
needs the slope itself, so it is named and `pdf` calls it. Package-private.

```java
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

    public double pdf(double p) {
        return 1.0 / quantileDensity(p);
    }
```

#### 10. `MetalogInfeasibleException` — the fit that does not exist

New file, `src/main/java/com/risquanter/metalog/MetalogInfeasibleException.java`.
Public, because the consumer catches it. Its constructor is package-private,
because only the fitter throws it.

```java
package com.risquanter.metalog;

/**
 * Thrown by {@link QPFitter.Options#fit()} when no strictly increasing metalog
 * with the requested number of terms fits the given quantiles.
 * <p>
 * This is a property of the data and the term count together, not of either
 * alone: the same quantiles usually admit a fit at a lower term count, or with
 * a bound. It is distinct from {@link IllegalArgumentException}, which the fitter throws for
 * inputs that are malformed on their own — a percentile outside (0,1),
 * mismatched array lengths, or more terms than data points — and from
 * {@link IllegalStateException}, which it throws when the solver stops before
 * reaching an optimum.
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

    /** The number of terms that was requested. */
    public int terms() {
        return terms;
    }

    /** The number of quantile data points that were supplied. */
    public int dataPoints() {
        return dataPoints;
    }
}
```

A dedicated type rather than `IllegalArgumentException`, because the consumer
has to tell "you passed `p = 1.5`" apart from "these anchors admit no monotone
four-term metalog, ask for fewer terms" — the first is a programming error and
the second is an answer about the data. This is a derived choice, flagged
reversible.

#### 11. `QPUnboundedConstrainedFitter.fit` — tail rows, state check, post-solve check

Replacing steps 6 to 8 of the method (lines 140–156). Steps 1 to 5 are
unchanged.

```java
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
        return a;
```

And the verification grid, a private static method in the same class:

```java
    /**
     * Where the fitted slope is checked after the solve: the span of the
     * constraint grid, sampled densely enough to see what the grid rows miss.
     * 10 001 uniform points resolve an interior dip a hundredth of the interval
     * wide with a hundred samples, which is the narrowest dip a fit the data do
     * not admit has been seen to produce. Three points per decade from 1e-2 down
     * to the grid's lower end, mirrored at the upper end, cover the tails where
     * uniform spacing has no sample at all.
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

    private static final int UNIFORM_CHECK_INTERVALS = 10_000;
    private static final double TAIL_CHECK_START = 1e-2;
    private static final int TAIL_CHECK_POINTS_PER_DECADE = 20;
```

New imports in that file: `org.ojalgo.optimisation.Optimisation`,
`java.util.Arrays`, `java.util.TreeSet`.

#### 13. `QPUnboundedConstrainedFitter` — the iteration limit, for the state test

The existing five-parameter constructor delegates to a six-parameter one. Both
are package-private. `QPFitter` keeps calling the five-parameter form.

```java
    /**
     * The solver's own default iteration limit, read from ojAlgo 56.0.0's
     * {@code Optimisation.Options}. Passing it leaves the solver's behaviour
     * exactly as it is without the parameter.
     */
    private static final int SOLVER_DEFAULT_ITERATION_LIMIT = Integer.MAX_VALUE;

    QPUnboundedConstrainedFitter(
        double[] pData, double[] xData, int terms, double epsilon, double[] gridP
    ) {
        this(pData, xData, terms, epsilon, gridP, SOLVER_DEFAULT_ITERATION_LIMIT);
    }

    QPUnboundedConstrainedFitter(
        double[] pData, double[] xData, int terms, double epsilon, double[] gridP,
        int iterationLimit
    ) { ... existing body, plus this.iterationLimit = iterationLimit; ... }

    private final int iterationLimit;
```

The six-parameter form exists so a test can stop the solver early. Capped at 50
iterations on the nine-observation, nine-term bounded case, the solver returns
state `FEASIBLE`, `isSuccess()` true, a residual of `12.3` against `0.0045` at
optimum, and a slope that is positive on the whole post-solve sweep. The slope
check therefore cannot stand in for the state check, and this is the input the
state test uses.

#### 12. `QPFitter.fit` — the grid constant's origin

The two literals, `G = 100` and `eps = 1e-12`, become named constants in
`QPFitter`, each carrying its origin in its own javadoc:

```java
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
```

### Tests to add, continuing the numbering in the verification plan

8. **`highOrderBoundedFitIsMonotoneToTheEndpoints`** — the nine observations
   `{65, 78, 82, 85, 90, 110, 127, 143, 160}` at Hazen positions, nine terms,
   bounded `[0.0, 170.0]`: `quantileDensity(p) > 0` at every point of a sweep
   that includes `1 − 1e-12`. Today's fitter produces `−2053` there.
9. **`heavyTailLowerBoundedFitIsMonotoneToTheEndpoints`** — the five anchors
   `{0.05, 0.25, 0.5, 0.9, 0.99} → {0.2, 1, 5, 30, 200}`, five terms,
   lower-bounded at `0.0`: the same sweep, including `1e-11`. Today: `−8.6`.
10. **`fitterRejectsAnchorsNoMonotoneFitAdmits`** — the four anchors
    `{0.05, 0.5, 0.9, 0.99} → {0.2, 2, 16, 120}`, four terms, unbounded:
    `fit()` throws `MetalogInfeasibleException` with `terms() == 4` and
    `dataPoints() == 4`. Today this returns a fit with a decreasing region.
11. **`basisTailWeightsAreTheDerivativesLeadingCoefficients`** — for every
    `terms` in `[2, 20]` and both tails, `basisDerivatives(p, terms)[j] · p·(1−p)`
    at `p = 1e-9` and `p = 1 − 1e-9` equals `basisTailWeights(terms, tail)[j]`
    to within `1e-6`. The subleading part is `k·φ^(k−1)·logit(p)·p·(1−p)`,
    which at `p = 1e-9` is below `2e-8`, so the tolerance is loose by a factor
    of fifty and still pins the leading coefficient.

12. **`fitterRefusesAResultTheSolverDidNotFinish`** — the nine observations of
    test 8, nine terms, fitted through the six-parameter constructor with an
    iteration limit of 50: `fit()` throws `IllegalStateException` whose message
    names the solver state. With the limit at `Integer.MAX_VALUE` the same
    inputs return coefficients, so the test pins both sides.

No input can produce an `INFEASIBLE` state. The coefficient vector with
`a₁ = 1` and every other coefficient zero is the logistic distribution, whose
slope `1/(p·(1−p))` is at least 4 on `(0, 1)`, so it satisfies every grid row
and both tail rows. Every constraint set the fitter builds therefore has a
feasible point, and the state check guards early stopping only.

The existing tests 6 and 7 — density integrates to one, and `pdf` is the
reciprocal of the quantile slope — now run against a fitter that has already
verified positive slope, so they pin the transform and nothing else.

### Inventory amendment

One new file. The block for `.claude/protocol/pending`:

```
docs/dev/plans/PLAN-MB-METALOG-BASIS-CORRECTNESS.md
new: src/main/java/com/risquanter/metalog/MetalogInfeasibleException.java
```

Every other edit above lands in a file the inventory already lists.

### Documentation impact of this section

- `README.md`, "Metalog fitting" — gains one sentence: `fit()` throws
  `MetalogInfeasibleException` when no strictly increasing metalog with the
  requested number of terms fits the quantiles. For loss data the first remedy
  is a lower bound of zero, and the second is fewer terms.
- `QPUnboundedConstrainedFitter`'s class comment, "Monotonicity Constraint"
  section — gains the tail rows and the post-solve check, as current behaviour.

### Open decisions in this section

#### `MB-D-2` — what the fitter does when the solver stops early — ruled 2026-10-24

Option C: read the solver's state, and throw `IllegalStateException` naming it
when the state is not optimal. Rejected: throwing `MetalogInfeasibleException`,
which would tell the caller to change the data when the solver is at fault; and
no state check, which returns a monotone fit far from the anchors as a success.

`MetalogInfeasibleException` is reserved for the post-solve sweep finding a
non-positive slope, which is a fact about the data and the term count. The
grid density is settled by measurement and is not a decision.

## Continuation — input validation and wrapper consistency, 2026-10-24

Approved 2026-10-24. Found during implementation. All three items are
in files the inventory already lists, and none adds public surface.

### 14. `Boundedness.admits` and the bounds' order — anchors outside the support

A lower-bounded fit takes `ln(x − l)`. An anchor at or below `l` gives
`ln(0) = −∞` or `NaN`, and today that value goes into the least-squares
objective without any signal. The fully bounded mode has the same gap at both
ends. A lower bound at or above the upper bound gives a negative span.

```java
    /** Whether x lies strictly inside the support, where toZ is finite. */
    boolean admits(double x);

    // Unbounded: Double.isFinite(x)
    // Lower:     x > l
    // Upper:     x < u
    // Both:      x > l && x < u
```

`Boundedness.of` rejects `lower >= upper` when both are present:

```java
        if (lower != null && upper != null) {
            if (!(lower < upper)) {
                throw new IllegalArgumentException(
                        "lower bound must be below upper bound: lower=" + lower
                                + ", upper=" + upper);
            }
            return new Both(lower, upper);
        }
```

`QPFitter.Builder.fit()` step 2 checks each anchor before transforming it:

```java
      for (int i = 0; i < x.length; i++) {
        if (!bounds.admits(x[i])) {
          throw new IllegalArgumentException(
              "x[" + i + "] = " + x[i] + " is outside the support of " + bounds);
        }
        z[i] = bounds.toZ(x[i]);
      }
```

### 15. `QPFitter.Builder` — copies of the caller's arrays

`with(p, x, terms)` and `grid(gridP)` keep references to the caller's arrays,
so a change to an array between `with` and `fit` changes the fit. The builder
takes copies, which is what `Metalog`'s constructor and
`QPUnboundedConstrainedFitter`'s constructor already do with their arrays.

```java
    Builder(double[] p, double[] x, int terms) {
      this.p = p.clone();
      this.x = x.clone();
      this.terms = terms;
    }

    @Override
    public Options grid(double[] gridP) {
      this.gridP = gridP.clone();
      return this;
    }
```

### 16. The fitted wrapper's `quantileDensity` — one source for slope and density

Signatures 5 and 9 contradict each other on the wrapper `QPFitter.fit`
returns. Signature 9 defines `quantileDensity(p)` as `dQ/dp`, with `pdf` as
its reciprocal. Signature 5 overrides `pdf` and leaves `quantileDensity`
inherited, so on a bounded fit `quantileDensity` returns the slope in `z`
while `quantile` returns `x`. The wrapper therefore overrides `quantileDensity`
instead of `pdf`, and the inherited `pdf` becomes correct through it:

```java
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
```

What `pdf` returns does not change. Tests 6 and 7 pin that.

### Tests

13. **`fitterRejectsAnchorsOutsideTheBounds`** — the README triplet with lower
    bound `17.0`, which equals its first anchor; with upper bound `35.0`,
    which equals its last; and with lower `40.0` and upper `16.0`. Each throws
    `IllegalArgumentException` from `fit()`.
14. **`builderIsUnaffectedByLaterChangesToItsArrays`** — `with(p, x, 3)`, then
    `x[0]` overwritten, then `fit()`. The result equals a fit of the original
    arrays at five percentiles.
15. **`boundedFitQuantileDensityIsTheSlopeOfItsQuantile`** — for each of the
    four modes, `quantileDensity(p)` equals the central-difference slope of
    `quantile` at the interior percentiles of test 7, within the same
    tolerance.

### Test 6's integration rule — ruled 2026-10-24

Test 6 was specified as "the trapezoid sum of `pdf(p) · dx/dp` over `(0, 1)`".
A trapezoid sum evaluates the integrand at `p = 0` and `p = 1`, and neither
`quantile` nor `pdf` accepts those values. The test therefore uses the midpoint
rule at 20 000 intervals, with the central difference for `dx/dp` taken at a
step of `1e-4` times the distance to the nearer endpoint. The assertion,
within `1e-3` of one, is the one specified. Ruled: the midpoint rule stands,
and test 6's text in the verification plan says so.

## Continuation — review findings, 2026-10-24

Approved 2026-10-24 from the complex-tier review report (`docs/dev/reviews/REVIEW-main.md`).
Every edit is in a file the inventory already lists. One decision, `MB-D-3`, is
ruled below. The review's finding numbers are kept for cross-reference.

### `MB-D-3` — what the post-solve monotonicity check promises — ruled 2026-10-24

The post-solve sweep samples the slope; a dip narrower than its spacing passes.
Ruled: Option A, denser tail sampling. The tails are sampled at twenty points per
decade, spaced evenly in `ln(1/δ)` where `δ` is the distance to the nearer
endpoint. The narrowest far-tail dip constructed against the previous three
points per decade was about `0.15` wide in `ln(1/δ)`; twenty per decade samples
every `0.115`. The promise stays a dense sample, and the javadoc of
`Options.fit` says so. A certified check is open as a separate question.

### Signatures

#### 17. `Boundedness` — one `admits`, validating constructors, no factory (findings 1, 5b, 6)

```java
    /** Whether x lies strictly inside the support, where toZ is finite. */
    default boolean admits(double x) {
        return Double.isFinite(toZ(x));
    }

    record Lower(double l) implements Boundedness {
        Lower {
            requireFinite(l, "lower");
        }
        ...
    }

    record Upper(double u) implements Boundedness {
        Upper {
            requireFinite(u, "upper");
        }
        ...
    }

    record Both(double l, double u) implements Boundedness {
        Both {
            requireFinite(l, "lower");
            requireFinite(u, "upper");
            if (!(l < u)) {
                throw new IllegalArgumentException(
                        "lower bound must be below upper bound: lower=" + l + ", upper=" + u);
            }
        }
        ...
    }

    private static void requireFinite(double bound, String name) {
        if (!Double.isFinite(bound)) {
            throw new IllegalArgumentException(name + " bound must be finite, got " + bound);
        }
    }
```

The four per-record `admits` overrides and `Boundedness.of(Double, Double)` go.

#### 18. `QPFitter.Builder` — the mode advances by `switch`; `epsilon` and `grid` validated; wrapper uses `super` (findings 1, 6, 7, 11)

```java
    private Boundedness bounds = new Boundedness.Unbounded();

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
      ...
      final Boundedness mode = this.bounds;   // captured by value, so a later lower()/upper() does not reach a returned Metalog
      ...
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
```

The `Double lower, upper` fields and `mZ` go. `Options.lower`/`upper` document
that a reversed or non-finite bound throws at the setter, because an invalid
`Both` cannot be constructed. `Options.fit` documents the rounding case
(finding 5a): on a bounded fit, a percentile so extreme that the quantile
rounds onto the bound gives a density of `Infinity` there.

#### 19. `QPUnboundedConstrainedFitter` — distinct percentiles; sweep density and symmetry (findings 3, 4; `MB-D-3`)

Added to the six-parameter constructor after the length check:

```java
        for (int i = 0; i < pData.length; i++) {
            for (int j = i + 1; j < pData.length; j++) {
                if (pData[i] == pData[j]) {
                    throw new IllegalArgumentException(
                            "pData must be distinct: pData[" + i + "] and pData[" + j
                                    + "] are both " + pData[i]);
                }
            }
        }
```

`verificationGrid` is as written in signature 11 above, which now shows the
twenty-per-decade loop and the `outermost = Math.min(lo, 1.0 − hi)` bound. It
is package-private so a test can inspect it.

### Tests

16. **`fitterRejectsNonFiniteInput`** — an anchor of `+Infinity` under a lower
    bound; `lower(−Infinity)`; `lower(NaN)`; `epsilon(−1.0)`; `epsilon(NaN)`.
    Each throws `IllegalArgumentException`.
17. **`fitterRejectsRepeatedPercentiles`** — `p = {0.1, 0.1, 0.9}` with three
    terms throws `IllegalArgumentException` naming indices 0 and 1.
18. **`gridNullIsRejected`** — `grid(null)` throws `IllegalArgumentException`.
19. **`verificationGridCoversBothTailsOfAnAsymmetricGrid`** — for a grid on
    `[0.3, 1 − 1e-12]`, the sweep has a point in `(1 − 1e-11, 1 − 1e-12)`.
20. **`returnedMetalogIsUnaffectedByLaterBoundChanges`** — `fit()`, then
    `lower(...)` on the same builder; the first result's quantiles are unchanged.
21. `MetalogTest.keelinDerivatives` becomes one `switch` (finding 8); no new
    assertion.

### Documentation

- `README.md` line 28: "with linear monotonicity constraints on the
  coefficients; bounds are handled by Keelin's transforms of the data". The
  two `mvn exec:java` commands become the `java -cp` form `CLAUDE.md` gives.
- `CLAUDE.md`: five source files and two test classes.
- `Options.fit` javadoc: the sweep is a dense sample; the rounding case.

## Continuation — tail certificate, 2026-10-24

Approved 2026-10-24, signatures included; implemented. The inventory lists the
new production file and the new test file.

### What it changes in the promise

The post-solve sweep samples the slope. This continuation proves the slope
positive from the grid's ends to `1e-2` in both tails, where the sampling
spacing is widest relative to how fast the slope can change. The body
`[1e-2, 1 − 1e-2]` stays sampled at a spacing of `1e-4`.

### The mathematics

Split every basis derivative by type, with `φ = p − 0.5`,
`inv = 1/(p(1−p))` and `logit = ln(p/(1−p))`:

```
logit-bearing  φ^k·logit :  k·φ^(k−1)·logit + φ^k·inv
pure power     φ^k       :  k·φ^(k−1)
constant       1         :  0
```

Collecting terms gives the slope as

```
s(p) = A(φ)·inv + B(φ)·logit + D(φ),    with B = A′,
A(φ) = Σ_{logit-bearing j} a_j·φ^k_j
D(φ) = a₃ + Σ_{even j ≥ 4} a_j·k_j·φ^(k_j − 1)
```

`A(−½)` and `A(½)` are the tail coefficients `C₀` and `C₁` that the tail rows
force to be at least `ε`. Differentiating once more, using `inv′ = 2φ·inv²`
and `logit′ = inv`:

```
s′(p) = 2·B(φ)·inv + 2·φ·A(φ)·inv² + B′(φ)·logit + D′(φ)
```

For a polynomial `P` in `φ` with coefficients `c_i`, `sup |P|` on `[−½, ½]` is
at most `‖P‖ = Σ |c_i|·0.5^i`. On a sub-interval of width `w`,
`|P| ≤ max(|P(φ₀)|, |P(φ₁)|) + ‖P″‖·w²/8`, which is the linear-interpolation
error bound.

**Method 1 — a threshold below which the slope is positive.** In the lower
tail, for `p ≤ q`: `A(φ) ≥ C₀ − ‖B‖·p` because `A′ = B`; `|B(φ)| ≤ ‖B‖`;
`|D(φ)| ≤ ‖D‖`; and `|logit(p)| ≤ ln(1/p)`. So `s(p) > 0` wherever

```
h(p) = ‖B‖·p + p(1−p)·(‖B‖·ln(1/p) + ‖D‖)  <  C₀
```

`h` increases on `(0, 1e-2]` and tends to zero, so the largest `q* ≤ 1e-2`
with `h(q*) < C₀` certifies all of `(0, q*]`. The upper tail mirrors it with
`q = 1 − p` and `C₁`. `q*` is found by bisection on `ln q`.

**Method 2 — an interval certificate between `q*` and `1e-2`.** On an interval
`[p₀, p₁]`, `inv`, `|logit|` and `|φ|` take their maxima at an endpoint,
because each is convex or monotone there. Bounding each factor of `s′` that way
gives `|s′| ≤ L` on the interval, and then

```
s(p) ≥ (s(p₀) + s(p₁) − L·(p₁ − p₀)) / 2      for every p in [p₀, p₁]
```

An interval is accepted when that is positive. Otherwise it is refuted if an
endpoint slope is at most zero, and split at its geometric midpoint if not.
Splitting stops at depth 60 or after 200 000 intervals in total.

**Measured on the prototype.** The review's constructed vector is refuted at
`p = 0.9999965`. Every fit in the test suite is certified, within 1.4 ms. Of
3 000 vectors built like the review's, all 1 317 with a real tail dip were
refuted and all 1 683 without one were certified.

### Signatures

#### 20. `TailCertificate` — new file, package-private

`src/main/java/com/risquanter/metalog/TailCertificate.java`

```java
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

    private TailCertificate() {
    }

    /** The outcome of certifying both tails of one coefficient vector. */
    sealed interface Verdict {
        /** dQ/dp > 0 is proved on both tails. */
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
     * Certifies dQ/dp > 0 on {@code [lo, TAIL_END]} and {@code [1 − TAIL_END, hi]}
     * for the metalog with coefficients {@code a}.
     */
    static Verdict certify(double[] a, double lo, double hi);

    /** Method 1: the largest q ≤ TAIL_END such that dQ/dp > 0 on (0, q] at the given tail; 0 if none. */
    static double threshold(double[] a, Metalog.Tail tail);

    /** A(φ), as coefficients of ascending powers of φ. */
    static double[] tailPolynomial(double[] a);

    /** D(φ), as coefficients of ascending powers of φ. */
    static double[] purePowerPolynomial(double[] a);
}
```

The private helpers are not part of the signature set: polynomial evaluation,
derivative, `‖P‖`, the interval bound, and the bisection loop.

#### 21. `QPUnboundedConstrainedFitter.fit` — step 11

After the post-solve sweep, before `return a`:

```java
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
```

An uncertified interval is refused with `MetalogInfeasibleException`. It means
the slope there is positive at every evaluated point and within rounding of
zero after 60 halvings. Such a fit is not strictly increasing at any
resolution the library can vouch for. This is a derived choice, flagged
reversible.

#### 22. `Options.fit` and `MetalogInfeasibleException` javadoc

`Options.fit`: the quantile function is proved strictly increasing from the
grid's ends to `1e-2` in both tails, and checked at a dense sample of
`[1e-2, 1 − 1e-2]`. `MetalogInfeasibleException`: also thrown when the slope
cannot be proved positive on part of a tail.

### Tests

New file `src/test/java/com/risquanter/metalog/TailCertificateTest.java`:

22. **`refutesTheReviewVector`** — `a = {0, 0.5 + e^(−12.6), −1, 10.599}`
    gives `Refuted` with `p` in the upper tail.
23. **`certifiesEveryFitInTheSuite`** — the README triplet in all four modes,
    the nine observations at four and nine terms, and the five heavy-tail
    anchors lower-bounded at 0: each `Certified`.
24. **`neverCertifiesAConstructedDip`** — 500 seeded vectors from the family
    `a = {0, ε − a₂/2, a₂, c₃}`. Each is scanned at 2 400 log-spaced points per
    tail. A vector the scan finds negative is never `Certified`, and a
    `Certified` vector's scan minimum is positive.
25. **`thresholdIsSound`** — for 500 seeded random vectors with positive tail
    coefficients, the slope is positive at 1 000 log-spaced points in
    `(1e-12, threshold]` in each tail.

### Inventory amendment

```
docs/dev/plans/PLAN-MB-METALOG-BASIS-CORRECTNESS.md
new: src/main/java/com/risquanter/metalog/TailCertificate.java
new: src/test/java/com/risquanter/metalog/TailCertificateTest.java
```

## Verification plan

### Tests to add

All in `src/test/java/com/risquanter/metalog/MetalogTest.java`, except the
fitter precondition tests which go in a new
`src/test/java/com/risquanter/metalog/QPFitterTest.java`. The existing three
tests in `MetalogTest` fit two terms only and are unaffected by every change
above; none is modified or weakened.

1. **`basisMatchesKeelinEquationSix`** — an independent implementation of
   Equation (6) written in the test, compared index by index against
   `basisFunctions` for every `terms` in `[2, 20]` at nine percentiles spread
   over `(0, 1)`. This test is the specification; it is written before the fix
   and it fails at index 3 until the fix lands.
2. **`derivativesMatchKeelinEquationSix`** — the same against
   `basisDerivatives`.
3. **`basisColumnsAreDistinct`** — for every `terms` in `[2, 20]`, no two
   indices agree at all nine sampled percentiles. Catches any future indexing
   slip in the loop, which is where this one came from.
4. **`derivativesAgreeWithFiniteDifferences`** — each
   `basisDerivatives` entry against a central difference of `basisFunctions`
   with step `1e-7`, chosen as the largest step whose truncation error stays
   below the comparison tolerance for these functions. Pins the two functions
   to each other so a future edit to one has to touch the other.
5. **`fitterRejectsMoreTermsThanDataPoints`** — `QPFitter.with` with three
   data points and four terms throws `IllegalArgumentException` from `fit`, and
   the message names both numbers. Also asserts that three terms against three
   data points is accepted, so the boundary is pinned on both sides.
6. **`boundedFitDensityIntegratesToOne`** — for each of the four boundedness
   modes, the midpoint sum of `pdf(p) · dx/dp` over `(0, 1)` at 20 000
   intervals is `1` to within `1e-3`, with `dx/dp` taken as a central
   difference of `quantile` at a step of `1e-4` times the distance to the
   nearer endpoint. The midpoint rule never evaluates at `p = 0` or `p = 1`,
   where `quantile` and `pdf` are undefined.
   This is the test the current `pdf` fails at 9.51 for the lower-bounded
   case.
7. **`boundedFitPdfIsReciprocalOfQuantileSlope`** — for each mode,
   `pdf(p)` equals `1 / (dx/dp)` to central-difference tolerance at several
   percentiles. States the same property pointwise, so a failure localises.

### Commands that must be green

```bash
mvn clean test
```
Expected: `BUILD SUCCESS`, and the surefire summary reporting zero failures and
zero errors across both test classes.

```bash
mvn clean install
```
Expected: `BUILD SUCCESS`. This is the tier that runs the javadoc plugin, so a
malformed javadoc comment from items 3, 6 and 7 of the signatures section
fails here and not in `test`.

```bash
CP="target/classes:target/test-classes:$HOME/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar:$HOME/.m2/repository/org/ojalgo/ojalgo/56.0.0/ojalgo-56.0.0.jar"
for c in ExpertOpinionDemo1 ExpertOpinionDemo2 ObservationalMetalogFitExample RiskHierarchyMetalogPGrid; do
  java -cp "$CP" com.risquanter.examples.$c; echo "$c exit=$?"
done
```
`mvn exec:java` would download `exec-maven-plugin`, which `pom.xml` does not
pin, so the examples are run directly.
Expected: each exits 0 and writes its vega-lite JavaScript Object Notation
(JSON) chart specification without throwing. These
four are this repository's only end-to-end tier, and the only code that
exercises a bounded fit outside the tests.

Every one of them asks for no more terms than it has data points, so none
begins throwing. One changes its output:
`ObservationalMetalogFitExample` fits nine observations at four terms and again
at nine, both bounded to `[0.0, 170.0]`, so both of its fitted traces come from
a corrected basis. It writes `src/test/resources/vega-lite-observational.json`,
which is tracked by git, so that regenerated file is part of this change and is
reviewed in the diff like any other. The other three examples fit three points
at three terms, which never reaches index 3, and their tracked output files are
expected byte-identical — if either changes, something unintended happened and
the change stops.

No example calls `pdf`, so nothing here exercises the density fix. Tests 6 and
7 are its only coverage, which is why both are required.

Report pass or fail only. A failure in any tier blocks done, whatever its
origin. This project runs no Docker-backed test tier, so the leaked-container
pre-flight does not apply.

---

## Version and release

`pom.xml` `<version>` is the source of truth and is mirrored in the
installation snippet in `README.md`.

The bump is `0.9.0` to `0.10.0`. Three of the four changes alter the values a
published method returns, and one adds a thrown exception to an input that was
previously accepted, which is an external behaviour change below `1.0.0`.

`0.9.0` is on Maven Central and `register` consumes it. A consumer of `0.9.0`
that asks for four or more terms has fitted the wrong function space; a
consumer that reads `pdf` off a bounded fit has read a quantity that is not a
density; and a consumer may hold a fit whose quantile function decreases in a
tail, returned as a success. The release notes for `0.10.0` name all three,
with the affected term counts and boundedness modes, and name the new
`MetalogInfeasibleException` as behaviour a caller may now see.

---

## Documentation impact

This repository keeps no glossary and no architecture document, so the glossary
and current-state sweeps have nothing to act on. Named here are the files whose
text this plan's decisions make stale.

- `README.md`, the installation snippet — pins `0.9.0`, becomes `0.10.0`.
- `README.md`, "The implementation follows the referenced paper for quantile
  and PDF evaluation" — false today for four or more terms and for every
  bounded fit, true once this plan lands. No edit.
- `FINDING-basis-function-indexing.md` at the repository root — the hand-off
  note from the consuming project's agent that this plan absorbs. Deleted when
  the plan lands. Its content is in this document; the note itself carries a
  table of three disagreeing sources that is resolved here, so leaving it in
  place would leave a contradicting document in the tree.
- `src/test/resources/vega-lite-observational.json` — regenerated by running
  the observational example, whose fit this plan corrects. Tracked by git, so
  it is part of the change rather than an untracked side effect.
- `src/test/java/com/risquanter/examples/ObservationalMetalogFitExample.java`,
  the comment at lines 69–70 — says "5 terms and 9 terms" where the code fits
  four and nine. It misstates the exact term count the basis defect turns on,
  so it becomes `four terms and nine terms (exact fit)`.

Comment updates, which are part of the edit and not separate items: the basis
list in `QPUnboundedConstrainedFitter`'s class comment (signatures item 7), the
`epsilon` javadoc in `QPFitter.Options` (item 6), the `MAX_TERMS` origin
clause (item 3), and the inline `// T2 = ...` and `// j ≥ 3: ...` comments in
both `Metalog` methods, which name the indices the loops cover and so change
with the loop bound.

---

## Appendix — the measurement harness

The numbers quoted in the Objective were produced by a throwaway harness
compiled against `target/classes`, not by anything in the repository. It did
four things, and tests 1 to 6 of the verification plan subsume all four:

```
duplicate-column scan over terms 2..20, basis and derivatives
per-index comparison against a from-scratch Equation (6)
central-difference check of every derivative
four-mode density integration, and the density ratio against x − L
```

Reproducing it is not part of this plan. It is recorded because the
`terms = 20` against three data points result and the `9.51` density integral
are the evidence for defects 2 and 3, and a reviewer may want to see them
before the tests exist.
