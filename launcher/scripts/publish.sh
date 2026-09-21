#!/usr/bin/env bash
# Publish the draft release the workflow opened for a tag — after checking
# it carries an installer, an updater bundle and a signature for every
# platform, plus latest.json. Fills the notes from the commits since the
# previous tag when the draft still has the placeholder body. Then points
# the Homebrew tap (ryz3nplayz/homebrew-tap, Casks/dusklauncher.rb) at the
# new DMGs so `brew install --cask` and `brew upgrade` follow.
#
#   scripts/publish.sh v0.2.1
#   scripts/publish.sh v0.2.1 --check   # verify only, leave it a draft
set -euo pipefail

TAP_REPO="ryz3nplayz/homebrew-tap"
REPO="ryz3nplayz/dusklauncher"

cd "$(dirname "$0")/.."
tag="${1:-}"
check_only=0
[ "${2:-}" = "--check" ] && check_only=1
if [[ ! "$tag" =~ ^v[0-9] ]]; then
  echo "usage: scripts/publish.sh vX.Y.Z [--check]" >&2
  exit 2
fi

json="$(gh release view "$tag" --json isDraft,body,assets,url)"
assets="$(printf '%s' "$json" | python3 -c 'import json,sys; print("\n".join(a["name"] for a in json.load(sys.stdin)["assets"]))')"

# what every platform must have shipped: the thing a person downloads, the
# thing the updater downloads, and the minisign signature it verifies with
need=(
  latest.json
  '_aarch64.dmg$'  'aarch64.app.tar.gz$'  'aarch64.app.tar.gz.sig$'
  '_x64.dmg$'      'x64.app.tar.gz$'      'x64.app.tar.gz.sig$'
  '-setup.exe$'    '-setup.exe.sig$'
  '_amd64.deb$'    '\.AppImage$'          '\.AppImage.sig$'
)
missing=0
for pat in "${need[@]}"; do
  if ! printf '%s\n' "$assets" | grep -qE "$pat"; then
    echo "missing: $pat" >&2
    missing=1
  fi
done
if [ "$missing" = 1 ]; then
  echo "assets on $tag:" >&2
  printf '  %s\n' $assets >&2
  echo "the release workflow hasn't finished (or a target failed) — not publishing" >&2
  exit 1
fi

# latest.json must name every updater target, and its version must be the tag's
tmp="$(mktemp)"
gh release download "$tag" --pattern latest.json --output "$tmp" --clobber
python3 - "$tmp" "$tag" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
want = sys.argv[2].lstrip("v")
assert doc["version"].lstrip("v") == want, f"latest.json says {doc['version']}, tag is {want}"
for p in ("darwin-aarch64", "darwin-x86_64", "windows-x86_64", "linux-x86_64"):
    assert p in doc["platforms"], f"latest.json lacks {p}"
    assert doc["platforms"][p].get("signature"), f"{p} has no signature"
print("latest.json ok:", doc["version"], ", ".join(sorted(doc["platforms"])))
PY
rm -f "$tmp"

if [ "$check_only" = 1 ]; then
  echo "draft $tag checks out — not publishing (--check)"
  exit 0
fi
if [ "$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["isDraft"])')" != "True" ]; then
  echo "$tag is already published" >&2
  exit 0
fi

# release notes: the commit subjects since the previous tag, unless someone
# already wrote proper notes into the draft
prev="$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true)"
body="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["body"])')"
if [[ "$body" == Draft* || -z "$body" ]]; then
  if [ -n "$prev" ]; then
    notes="$(git log --no-merges --format='- %s' "$prev..$tag")"
    header="Changes since $prev"
  else
    notes="$(git log --no-merges --format='- %s' -n 30 "$tag")"
    header="First release"
  fi
  printf '## %s\n\n%s\n' "$header" "$notes" | gh release edit "$tag" --notes-file - >/dev/null
fi

gh release edit "$tag" --draft=false --latest >/dev/null
echo "published $tag — launchers polling latest.json will offer it now"
gh release view "$tag" --json url -q .url

# Homebrew: rewrite the cask with this version + the DMG checksums and push
# the tap. sha256 over the published assets, so the cask can't drift from
# what the release actually carries.
version="${tag#v}"
dl="https://github.com/$REPO/releases/download/$tag"
sha_arm="$(curl -sSL "$dl/DuskLauncher_${version}_aarch64.dmg" | shasum -a 256 | cut -d' ' -f1)"
sha_x64="$(curl -sSL "$dl/DuskLauncher_${version}_x64.dmg" | shasum -a 256 | cut -d' ' -f1)"
work="$(mktemp -d)"
gh repo clone "$TAP_REPO" "$work/tap" -- -q --depth 1
mkdir -p "$work/tap/Casks"
cat > "$work/tap/Casks/dusklauncher.rb" <<RB
cask "dusklauncher" do
  arch arm: "aarch64", intel: "x64"

  version "$version"
  sha256 arm:   "$sha_arm",
         intel: "$sha_x64"

  url "https://github.com/$REPO/releases/download/v#{version}/DuskLauncher_#{version}_#{arch}.dmg"
  name "DuskLauncher"
  desc "Minecraft launcher with the Dusk client built in"
  homepage "https://github.com/$REPO"

  livecheck do
    url :url
    strategy :github_latest
  end

  # The launcher updates itself (Tauri updater against latest.json), so
  # \`brew upgrade\` leaves it alone unless run with --greedy.
  auto_updates true
  depends_on macos: ">= :catalina"

  app "DuskLauncher.app"

  zap trash: [
    "~/Library/Application Support/app.tryzwork.dusklauncher",
    "~/Library/Caches/app.tryzwork.dusklauncher",
    "~/Library/WebKit/app.tryzwork.dusklauncher",
  ]
end
RB
(
  cd "$work/tap"
  git add Casks/dusklauncher.rb
  if git diff --cached --quiet; then
    echo "homebrew tap already at $version"
  else
    git commit -q -m "dusklauncher $version"
    git push -q origin HEAD
    echo "homebrew tap → $version (brew upgrade --cask dusklauncher)"
  fi
)
rm -rf "$work"
