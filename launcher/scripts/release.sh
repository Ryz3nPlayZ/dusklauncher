#!/usr/bin/env bash
# Cut a release: bump every version field, commit, tag vX.Y.Z, push.
# The tag push triggers .github/workflows/release.yml, which builds the
# installers and opens a DRAFT release; `scripts/publish.sh vX.Y.Z` then
# checks it and makes it live (that's when launchers start updating).
#
#   scripts/release.sh 0.2.1            # bump + commit + tag + push
#   scripts/release.sh 0.2.1 --dry-run  # show what would change, touch nothing
set -euo pipefail

cd "$(dirname "$0")/.."
version="${1:-}"
dry=0
[ "${2:-}" = "--dry-run" ] && dry=1

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "usage: scripts/release.sh X.Y.Z [--dry-run]" >&2
  exit 2
fi
tag="v$version"

current="$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)"
branch="$(git rev-parse --abbrev-ref HEAD)"

if [ -n "$(git status --porcelain)" ]; then
  echo "working tree is not clean — commit or stash first" >&2
  git status --short | head -20 >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "tag $tag already exists" >&2
  exit 1
fi
if [ "$branch" != "main" ]; then
  echo "note: releasing from '$branch', not main — the tag will point here" >&2
fi
# same version is fine when it was never tagged (first release of a bump)
if [ "$(printf '%s\n%s\n' "$current" "$version" | sort -V | head -1)" != "$current" ]; then
  echo "$version is older than the current $current" >&2
  exit 1
fi
git fetch -q origin "$branch" || true
if [ "$(git rev-list --count "origin/$branch..HEAD" 2>/dev/null || echo 0)" != "0" ]; then
  echo "note: $branch has commits origin doesn't — they'll be pushed with the tag" >&2
fi

echo "release $current → $version ($tag) from $branch"
if [ "$dry" = 1 ]; then
  echo "dry run — would bump Cargo.toml, Cargo.lock, package.json, package-lock.json; commit; tag; push"
  exit 0
fi

# every place the version lives: the Cargo workspace (tauri.conf.json reads
# it from there), the npm package, and the two lockfiles
CUR="$current" NEW="$version" perl -0pi -e 's/^version = "\Q$ENV{CUR}\E"/version = "$ENV{NEW}"/m' Cargo.toml
cargo update -w --offline -q
npm version "$version" --no-git-tag-version --allow-same-version >/dev/null

git add Cargo.toml Cargo.lock package.json package-lock.json
git diff --cached --quiet || git commit -q -m "Release $tag"
git tag -a "$tag" -m "DuskLauncher $tag"
git push -q origin "$branch" "$tag"

echo
echo "pushed $tag — the release workflow is building the installers:"
echo "  gh run watch \$(gh run list --workflow release --branch $tag --limit 1 --json databaseId -q '.[0].databaseId')"
echo "when it's green, publish the draft (that's when launchers see the update):"
echo "  scripts/publish.sh $tag"
