#!/usr/bin/env bash
set -euo pipefail

# Release helper for Iceberg Lens.
#
# Usage:
#   ./release.sh 1.1.0
#
# What this script does:
# 1) Validates git working tree is clean.
# 2) Validates version in build.gradle.kts matches input.
# 3) Runs a local build check.
# 4) Creates annotated git tag vX.Y.Z.
# 5) Pushes branch + tag.
# 6) GitHub Actions release workflow builds macOS/Windows/Linux and publishes GitHub Release.

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <version>"
  echo "Example: $0 1.0.1"
  exit 1
fi

VERSION="$1"
TAG="v${VERSION}"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must be semantic format X.Y.Z (example: 1.0.1)"
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes first."
  exit 1
fi

echo "Checking build.gradle.kts version..."
if ! rg -n "version = \"${VERSION}\"" build.gradle.kts >/dev/null; then
  echo "Version mismatch: build.gradle.kts is not set to ${VERSION}."
  echo "Update build.gradle.kts first, commit, then rerun."
  exit 1
fi

if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "Tag ${TAG} already exists."
  exit 1
fi

echo "Running local build check..."
./gradlew build -x test

echo "Creating tag ${TAG}..."
git tag -a "${TAG}" -m "Release ${TAG}"

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "Pushing ${CURRENT_BRANCH} and ${TAG}..."
git push origin "${CURRENT_BRANCH}"
git push origin "${TAG}"

cat <<EOF
Release trigger complete.

Next steps:
1. Open GitHub Actions and watch workflow "Release".
2. After it finishes, open GitHub Releases page.
3. Verify assets: .dmg, .msi, .deb are attached.
EOF
