# Releasing

## One-time setup

1. **Maven Central namespace.** Create an account at
   [central.sonatype.com](https://central.sonatype.com), then verify the
   `com.payghaam` namespace under
   [Namespaces](https://central.sonatype.com/publishing/namespaces) — either
   via a DNS TXT record on `payghaam.com`, or by switching the groupId to
   `io.github.<your-username>` if you'd rather verify through GitHub instead
   (no DNS needed, but changes the Maven coordinate everywhere it's used —
   also update it in `payghaam/build.gradle.kts`'s `coordinates(...)` call
   and in every consumer, e.g. `payghaam-flutter`'s `android/build.gradle`
   and `payghaam-react-native`'s `android/build.gradle`).
2. **User token.** [Account](https://central.sonatype.com/account) →
   "Generate User Token" → gives you a username/password pair.
3. **GPG key.** Central requires every artifact to be PGP-signed.
   ```
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format LONG   # note the key ID
   gpg --export-secret-keys --armor <KEY_ID> > private.pgp
   ```
   Also publish the public key to a keyserver so Central can verify it:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
4. **Add repo secrets** (Settings → Secrets and variables → Actions):
   - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — the user token from step 2.
   - `SIGNING_IN_MEMORY_KEY` — contents of `private.pgp` from step 3.
   - `SIGNING_IN_MEMORY_KEY_ID` — the key ID from step 3.
   - `SIGNING_IN_MEMORY_KEY_PASSWORD` — the key's passphrase.

## Every release

1. Bump `version` in `payghaam/build.gradle.kts`.
2. Commit, push to `main`.
3. `git tag v0.1.1 && git push --tags` — this triggers
   `.github/workflows/publish.yml`.
4. Central validation takes minutes; full availability on
   `mavenCentral()` can take up to a few hours.

## Faster stopgap: JitPack

If you want the wrapper SDKs (`payghaam-flutter`, `payghaam-react-native`)
working end-to-end before Maven Central setup is done, JitPack needs zero
publishing step — it builds straight from a pushed git tag:

```
implementation "com.github.Payghaam:payghaam-android:v0.1.1"
```

Not a substitute for a real release, but useful for unblocking the wrapper
SDKs' own testing in the meantime.
