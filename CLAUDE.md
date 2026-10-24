# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A Java 21 implementation of Keelin's metalog distribution: basis functions,
quantile and density evaluation, and a quadratic-programming fitter with
monotonicity constraints. Three source files under
`src/main/java/com/risquanter/metalog/`, one test class, four runnable
examples under `src/test/java/com/risquanter/examples/`. Published to Maven
Central as `com.risquanter:metalog-distribution`.

The mathematical authority is Keelin, T.W. (2016), "The Metalog
Distributions", *Decision Analysis* 13(4), Equation (6). The paper is
paywalled. The basis it defines has been verified against three sources that
agree with each other: Khokhlov, arXiv:2102.10999; the Wikipedia article
*Metalog distribution*; and the `pymetalog` reference implementation. A change
to a basis function, a derivative, or a feasibility condition is checked
against those before it is written.

## Sibling repositories

`register` (`../register`) consumes this library as a Maven Central binary and
is its only known consumer. Its agent reads this library's source from this
checkout, never from a cached jar. Its `.claude/` holds the working protocol
this repository's protocol was ported from; where the two differ, this file is
the authority here.

## Common commands

```bash
mvn clean test                      # unit tests (JUnit 5, surefire)
mvn -o clean test                   # same, offline; works once ~/.m2 is warm
mvn clean install                   # also runs javadoc — a malformed comment fails here, not in test

# the four examples, after `mvn clean install`, from the repository root
CP="target/classes:target/test-classes:$HOME/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar:$HOME/.m2/repository/org/ojalgo/ojalgo/56.0.0/ojalgo-56.0.0.jar"
for c in ExpertOpinionDemo1 ExpertOpinionDemo2 ObservationalMetalogFitExample RiskHierarchyMetalogPGrid; do
  java -cp "$CP" com.risquanter.examples.$c; echo "$c exit=$?"
done
```

`mvn exec:java`, which `README.md` shows, needs `exec-maven-plugin`. It is not in the local cache and `pom.xml` does not pin it, so running it downloads an unpinned plugin. Use the `java` form above.

The four examples are the only end-to-end tier. Three of them write tracked
files under `src/test/resources/`, so running them can change the working tree;
a changed output file is part of the change that caused it and is reviewed in
the diff.

`mvn dependency:build-classpath` is not cached for offline use. To compile a
scratch harness against the library, the two runtime jars are
`~/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar`
and `~/.m2/repository/org/ojalgo/ojalgo/56.0.0/ojalgo-56.0.0.jar`, with
`target/classes` on the classpath.

Report test results as pass or fail only. A failure anywhere blocks done. There
is no Docker-backed tier, so the leaked-container pre-flight does not apply.

## Versioning

`pom.xml` `<version>` is the source of truth, mirrored in the installation
snippet in `README.md`. PATCH for a bug or security fix that changes shipped
code; MINOR for a closed plan or an external behaviour change while below
`1.0.0`; no bump for docs-only, test-only or tooling-only changes. PATCH and
MINOR are applied when the qualifying work lands. MAJOR is the user's.

## Hard gates — always in force

These bind at the moment of the tool action and override any harness default
toward autonomy. A conflict between a gate and such a default is named, and
work stops (G7).

- **G1 Echo before code.** No `Edit`/`Write` that introduces or changes a
  signature, type, public method or behaviour until that signature was presented
  in a previous turn and answered with an accepted signal. An approved plan
  satisfies this for every signature it spells out verbatim.
- **G2 Decision triggers.** A new public type or method; a change to an
  existing signature or to existing behaviour; a new dependency; a workaround or
  cast; a test assertion changed to pass; a trade-off with consequences on both
  sides; a rule in tension with the situation. Each stops work and is presented
  as a decision, after the noise filter in the global instructions.
- **G3 Plan coverage is a quality-gated plan document.** Only a plan under
  `docs/dev/plans/` with exact signatures, a file inventory, specification
  alignment, an open-decisions list and a verification plan authorizes source
  edits. A chat go-signal authorizes at most writing that document.
- **G4 Specification alignment before code.** This repository keeps no decision
  records; the check that stands in for an ADR review is the plan's statement
  of what Keelin (2016) says about every basis function, derivative and
  feasibility condition the change touches, with the sources quoted.
- **G5 Green is the only done.** `mvn clean test`, `mvn clean install`, and
  the four examples. A red tier blocks done whatever its origin.
- **G6 Halt after presenting** any plan, signature, review or option list. No
  further tool calls until an accepted signal.
