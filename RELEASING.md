# Releasing AgentWeave

Releases publish every `agentweave-*` library and `agentweave-bom` to Maven Central, under groupId
`io.agentweave`. Examples, the evaluation harness, the conformance apps and
`agentweave-argi-neutral` are never published.

Publishing is gated: the release workflow runs only for a version tag, and it waits for approval
on the `maven-central` GitHub environment.

## One-time setup (repository owner)

1. **Central Portal account and namespace.**
   1. Sign in at <https://central.sonatype.com>, then open your username menu and choose
      **View Namespaces → Add Namespace**. Enter `io.agentweave`.
   2. Copy the verification key it shows, and add it as a DNS **TXT** record on exactly
      `agentweave.io`, not a subdomain.
   3. Before you press **Verify**, confirm the record is visible with `dig TXT agentweave.io`.
      If you verify too early, the negative answer can be cached and verification is delayed.
2. **Portal user token.** In the Portal, open **View Account → Generate User Token**. Keep the
   username/password pair it produces; it is not your login.
3. **Signing key.**
   ```bash
   gpg --quick-generate-key "Rahul Jain (AgentWeave releases)" ed25519 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --export-secret-keys --armor <KEY_ID>      # value for SIGNING_KEY
   ```
4. **GitHub repository `rahulbsw/agentweave`:**
   - Create an environment named `maven-central`, and add yourself as a required reviewer.
   - Add these environment secrets:

     | Secret | Value |
     |---|---|
     | `MAVEN_CENTRAL_USERNAME` | user token username |
     | `MAVEN_CENTRAL_PASSWORD` | user token password |
     | `SIGNING_KEY` | ASCII-armored private key |
     | `SIGNING_KEY_PASSWORD` | its passphrase |
   - Under **Settings → Pages**, set the source to **GitHub Actions**. The docs then deploy to
     <https://rahulbsw.github.io/agentweave/>.

## Cutting a release

1. Move the `[Unreleased]` entries in `CHANGELOG.md` under the new version.
2. Check locally that publishing works:
   `./gradlew build publishToMavenLocal -Pversion=<version>`. This is unsigned and touches only
   `~/.m2`.
3. Tag and push: `git tag v<version> && git push origin v<version>`.
4. Approve the `maven-central` deployment in the Actions tab. The workflow then:
   - builds and tests;
   - signs and uploads to the Central Portal, and releases automatically after validation;
   - attests build provenance for the jars;
   - creates the GitHub release.
5. Check <https://central.sonatype.com/publishing/deployments>. Artifacts usually appear on Maven
   Central within 30 minutes.

The first release should be a release candidate (for example `v0.1.0-rc1`). The publishing
plugin (`com.vanniktech.maven.publish` 0.37.0) is documented as tested up to Gradle 9.7, and this
build uses 9.8.2.

## What every published artifact carries

- Main, sources and Javadoc jars, plus a Gradle module metadata file.
- `.asc` signatures and checksums, added by the plugin at upload.
- A POM with name, description, url, the MIT license, the developer and SCM. CI checks this on
  every build by publishing to Maven Local.
- An `Automatic-Module-Name` of `io.agentweave.<module>`.
