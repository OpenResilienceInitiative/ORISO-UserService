#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?Repository is required}"
: "${GITHUB_REPOSITORY_OWNER:?Repository owner is required}"
: "${GITHUB_REF_NAME:?Branch name is required}"
: "${GITHUB_OUTPUT:?GitHub output file is required}"

# PR validation checks the merge result and includes all branch checks. If the
# lookup fails, run branch validation rather than silently lose test coverage.
if number=$(gh api --method GET "repos/${GITHUB_REPOSITORY}/pulls" \
  --field state=open \
  --field "head=${GITHUB_REPOSITORY_OWNER}:${GITHUB_REF_NAME}" \
  --jq '[.[] | select(.head.repo.full_name == env.GITHUB_REPOSITORY)][0].number'); then
  if [[ "$number" =~ ^[0-9]+$ && "$number" -gt 0 ]]; then
    # GitHub does not run pull_request workflows for merge conflicts. Unknown
    # mergeability also falls back to branch checks rather than leave a gap.
    if mergeable=$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${number}" --jq .mergeable) \
      && [[ "$mergeable" == true ]]; then
      echo "Mergeable open PR found; its validation workflow covers this push."
      echo 'run_validation=false' >> "$GITHUB_OUTPUT"
      exit 0
    fi
  fi
else
  echo "::warning::Could not check open PRs; running branch validation."
fi

echo 'run_validation=true' >> "$GITHUB_OUTPUT"
