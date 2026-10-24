# File inventory — PLAN-MB-METALOG-BASIS-CORRECTNESS.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- src/main/java/com/risquanter/metalog/Metalog.java
- src/main/java/com/risquanter/metalog/QPFitter.java
- src/main/java/com/risquanter/metalog/QPUnboundedConstrainedFitter.java
- src/main/java/com/risquanter/metalog/Boundedness.java
- src/test/java/com/risquanter/metalog/MetalogTest.java
- src/test/java/com/risquanter/metalog/QPFitterTest.java
- src/test/java/com/risquanter/examples/ObservationalMetalogFitExample.java
- src/test/resources/vega-lite-observational.json
- pom.xml
- README.md
- FINDING-basis-function-indexing.md
- src/main/java/com/risquanter/metalog/MetalogInfeasibleException.java
