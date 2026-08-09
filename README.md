# Metalog Distribution (Java)

A Java implementation of Keelin's Metalog distribution system [1]: a flexible probability modeling toolkit for translating expert judgment into quantitative models.

It is designed for cybersecurity practitioners, tool providers, and decision scientists who want to accurately represent risks. By using structured confidence levels—often expressed as probability triplets (e.g., with 10% certainty losses will be below 2 million, with 50% certainty below 16 million, and with 90% certainty below 20 million)—experts can articulate their assessments in a way that is both intuitive and mathematically rigorous.

The key advantage is that it enables precise, simulation-based quantitative risk assessment and aggregation on the JVM without requiring experts to master complex mathematics. This makes it possible to incorporate expert elicitation directly into robust probabilistic models, supporting better-informed decisions in high-stakes domains such as cybersecurity and enterprise risk management.

## Installation

```xml
<dependency>
  <groupId>com.risquanter</groupId>
  <artifactId>metalog-distribution</artifactId>
  <version>0.9.0</version>
</dependency>
```

The public API lives in the `com.risquanter.metalog` package.

## Functionality

The basic metalog functionality (basis functions for quantile expansion) as defined in the original papers, plus a QP fitter, exposed through a simple builder API:

- `Metalog` — quantile and PDF evaluation
- `QPFitter` — fits a Metalog distribution via quadratic programming (QP) using the Ojalgo library

The implementation follows the referenced paper for quantile and PDF evaluation. However, instead of ordinary least squares (OLS), fitting uses a quadratic programming (QP) approach with convex feasibility constraints (such as monotonicity and bounds) on the coefficients. This is motivated by Keelin's proof that the set of feasible Metalog coefficients is convex, making convex optimization (QP) both robust and theoretically justified for Metalog fitting.

## Usage

### Metalog fitting

```java
// 1) Expert quantiles
double[] pVals = {0.10, 0.50, 0.90};
double[] xVals = {17.0, 24.0, 35.0};
int terms = pVals.length;

Double lowerBound = 16.0;
Double upperBound = 40.0;

// 2) Fit two Metalogs: unbounded and bounded
Metalog metalogUnbounded = QPFitter
    .with(pVals, xVals, terms)
    .fit();

Metalog metalogBounded = QPFitter
    .with(pVals, xVals, terms)
    .lower(lowerBound)
    .upper(upperBound)
    .fit();
```

### Generating a quantile trace

To generate quantiles for a full range of probabilities \(p\) from \(0\) to \(1\), use evenly spaced \(p\) values.
Make sure \(p\) never reaches the exact endpoints \(0\) or \(1\).
Instead, clamp \(p\) to the interval $(\text{EPS}, 1-\text{EPS})$, where `EPS` is a very small number (e.g., $10^{-12}$):

```java
double pForQ = Math.min(Math.max(p, EPS), 1.0 - EPS);

int steps = 100; // or use ExampleUtil.STEPS
double eps = 1e-12; // or use ExampleUtil.EPS

for (int i = 0; i <= steps; i++) {
    double p = i / (double) steps;
    double pClamped = Math.min(Math.max(p, eps), 1.0 - eps);
    // Use pClamped for quantile calculation
    System.out.printf("p: %.5f, pClamped: %.12f%n", p, pClamped);
}
```

> **Note:**
> Clamping $p$ to $(\text{EPS}, 1-\text{EPS})$ is necessary because the Metalog implementation requires $p$ to be strictly within $(0,1)$.
> This avoids undefined behavior in the quantile and PDF calculations, such as division by zero or logarithm of zero, which can occur at the endpoints.
> Always clamp $p$ before calling `quantile(p)` or `pdf(p)` to ensure numerical safety.

Various examples under `src/test/java/com/risquanter/examples` demonstrate fitting and practical use-cases, such as working with risk hierarchies expressed as loss exceedance curves (LEC).

## Random number generation

`Metalog.quantile(p)` turns any uniform draw in (0, 1) into a sample from the fitted distribution, so the library pairs with any uniform PRNG. For a deterministic, counter-based PRNG designed for large-scale, reproducible Monte Carlo simulations, see [risquanter/hdr-rng](https://github.com/risquanter/hdr-rng) (Scala 3, JVM + Scala.js).

## Getting started

1. Clone the repository
2. Build: `mvn clean install`
3. Run an example from the command line:
   ```bash
   mvn exec:java -Dexec.mainClass="com.risquanter.examples.ExpertOpinionDemo1"
   ```

> **Note:**
> To keep the compiled JAR lean, all example classes are located under `src/test/java` and are not included in the main build artifact.
> If you want to run the examples without performing a full `mvn clean install`, you can compile the test sources with:
> ```bash
> mvn clean test
> ```
> This will compile the test classes, allowing you to run the examples with `mvn exec:java -Dexec.mainClass="..."` as shown above.

## Release verification

Artifacts on Maven Central are GPG-signed (key `0F0D975BADB0C1F45F5424A20BCC447FF2426979`,
published on `keyserver.ubuntu.com`) and carry Sigstore bundles (`*.sigstore.json`) bound to this
repository's CI workflow identity:

```
cosign verify-blob --bundle metalog-distribution-<version>.jar.sigstore.json \
  --certificate-identity-regexp="https://github.com/risquanter/metalog-distribution/.github/workflows/ci-build.yml@refs/heads/main" \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  metalog-distribution-<version>.jar
```

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

## References

[1] T. W. Keelin, "The Metalog Distributions," *Decision Analysis*, vol. 13, no. 4, pp. 243–277, Dec. 2016. [Online]. Available: https://doi.org/10.1287/deca.2016.0338
