#!/usr/bin/env bash
set -euo pipefail

# git-release.sh
# Create and push an annotated tag like v0.1.0-alpha.1 to trigger the CI release pipeline.
#
# Usage:
#   ./git-release.sh VERSION [--dry-run] [--force] [--remote origin] [--branch main]
#
# Examples:
#   ./git-release.sh v0.1.0-alpha.1
#   ./git-release.sh v0.1.0 --dry-run
#
# Behavior:
#   - Verifies git repo, branch, clean working tree (unless --force)
#   - Validates version format (vMAJOR.MINOR.PATCH[-prerelease])
#   - Ensures tag does not already exist (local or remote)
#   - Creates annotated tag and pushes it to the specified remote
#
# This does not create a GitHub Release directly; your workflow will do that.

REMOTE="origin"
REQUIRED_BRANCH="main"
DRY_RUN="false"
FORCE="false"

die() { echo "ERROR: $*" >&2; exit 1; }
note() { echo "-- $*"; }

usage() {
  cat <<EOF
Usage: $0 VERSION [--dry-run] [--force] [--remote <name>] [--branch <name>]

Options:
  --dry-run           Show what would happen without creating/pushing the tag
  --force             Skip clean working tree check
  --remote <name>     Git remote to push to (default: origin)
  --branch <name>     Expected branch to tag from (default: main)

Examples:
  $0 v0.1.0-alpha.1
  $0 v0.1.0 --dry-run
EOF
}

# ---- Parse args ----
VERSION="${1-}"
if [[ -z "${VERSION}" ]]; then
  usage; exit 1
fi
shift || true

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN="true"; shift ;;
    --force)   FORCE="true"; shift ;;
    --remote)  REMOTE="${2-}"; [[ -n "${REMOTE}" ]] || die "--remote needs a value"; shift 2 ;;
    --branch)  REQUIRED_BRANCH="${2-}"; [[ -n "${REQUIRED_BRANCH}" ]] || die "--branch needs a value"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

# ---- Preconditions ----
# 1) In a git repo
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "Not inside a Git repository."

# 2) Remote exists
git remote get-url "${REMOTE}" >/dev/null 2>&1 || die "Remote '${REMOTE}' not found."

# 3) On expected branch
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "${CURRENT_BRANCH}" != "${REQUIRED_BRANCH}" ]]; then
  die "You are on '${CURRENT_BRANCH}'. Switch to '${REQUIRED_BRANCH}' or pass --branch ${CURRENT_BRANCH} if intentional."
fi

# 4) Working tree clean (unless --force)
if [[ "${FORCE}" != "true" ]]; then
  if ! git diff --quiet || ! git diff --cached --quiet; then
    die "Working tree has uncommitted changes. Commit/stash or use --force to bypass."
  fi
fi

# 5) Version format validation
# Accepts: v1.2.3, v1.2.3-alpha.1, v1.2.3-rc.0, etc.
if [[ ! "${VERSION}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([\-+][0-9A-Za-z\.\-]+)?$ ]]; then
  die "Version '${VERSION}' is invalid. Expected like: v1.2.3 or v1.2.3-alpha.1"
fi

# 6) Sync + uniqueness checks
note "Fetching latest tags from '${REMOTE}'..."
git fetch --tags "${REMOTE}" >/dev/null

if git rev-parse -q --verify "refs/tags/${VERSION}" >/dev/null; then
  die "Tag '${VERSION}' already exists locally."
fi

if git ls-remote --tags "${REMOTE}" "refs/tags/${VERSION}" | grep -q .; then
  die "Tag '${VERSION}' already exists on remote '${REMOTE}'."
fi

# 7) Make sure local branch is up-to-date with remote
UPSTREAM="${REMOTE}/${REQUIRED_BRANCH}"
if git rev-parse --verify "${UPSTREAM}" >/dev/null 2>&1; then
  git fetch "${REMOTE}" "${REQUIRED_BRANCH}" >/dev/null
  LOCAL_SHA="$(git rev-parse ${REQUIRED_BRANCH})"
  REMOTE_SHA="$(git rev-parse ${UPSTREAM})"
  if [[ "${LOCAL_SHA}" != "${REMOTE_SHA}" ]]; then
    die "Local '${REQUIRED_BRANCH}' (${LOCAL_SHA}) is not up-to-date with '${UPSTREAM}' (${REMOTE_SHA}). Pull/merge first."
  fi
fi

# ---- Plan ----
note "About to create and push tag:"
echo "  Version : ${VERSION}"
echo "  Branch  : ${CURRENT_BRANCH}"
echo "  Remote  : ${REMOTE}"
if [[ "${DRY_RUN}" == "true" ]]; then
  note "[DRY RUN] Would run: git tag -a ${VERSION} -m \"${VERSION}\""
  note "[DRY RUN] Would run: git push ${REMOTE} ${VERSION}"
  exit 0
fi

# ---- Execute ----
cleanup_on_fail() {
  note "Push failed. Deleting local tag '${VERSION}' to keep things clean."
  git tag -d "${VERSION}" >/dev/null 2>&1 || true
}
trap cleanup_on_fail ERR

note "Creating annotated tag ${VERSION}..."
git tag -a "${VERSION}" -m "${VERSION}"

note "Pushing tag to ${REMOTE}..."
git push "${REMOTE}" "${VERSION}"

trap - ERR
note "Done. CI should start building and releasing for '${VERSION}'. 🚀"

# Helpful hints
echo
echo "Next steps:"
echo "  - Watch the GitHub Actions pipeline (Create Release workflow)."
echo "  - Once done, verify the GitHub Release and the Homebrew tap formula."
echo "  - On macOS: brew tap stefanesco/obsidize && brew install stefanesco/obsidize/obsidize"`