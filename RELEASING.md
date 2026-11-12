# Releasing Protoweft

Releases publish every `protoweft-*` library and `protoweft-bom` to Maven Central, under groupId
`io.github.rahulbsw`. Examples, the evaluation harness, the conformance apps and
`protoweft-argi-neutral` are never published.

- **Releases** start from the Actions tab with a version number. They publish only after your
  approval on the `maven-central` environment, because a Maven Central release can never be
  changed or deleted.
- **Snapshots** of `main` (`version` in `gradle.properties`, always `-SNAPSHOT`) are published on
  every push to `main`, to the Central Portal snapshot repository.

## Setup

Done once, for repository `rahulbsw/protoweft`.

| What | Where | State |
|---|---|---|
| Central Portal user token (not your login) | repository secrets `MAVEN_ARTIFACTORY_USERNAME`, `MAVEN_ARTIFACTORY_TOKEN` | set |
| Release signing key, Ed25519, fingerprint `7D1F 6067 10F8 071A 5AB3 D49F 2256 22B1 DCFB 8C54`, expires 2028-11-11 | `maven-central` environment secrets `SIGNING_KEY` (ASCII-armored private key) and `SIGNING_KEY_PASSWORD` | set |
| Public half of the signing key | keyserver.ubuntu.com (served in full) and keys.openpgp.org (served without its name, as for any key with no verified email), both checked by Central | uploaded |
| Environment `maven-central` | Settings → Environments: you as required reviewer, protected branches only | set |
| Actions may create pull requests (for the follow-up PR) | Settings → Actions → General → Workflow permissions | on |
| **SNAPSHOTs enabled for `io.github.rahulbsw`** | [Central Portal namespaces](https://central.sonatype.com/publishing/namespaces) → the namespace's menu → **Enable SNAPSHOTs** | needed before the first snapshot |

The signing key's private half and its passphrase are also kept on the owner's machine: the key
in the GnuPG keyring, the passphrase in the macOS Keychain under "Protoweft release signing key
passphrase". The secrets hold the only other copies.

### Replacing a secret

- **Central token:** in the Portal, **View Account → Generate User Token**, then set both
  `MAVEN_ARTIFACTORY_*` secrets to the new pair.
- **Signing key** (before it expires, or if it leaks):
  ```bash
  gpg --quick-generate-key "Rahul Jain (Protoweft releases)" ed25519 sign 2y
  gpg --armor --export <FINGERPRINT> > key.asc
  curl --data-urlencode keytext@key.asc https://keyserver.ubuntu.com/pks/add
  curl -H 'Content-Type: application/json' \
    --data "$(jq -n --rawfile k key.asc '{keytext: $k}')" https://keys.openpgp.org/vks/v1/upload
  gpg --export-secret-keys --armor <FINGERPRINT> | gh secret set SIGNING_KEY --env maven-central
  gh secret set SIGNING_KEY_PASSWORD --env maven-central   # paste the passphrase
  ```
  To extend the current key instead, run `gpg --quick-set-expire <FINGERPRINT> 2y`, re-upload the
  public key, and update `SIGNING_KEY`.

## Cutting a release

1. Check that the `[Unreleased]` section of `CHANGELOG.md` lists what ships. The release notes are
   taken from it, and a final release refuses an empty section.
2. In **Actions → Release → Run workflow** (on `main`), enter the version without a leading `v`:
   `0.1.0`, or a pre-release such as `0.1.0-rc.1` (`-alpha.N`, `-beta.N` and `-rc.N` are
   accepted).
3. **Verify** checks the version (the tag `v<version>` must not exist yet), builds and tests
   `main`'s head, and prepares the notes.
4. **Approve** the `maven-central` deployment when asked. **Publish** then:
   - signs and uploads to the Central Portal, and releases once Central has validated the
     deployment;
   - attests build provenance and the SBOM;
   - creates the tag `v<version>` and the GitHub release (marked pre-release for `-alpha`, `-beta`
     and `-rc`), with the jars and the SBOM attached.
5. For a final release, **Follow-up** opens the PR "Record release <version> and start <next>". It
   moves the released notes under `## [<version>] - <date>` and sets `version=<next>`
   (patch + 1, `-SNAPSHOT`) in `gradle.properties`. A PR opened by the workflow's token can't start
   other workflows, so the required checks don't run on it: close and reopen the PR to run them, or
   merge with admin bypass. To move to a new minor version instead, edit `gradle.properties` in
   that PR before merging.
6. Artifacts appear on Maven Central within about 30 minutes; deployments are listed at
   <https://central.sonatype.com/publishing/deployments>.

If a run fails before **Publish** finishes, nothing is released and no tag exists: fix the cause
and run it again with the same version. The first release should be a release candidate.

To check publishing locally without signing or uploading:
`./gradlew build publishToMavenLocal -Pversion=<version>`.

## Snapshots

`.github/workflows/snapshot.yml` publishes `main`'s snapshot on every push to `main` (and on
demand). Snapshots are unsigned and unvalidated, they are overwritten by each push, and Central
deletes them after 90 days. To use one:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content { includeGroup("io.github.rahulbsw") }
    }
    mavenCentral()
}
dependencies {
    implementation(platform("io.github.rahulbsw:protoweft-bom:0.1.0-SNAPSHOT"))
}
```

## What every published artifact carries

- Main, sources and Javadoc jars, plus a Gradle module metadata file.
- `.asc` signatures (releases) and checksums, added by the plugin at upload.
- A POM with name, description, url, the MIT license, the developer and SCM. CI checks this on
  every build by publishing to Maven Local.
- An `Automatic-Module-Name` of `io.github.rahulbsw.protoweft.<module>`.
