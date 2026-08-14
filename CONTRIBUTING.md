# Contributing to Volt

Thank you for your interest in contributing.

## Development Setup

1. Install JDK 21 or newer and Maven 3.9 or newer.
2. Fork and clone the repository.
3. Run `mvn --batch-mode verify`.

## Branching

Every change starts as an issue. Open one first — bug report, feature request,
or a short note for maintenance work — so the branch, the pull request, and the
release notes all point at the same discussion.

Branch off `main` and name the branch after the issue:

```text
feat/42-route-groups
fix/57-header-case-sensitivity
```

- Prefix `feat/` for additive work, `fix/` for corrective work, including
  documentation and maintenance.
- `42` is the issue number.
- The slug is short, kebab-case, and at most four words.

The PR pipeline rejects branches that do not match
`^(feat|fix)/[0-9]+-[a-z0-9]+(-[a-z0-9]+)*$`.

`release/X.Y.Z` branches are created by the Prepare Release workflow. Never
create or push one by hand.

## Reporting Bugs

- Search existing issues first.
- Include a minimal reproduction, expected and actual behavior, Volt and Java
  versions, and relevant logs without credentials.
- Report security vulnerabilities privately as described in
  [SECURITY.md](SECURITY.md).

## Suggesting Features

Open a GitHub Discussion or feature request. Explain the problem and use case
before proposing an API. Volt favors a small, explicit, dependency-free core.

## Submitting Pull Requests

1. Keep changes focused and preserve zero mandatory runtime dependencies.
2. Add or update unit and integration tests.
3. Run `mvn --batch-mode verify`.
4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.
5. Open a pull request against `main` and close the issue from the description
   with `Closes #42`.
6. Label the pull request so it lands in the right release-note category:
   `enhancement`, `bug`, `documentation`, `dependencies`, `refactor`, or
   `breaking-change`.
7. Address review feedback, then squash merge.

Pull requests into `main` are squash merged, so the pull request title becomes
the commit message — write it as a conventional commit,
`feat(routing): add route groups (#42)`.

While a `release/X.Y.Z` branch is open, work merged into `main` is **not** part
of that release. Fixes that must ship with the candidate go into
`release/X.Y.Z` directly, through a pull request from a `fix/` branch.

## Code Review Standards

- Pull requests require at least one approving review.
- CI checks must pass before merge.
- Breaking public API changes require prior discussion.
- Security, lifecycle, routing, and concurrency changes need regression tests.

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org):
`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, or `chore:`.

## Releases

Releases run through `main` and a single short-lived `release/X.Y.Z` branch.
Three workflows own the process; nothing is versioned or tagged by hand.

1. **Prepare Release** — dispatch with `version` (`1.2.0`). Cuts
   `release/1.2.0` from `main`, sets the `pom.xml` version to `1.2.0-rc`, and
   pushes the branch. Refuses to run if another release branch is still open.
2. **PR** — runs on every pull request and on every push to `release/**`. On a
   release branch it deletes the previous `1.2.0-rc` package version and
   redeploys, so exactly one release candidate exists per version. Merge
   fixes into `release/1.2.0` and repeat until the candidate is good.
3. **Release** — dispatch with the same `version`. Bumps `1.2.0-rc` to `1.2.0`,
   stamps `CHANGELOG.md`, publishes `net.messeb:volt:1.2.0` to GitHub Packages,
   creates the annotated tag `v1.2.0` and a GitHub Release marked latest, then
   opens a pull request back to `main`.

Merging that pull request closes out the release branch, which may then be
deleted.

Merge the release pull request with a **merge commit**, not a squash. Squashing
rewrites the released commit, so the `vX.Y.Z` tag would no longer be reachable
from `main` and the history would carry the release twice.

If contributors added `[Unreleased]` entries to `main` while the release branch
was open, that pull request conflicts in `CHANGELOG.md`. Resolve it by keeping
both sections: the new `[X.Y.Z]` section from the release branch and the
`[Unreleased]` entries from `main`.

Only one release branch may be open at a time. The Prepare Release workflow
refuses to cut a second one.

### Hotfixes

There is no separate hotfix flow. Once the release pull request is merged,
`main` holds the released version, so a patch release is an ordinary release:
merge the fix into `main`, then run Prepare Release with the patch version.

### Repository configuration

- **`PACKAGES_TOKEN`** — a personal access token with `write:packages`,
  `delete:packages`, and repository `contents: write`. The built-in
  `GITHUB_TOKEN` cannot delete package versions, and a branch it pushes does
  not trigger the PR workflow. Watch its expiry date; releases fail silently
  at the publish step once it lapses.
- **`main`** — protected: pull request required, PR pipeline required to pass,
  one approving review, squash merge.
- **`release/**`** — protected: pull request required, PR pipeline required to
  pass. Allow the `PACKAGES_TOKEN` identity to bypass, because Prepare Release
  and Release push to these branches directly.

Deleting the only remaining version of a package deletes the package itself;
GitHub then recreates it on the next deploy. Public package versions with more
than 5000 downloads cannot be deleted, which would break the release-candidate
overwrite — not a concern at current volume.

## Code Style

- Target Java 21 and use idiomatic Java APIs.
- Keep public APIs fluent, explicit, and null-safe.
- Avoid annotations, classpath scanning, and mandatory runtime dependencies.
- Prefer focused classes and tests over speculative abstractions.
