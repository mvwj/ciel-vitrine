# Security policy

Ciel Vitrine is experimental. The current development branch receives fixes;
there is no supported production release or independent security audit yet.

## Report privately

Use [GitHub's private vulnerability reporting](https://github.com/mvwj/ciel-vitrine/security/advisories/new).
Describe the affected revision, reproduction steps and expected impact. Use
synthetic media and dummy credentials. Do not publish a working credential,
personal original or unredacted session/log in an issue or pull request.

## Important properties

Original containers preserve exact media bytes and recorded source metadata.
They use AES-GCM, but **the recovery key is inside the container**. A provider
or anyone holding that container can recover the original and its EXIF/GPS.
This is a recoverable packaging design, not end-to-end secrecy from the cloud.
See [CVPOS3](docs/CVPOS3_SPEC.md).

OAuth credentials and device catalogs use separate Android Keystore protection.
No OAuth client secret belongs in this app. The public source does not include
the maintainer's OAuth registration, signing key, device sessions or media.

Two objects on one provider do not protect against losing the entire account.
A verified archive row is not by itself permission to delete a source. Review
[the local removal limits](docs/CLOUD_STATUS_AND_LOCAL_RELEASE_RU.md) before
testing that workflow.

## If a credential was exposed

Revoke or rotate it with its issuer first, then remove it from files, Git
history, artifacts and logs. Rewriting Git cannot invalidate a credential or
erase someone else's downloaded copy. Never post the replacement credential.
