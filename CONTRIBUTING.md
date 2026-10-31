# Contributing

Small, reproducible changes are welcome. Describe the trigger, resulting
behavior and validation in each pull request. English and Russian reports are
both welcome. Contributions are accepted under [Apache-2.0](LICENSE).

## Local setup

Follow the [build instructions](README.md#build). Cloud access is optional for
local browsing and offline tests. Use your own OAuth registration and the
ignored `oauth.properties`; never add a client secret to the Android app.

Before opening a pull request, run the checks relevant to the change:

```sh
./gradlew test :app:assembleDebug :app:lintDebug
python scripts/test_pos_fixtures.py
python scripts/test_pos2_fixtures.py
python scripts/check-public-tree.py --all-history
```

Use an isolated emulator for the complete Android suite. Some instrumentation
tests deliberately change grants, sessions and inventories. Create synthetic
test media instead of using a personal gallery. Real-cloud tests are opt-in
and must use a test account.

## Data integrity changes

- Keep original containers separate from thumbnails, video renditions and caches.
- Compare full original bytes and observed source metadata; display quality or
  a successful HTTP response is not a verification proof.
- Preserve uncertainty after an interrupted upload. Reconcile by reading before
  creating a new object; do not blindly repeat PUT.
- Treat revoked permissions and unavailable objects as uncertainty.
- Exercise corruption, interruption, reopen/restart and source mutation where
  the changed behavior depends on them.
- Keep cloud deletion and Android local deletion under distinct authorization.

## Reports and attachments

Do not attach access tokens, OAuth secrets, signing keys, session/database files,
personal photos, precise location metadata or unredacted device logs. Synthetic
reproduction files and sanitized error text are usually sufficient. Report a
security issue through [the security policy](SECURITY.md).
