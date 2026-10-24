# Review coverage — branch `main`

```
COVERAGE  tier=complex  scope=the implementation of PLAN-MB-METALOG-BASIS-CORRECTNESS
          plus the project configuration written in the same session (commit 3eba769
          and CLAUDE.md); source 1 of File scope — named by the user as "the changes
          today as per session history and code". The working tree is clean, so the
          set is `git show 3eba769` plus CLAUDE.md.
read-in-full:
  src/main/java/com/risquanter/metalog/Metalog.java
  src/main/java/com/risquanter/metalog/QPFitter.java
  src/main/java/com/risquanter/metalog/QPUnboundedConstrainedFitter.java
  src/main/java/com/risquanter/metalog/Boundedness.java
  src/main/java/com/risquanter/metalog/MetalogInfeasibleException.java
  src/test/java/com/risquanter/metalog/MetalogTest.java
  src/test/java/com/risquanter/metalog/QPFitterTest.java
  src/test/java/com/risquanter/examples/ObservationalMetalogFitExample.java
  pom.xml
  README.md
  CLAUDE.md
  docs/dev/plans/PLAN-MB-METALOG-BASIS-CORRECTNESS.md
  docs/dev/plans/PLAN-MB-METALOG-BASIS-CORRECTNESS-INVENTORY.md
  (register, read-only) modules/server/src/main/scala/com/risquanter/register/simulation/MetalogDistribution.scala
  (register, read-only) modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala lines 30-120
  (register, read-only) modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala lines 159-187
  (register, read-only) modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala lines 54-62, 215-300
  (register, read-only) docs/user/TERMINOLOGY.md section 1.4
discharged:
  1 adversarial line scan — 5 findings reported (non-finite anchors and bounds;
    sampled sweep; duplicate percentiles; asymmetric-grid tail sampling; quantile
    rounding onto a bound). FastMath.pow(φ, 0) and pow(φ, −1) are unreachable:
    every power − 1 in the j ≥ 4 loops is ≥ 1 — Metalog.java:217, 221-222.
    Every point of the default verification grid passes validateInputs and the
    decade loop reaches 1e-12 — QPUnboundedConstrainedFitter.java:255-274,
    checked by harness.
  2 removed-behaviour audit — PASS. pdf's own range check is re-established by
    quantileDensity — Metalog.java:117. The four transforms and their inverses
    match the deleted null ladder term for term — Boundedness.java:44-118.
    Only index 3 changed value: the j = 3 arm of the old loop produced
    φ^((3−1)/2)·logit = φ·logit — Metalog.java:161-172, 211-226.
  3 cross-module tracing — PASS, register-side notes in the report. Controls
    and the paths that reach them:
      terms ≤ K: QPUnboundedConstrainedFitter.java:136-140, reached from
        QPFitter.java:193 ← QPFitter.Options.fit ← register
        MetalogDistribution.scala:114 ← Simulator.scala:163, DistributionPreviewService.scala:58.
      admits: QPFitter.java:185, same callers.
      solver state: QPUnboundedConstrainedFitter.java:222, same callers and
        QPFitterTest.java:244 (six-parameter constructor).
      slope sweep: QPUnboundedConstrainedFitter.java:238-244, same callers.
    register catches `Exception` around fit() — MetalogDistribution.scala:116-118 —
    so every type listed on Options.fit propagates no further. register calls pdf
    on lower-bounded fits — DistributionPreviewService.scala:106 with lower =
    Some(0.0) at :63. register passes fresh arrays (`.toArray`) and does not
    mutate them. register pins 0.9.0 — build.sbt:38.
  4 state and interleaving — PASS. Builder is the only mutable object; fit()
    reads its fields once per call and captures locals in the returned wrapper
    (QPFitter.java:172-210). Two fit() calls, and lower() between them, give
    the same results as fresh builders (harness). No static mutable state in
    src/main (grep). Metalog is immutable: a is cloned and final — Metalog.java:71.
  5 invariant reconstruction — (a) and (b) PASS by MetalogTest
    basisMatchesKeelinEquationSix, derivativesMatchKeelinEquationSix,
    derivativesAgreeWithFiniteDifferences, re-derived from Eqn (6) in the
    report; (c) 1 finding (sampled sweep); (d) PASS by QPFitterTest
    boundedFitDensityIntegratesToOne and the sign argument densityScale > 0
    on the open support — Boundedness.java:57, 76, 95, 115; (e) 1 finding
    (duplicate percentiles); (f) PASS — proof in the report, pinned by
    basisTailWeightsAreTheDerivativesLeadingCoefficients.
    Objective scaling ½aᵀQa: PASS — 3 terms on 3 points interpolate to 1e-12
    (harness), and Expression.toIntRowColumn keeps (j,k) and (k,j) distinct
    (ojAlgo 56.0.0 bytecode).
    iterations_abort on the convex path: PASS — GenericSolver.isIterationAllowed
    offsets 87-98 compare countIterations() with options.iterations_abort;
    ActiveSetSolver calls it (bytecode). State.isOptimal() is absValue ≥ 64,
    so OPTIMAL (64) and DISTINCT (256) pass and FEASIBLE (16) does not.
  6 design altitude — 3 NOTEs reported (null-ladder factory; duplicate
    coefficient copy in the wrapper; test switch). Verification-grid cost
    measured: 3-term fit 4.7 ms, 9-term bounded fit 24.6 ms, grid build and
    sweep about 6 ms warm — caching not warranted.
  7 terminology freshness — 2 NOTEs on README prose (pre-existing); no
    TERMINOLOGY NOTE for register's glossary, reasoning in the report.
  8 inline verification — harness at the scratchpad path named in the report;
    every CONFIRMED verdict was reproduced there.
  build — `mvn -o clean install` PASS (tests and javadoc). Four examples PASS,
    exit 0, no tracked file changed (git status clean after the run).
escalated:
  .github/workflows/ci-build.yml — modified in the working tree before this
    session (CI workflow) → security
  .github/workflows/pr-build.yml — same → security
  .github/workflows/release.yml — same → security
  .github/allowed_signers — untracked, signing material (credential file) → security
  .github/dependabot.yml — untracked, dependency manifest tooling → security
```
