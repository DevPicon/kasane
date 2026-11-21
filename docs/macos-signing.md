# Developer ID signing, notarization and GitHub Secrets

The normal CI build remains ad-hoc signed and has no signing secrets. Local Developer ID signing is opt-in; local notarization uses an existing Keychain profile. Only an artifact that passes both notarization stages, stapling and Gatekeeper assessment is described as notarized.

## Apple account and credentials

Use the same active **Apple Developer Program** membership used for App Store distribution; a separate subscription is not needed. Distribution outside the Mac App Store needs a **Developer ID Application** certificate, not an Apple Distribution / Mac App Store certificate. The team's Account Holder can create it in Xcode or Certificates, Identifiers & Profiles. See [Apple Developer ID](https://developer.apple.com/developer-id/).

The signing identity includes its private key in the Mac's Keychain. A downloaded public `.cer` alone is insufficient. For a CI runner, the owner exports the certificate **and its private key** into a password-protected `.p12`. Do not send that export or its password through chat, issues, pull requests or Git.

Notarization also needs authorized credentials: for example, a team App Store Connect API key with the corresponding key ID and issuer ID. Use the least role Apple permits for the chosen notarization flow. An Apple ID with an app-specific password is an alternative; do not use the normal account password in CI.

## Local signing and notarization

1. Install a valid Developer ID Application certificate with its private key in the login Keychain. Verify the identity with `security find-identity -v -p codesigning`.
2. Create an app-specific password personally in the Apple Account portal. Store it interactively, never in shell arguments or chat:

   ```sh
   xcrun notarytool store-credentials "kasane-notary" --apple-id "YOUR_APPLE_ID" --team-id "YOUR_TEAM_ID"
   ```

   This prompts securely for the app-specific password and validates the profile before saving it.
3. Select the public identity name (without the `Developer ID Application:` prefix) and build:

   ```sh
   export KASANE_SIGNING_IDENTITY="Your Name (YOURTEAMID)"
   ./gradlew test createDistributable
   python3 scripts/test-signing.py
   python3 scripts/notarize-local-dmg.py --keychain-profile kasane-notary
   ```

Compose signs nested code, including native JAR contents and the Java runtime. The metadata finalizer then renews only the outer app signature, preserving its entitlements and requirements, with hardened runtime and a secure timestamp. This exception requires an explicit matching Developer ID identity, a valid original signature/team, and the exact local build output path. It refuses to downgrade an identity signature to ad hoc or edit a delivered app outside that build directory.

The notarization script validates the signed bundle/version and Keychain profile, copies the app into a new working directory, submits the app archive, saves and reviews its notarization log, and requires `Accepted`. It staples and validates the app ticket before creating a Developer ID-signed DMG. It then notarizes the DMG, staples/validates its ticket and checks Gatekeeper and image integrity. The final `*-notarized.dmg` and SHA-256 appear under a uniquely named `build/notarization/` directory only after all gates pass. Failed or timed-out submissions retain their public IDs/logs for investigation; do not claim them as releases or repeatedly resubmit without checking their status.

The intermediate `*-signed.dmg` is not a notarized distribution. Existing 0.1.0-alpha.1/.2 files and tags remain unchanged. Local credentials are not exported or uploaded to GitHub by this flow. CI signing is a separate future setup after the local walkthrough succeeds.

## Store secrets outside Git

Recommended environment name: `macos-release`. Use GitHub's **Settings → Environments → macos-release → Environment secrets** when that environment is configured for trusted releases. Environment protection features depend on the GitHub plan; do not assume mandatory reviewer gates are available for every private repository.

| Value | Storage |
| --- | --- |
| `MACOS_CERTIFICATE_P12_BASE64` | Secret: certificate/private-key export encoded as Base64 |
| `MACOS_CERTIFICATE_PASSWORD` | Secret: password protecting the export |
| `APPLE_NOTARY_KEY_P8_BASE64` | Secret: notarization API private key encoded as Base64 |
| `MACOS_SIGNING_IDENTITY` | Variable: public Developer ID identity name |
| `APPLE_TEAM_ID` | Variable: public team identifier |
| `APPLE_NOTARY_KEY_ID` | Variable: key identifier |
| `APPLE_NOTARY_ISSUER_ID` | Variable: team-key issuer identifier |

Base64 is an encoding, not encryption. Protection comes from GitHub Secrets and controlled access to the release job. `.gitignore` excludes common signing-material extensions, but it cannot prevent an explicitly forced add, a renamed secret or malicious workflow code. Review workflows and release tags as carefully as source code. Never print private values or enable shell tracing around them. See [GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).

The owner can enter values in GitHub's UI, or use `gh secret set` with interactive input / stdin. Do not paste literal credentials into shell history. No long-lived personal GitHub token is needed to attach release assets: the current release job uses its scoped `GITHUB_TOKEN`.

## CI signed release sequence (`.github/workflows/release.yml`)

Normal PR and `main` builds (`tests.yml` and `build.yml`) remain ad-hoc signed and credential-free. Trusted tag releases (`v*`) run `build-signed` inside the `macos-release` GitHub Environment:

1. Validate `appVersion` against the `v*` tag and run `tests.yml`.
2. In `build-signed` (`macos-15` arm64 and `macos-15-intel` x64), decode `MACOS_CERTIFICATE_P12_BASE64` into an ephemeral keychain and `APPLE_NOTARY_KEY_P8_BASE64` into `$RUNNER_TEMP/AuthKey.p8`.
3. Build and finalize metadata with `./gradlew createDistributable` and `KASANE_SIGNING_IDENTITY`.
4. Run `scripts/notarize-local-dmg.py --api-key ... --key-id ... --issuer ... --output-dir dist --zip-app` to notarize and staple both the `.app` and the `.dmg`, verify Gatekeeper/checksums, and export the notarized `.dmg`, `.zip`, and `.sha256` files.
5. In an unconditional `if: always()` cleanup step, delete the ephemeral keychain and temporary `.p12`/`.p8` files. Never upload signing material as an Actions artifact.
6. Verify checksums in `release` and publish the assets with `gh release create`.

Do not run signing against untrusted pull-request code or expose these credentials through `pull_request_target`.

References: [Apple notarization workflow](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution), [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).