- **G7 Escalation is the only exit** from a rule conflict. Silent deviation and
  silent compliance are both violations.
- **G8 Plan = epic, shipped whole.** Every item in a plan's scope lands
  together. A continuation section is itself shipped whole.

## The plan quality gate

A plan document confers coverage only when it has all of these. Missing one
makes it a draft, whatever it is titled.

1. **Exact signatures** — every new or changed method, type and exception as
   copy-pasteable Java, not prose.
2. **File inventory** — every file to create or modify, as bullet lines of full
   repository-relative paths in the sidecar document
   `docs/dev/plans/PLAN-<name>-INVENTORY.md`. The plan carries a pointer, not
   the list. Only the user writes the sidecar, through
   `.claude/bin/approve-inventory`.
3. **Specification alignment** — what the paper says about each touched basis
   function, derivative or feasibility condition, and which sources it was
   checked against.
4. **Open decisions** — every unresolved choice with its options, or an
   explicit statement that there are none.
5. **Verification plan** — the tests to add and the exact commands that must
   be green, each with its expected output beside it.
6. **Plan-writing conformance** — the global `plan-writing-style` skill:
   decisions labelled `<CODE>-D-<n>` from the filename's code, every cited path
   resolving or marked as not yet existing, every acronym expanded on first use,
   every literal constant carrying its origin.
7. **Documentation impact** — there is no glossary; name the `README.md`
   passages and code comments the plan's decisions make stale.

## Mechanical enforcement

A `PreToolUse` hook, `.claude/hooks/protocol-gate.sh`, gates `Edit`/`Write`
under `src/**` and to `pom.xml`, and refuses Bash commands that would write
there. The user-owned token `.claude/protocol/approved` names the approved plan;
that plan's inventory sidecar lists the files. An edit is allowed only when the
edited file is a bullet line in that sidecar. An edit to `src/test/**` is also
allowed when the sidecar lists any `src/main/**` file. A file the sidecar does
not name is denied even mid-plan: stop, say which file is needed and why, print
the amendment block, and wait.

The amendment block is the plan path on the first line, then one
repository-relative path per line, with `new:` marking a file the plan will
create. The user pastes it into `.claude/protocol/pending` and runs
`.claude/bin/approve-inventory`.

Never write the protocol machinery — `.claude/protocol/`, `.claude/hooks/`,
`.claude/bin/`, `.claude/settings.json`, or any `*-INVENTORY.md`. Reading it
is fine. These controls catch careless mistakes and make a deliberate override
unmistakable; they are not got around by any route. A blocked command is handed
back to the user verbatim, with what it does and why, and work stops.

Tool routing under the gate: read with `Read`; search with Bash `grep` and
`find`; edit under `src/` and `pom.xml` with `Edit` or `Write` only; run inline
code over files outside the gated region with `python3 -c`, or `Write` a longer
script into the scratchpad and run it by path.

## Java conventions

- **A sum type is a sealed interface with records, matched exhaustively.** Four
  boundedness modes are four records, not two nullable `Double`s tested in a
  null ladder. Testing which case a value is by equality or null-check to pick
  a branch is the defect; a `switch` over the cases is the fix.
- **Validate at the boundary that holds the whole input.** For the fitter that
  is `fit()`, because the builder is incomplete until then. Malformed input
  throws `IllegalArgumentException`; data that admit no fit throws the
  library's own exception type. The two are not the same thing and are not
  conflated.
- **Numerical constants carry their origin in a comment** — measured, chosen
  here, or fixed by the paper. A bare `1e-12` or `100` is incomplete.
- **A basis or derivative change is checked against Equation (6) first**, by
  the per-index test, before any fitter test is run.
- **Comments describe the code as it is.** No history, no plan references, no
  review provenance. Load the global `code-comment-style` and `boyscout` skills
  before editing source.

## Tests

- Tests are the specification. Every validation branch and every error path
  gets a test, not only the happy path.
- Never weaken an assertion to make a test pass. A failing test is a finding
  about the implementation, and it stops work.
- A numerical tolerance is a constant and carries its origin like any other.

## Where the knowledge lives

| Document | Owns |
|---|---|
| `CLAUDE.md` (this file) | What the project is, its commands, the gates, the conventions |
| `README.md` | The user-facing usage and release verification |
| `docs/dev/plans/` | What is being built and whether it is done |
| Keelin (2016) Eqn (6) and the three verified sources | What the mathematics is |
