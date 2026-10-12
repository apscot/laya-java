# Releasing to Maven Central (group `io.github.apscot`)

One-time setup (about 30 minutes):

1. **Central Portal account**: sign in at https://central.sonatype.com with GitHub. Add the namespace `io.github.apscot`
   and complete the verification (the portal asks you to create a temporary public GitHub repository with a given name).
2. **User token**: Central Portal > Account > *Generate User Token*. Save the username/password it shows.
3. **GPG key** (used to sign every file):
   ```bash
   gpg --full-generate-key                   # RSA 4096, no expiry (or long expiry)
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>      # publish the public half
   gpg --armor --export-secret-keys <KEYID>  # this ASCII block is the SIGNING_KEY secret
   ```
4. **GitHub repository** `apscot/laya-java`, with Actions secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`.

Each release:

```bash
git tag v0.1.0 && git push origin v0.1.0     # the Release workflow builds, signs and uploads
```

Publishing is *user managed*: open https://central.sonatype.com/publishing, review the deployment, press **Publish**.
Releases cannot be deleted once published, so first test with a SNAPSHOT-free dry run: `gradle publishToMavenLocal`
and consume it from a fresh project.

Local test publish with signing: `SIGNING_KEY="$(gpg --armor --export-secret-keys <KEYID>)" SIGNING_PASSWORD=... gradle publishToMavenLocal`.
