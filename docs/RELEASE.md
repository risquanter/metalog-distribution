# Release guide

How metalog-distribution is built, verified, and published to Maven Central.

## Published artifact

- `com.risquanter:metalog-distribution` (Java 21)

## The three workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `pr-build.yml` ("PR build") | pull requests to main | Tests only. References no secrets, so runs from fork PRs are safe by construction. |
| `ci-build.yml` ("CI Build + Sigstore provenance") | push to main | Tests, then stages a Maven-layout bundle, GPG-signs and Sigstore-signs every jar and pom, and uploads the zipped bundle as the run artifact `deployment-<run id>`. Each run's ID identifies a releasable bundle. |
| `release.yml` ("Release") | manual dispatch | Two jobs. `verify-authorization` checks the supplied TOTP code against the org secret (60-second period, YubiKey OATH credential) with the runner's preinstalled `python3`, accepting the current window and the two before it. `sign-and-publish` (gated on the first job, runs in the `release` environment restricted to main) reads the version from `pom.xml` and refuses any version that is not `MAJOR.MINOR.PATCH` digits, then checks that the named run is a successful ci-build run and that the tag `v<version>` points at the commit it built and carries a signature from `.github/allowed_signers`. Only then does it download the bundle, verify every jar's and the pom's Sigstore signature against ci-build's OIDC identity on main, upload to the Central Portal as a staged `USER_MANAGED` deployment, and create the GitHub release on the existing tag. It never creates the tag. |

No human-triggered run builds the artifacts it publishes: `release.yml` only
republishes what `ci-build.yml` built and signed on main, and PR runs never
touch signing.

Do not rename `ci-build.yml`. `release.yml` verifies Sigstore certificates
against that literal workflow path, and checks the run's workflow name
`CI Build + Sigstore provenance`.

## Required configuration (already in place)

- Org-level secrets: `TOTP_ORG`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`,
  `CENTRAL_PORTAL_ORG_TOKEN_NAME`, `CENTRAL_PORTAL_ORG_TOKEN`.
- Repository environment `release` with a deployment branch rule allowing only
  `main`.
- `.github/allowed_signers` holds the maintainer's SSH signing key.

## Cutting a release

Run every command in one terminal, from the repository checkout. Each step sets
shell variables that a later step reads, so a new terminal starts again from
step 2.

Prerequisites on the maintainer's machine:

- `gh` is logged in (`gh auth status`).
- git signs with the hardware SSH key: `gpg.format` is `ssh`, and
  `user.signingkey` names the key. Its public half is a signing key on the
  GitHub account and a line in `.github/allowed_signers`.
- `ykman` reaches the YubiKey.

**1. Set the version, commit with a signature, push to main.**

```bash
read -rp "Version to release: " VERSION
sed -i "0,/<version>[^<]*<\/version>/s//<version>$VERSION<\/version>/" pom.xml README.md
git diff pom.xml README.md
git add pom.xml README.md    # and the release's other files
git commit -S -m "release $VERSION"
git push
```

`read` asks for the version, for example `0.10.1`. The `sed` writes it into
the first `<version>` of each file: the project version in `pom.xml`, and the
installation snippet in `README.md` that mirrors it. `git diff` shows both
lines before anything is committed. The version must not be a SNAPSHOT. On `main` with upstream `origin/main`,
`git push` pushes `main` to `origin`.

**2. Wait for the ci-build run of that push, and read the commit it built.**

```bash
HEAD_SHA=$(git rev-parse HEAD)
RUN_ID=$(gh run list --workflow ci-build.yml --commit "$HEAD_SHA" --limit 1 --json databaseId -q '.[0].databaseId')
echo "run ${RUN_ID:-<none yet>} builds $HEAD_SHA"
gh run watch "$RUN_ID" --exit-status
```

`--commit` selects the run of the commit step 1 pushed, so an older run is
never picked up. If the echo prints `<none yet>`, the run has not started:
wait a few seconds and repeat the block. `--exit-status` makes the watch fail
when the run fails, and a failed run is not released.

**3. Sign the release tag on exactly that commit, check it, push it.**

```bash
VERSION=$(git show "$HEAD_SHA:pom.xml" | sed -n '0,/<version>/s#.*<version>\(.*\)</version>.*#\1#p')
if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  git tag -s "v$VERSION" "$HEAD_SHA" -m "v$VERSION" &&
  git tag -v "v$VERSION" &&
  git push origin "v$VERSION"
else
  echo "not a release version: $VERSION"
fi
```

The version is read from the pom of the commit being tagged, not from the
working tree. Nothing is tagged unless that version is three dot-separated
numbers, such as `0.10.1`. The YubiKey asks for a touch. `release.yml` refuses
a tag that is unsigned, that is signed by a key outside
`.github/allowed_signers`, or that points at any other commit.

**4. Dispatch the release with a fresh TOTP code, and watch it.**

```bash
gh workflow run release.yml -f run_id="$RUN_ID" -f totp_code="$(ykman oath accounts code -s '60/risquanter/simulation-util:release')"
sleep 5
RELEASE_RUN_ID=$(gh run list --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RELEASE_RUN_ID" --exit-status
```

`ykman` prints the code of the current 60-second window at once. The workflow
accepts that window and the two before it, and it checks the code seconds
after dispatch, so a fresh code is always valid. The org-wide OATH credential
`60/risquanter/simulation-util:release` authorizes releases in every
risquanter repository. `sleep 5` lets the new run appear before `gh run list`
reads it.

**5. Check that the commit and the tag are verified on GitHub.**

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
TAG_OBJECT=$(gh api "repos/$REPO/git/ref/tags/v$VERSION" -q .object.sha)
gh api "repos/$REPO/commits/$HEAD_SHA" -q .commit.verification
gh api "repos/$REPO/git/tags/$TAG_OBJECT" -q .verification
gh release view "v$VERSION"
```

Both `verification` objects read `"verified": true` and `"reason": "valid"`.
GitHub then shows the Verified badge on the commit and on the release's tag.

**6. Publish.** With both release jobs green, the deployment is staged. Finish
at <https://central.sonatype.com/publishing/deployments>: review it and click
Publish, or Drop. Publication to Central is irreversible.

Do not push another version change to main between steps 2 and 4.
`release.yml` reads the version from main's `pom.xml` when it is dispatched,
and that version must match the bundle and the tag.

## Dependency automation

Dependabot keeps the SHA-pinned `uses:` lines in every workflow current
(`.github/dependabot.yml`). It opens one PR per update against `main`, and
`pr-build.yml` runs the suite on each. Merging stays manual, and it cannot
publish a release.

## Verifying a published artifact

Artifacts carry detached GPG signatures (`.asc`, key
`0F0D975BADB0C1F45F5424A20BCC447FF2426979` on `keyserver.ubuntu.com`) and
Sigstore bundles (`.sigstore.json`):

```bash
cosign verify-blob --bundle metalog-distribution-<version>.jar.sigstore.json \
  --certificate-identity-regexp="https://github.com/risquanter/metalog-distribution/.github/workflows/ci-build.yml@refs/heads/main" \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  metalog-distribution-<version>.jar
```
