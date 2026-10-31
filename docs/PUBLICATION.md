# Public source preparation

31 October 2026. The public repository was prepared from the validated source
with its development history retained and sanitized.

## Removed from the public tree and reachable history

- Personal author/committer email addresses, replaced with a project noreply identity.
- User-specific filesystem paths and a historical generated Python cache file.
- The maintainer's OAuth application ID and local signing-certificate fingerprint.
- Device model details and a private test-file fingerprint in historical reports.
- Links to private historical CI runs.

The current build reads OAuth configuration from an ignored local file, an
environment variable or a Gradle property. No client secret is used by the app.
The disconnected public build was assembled and linted separately.

## Checks

Gitleaks 8.30.1 reported no secrets in the prepared source tree or rewritten
history. The targeted history check reported no remaining private markers.
`scripts/check-public-tree.py --all-history` also passed. The privacy checker
was exercised against clean content, a private path removed in a later commit
and a tracked local configuration file; the last two were correctly rejected.

These checks complement code review. They do not prove the absence of every
possible secret. Future pushes and pull requests run a pinned Gitleaks binary
and the tracked-file/history check. CI actions are pinned to commit hashes.

## Historical GitHub materials

The former development repository, including old PR references, Actions logs
and artifacts, remains a **private, archived backup**. It was not made public.
The public repository is a separate GitHub repository with the cleaned history,
so the old PR refs and cached commits are not inherited. Local private backups
are ignored by Git and were not uploaded to the public repository.

Rewriting history does not revoke credentials or erase earlier downloaded
copies. Any credential exposed elsewhere must be rotated with its provider.
