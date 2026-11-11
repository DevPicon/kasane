# Developer ID signing, notarization and GitHub Secrets

This is setup guidance. The current alpha is not Developer ID signed or notarized, and no signing secrets are configured or consumed by its CI workflows.

## Apple account and credentials

Use the same active **Apple Developer Program** membership used for App Store distribution; a separate subscription is not needed. Distribution outside the Mac App Store needs a **Developer ID Application** certificate, not an Apple Distribution / Mac App Store certificate. The team's Account Holder can create it in Xcode or Certificates, Identifiers & Profiles. See [Apple Developer ID](https://developer.apple.com/developer-id/).

The signing identity includes its private key in the Mac's Keychain. A downloaded public `.cer` alone is insufficient. For a CI runner, the owner exports the certificate **and its private key** into a password-protected `.p12`. Do not send that export or its password through chat, issues, pull requests or Git.

Notarization also needs authorized credentials: for example, a team App Store Connect API key with the corresponding key ID and issuer ID. Use the least role Apple permits for the chosen notarization flow. An Apple ID with an app-specific password is an alternative; do not use the normal account password in CI.

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

## Future signed release sequence

1. Build and finalize package metadata. Review the code and trusted version tag from main.
2. In a separate trusted macOS release job, import the `.p12` into an ephemeral keychain. Keep normal PR/build/test jobs credential-free.
3. Sign the bundle, embedded runtime and native libraries with Developer ID, hardened runtime and the entitlements required by the JVM. Use the Compose signing support/default Java entitlements as a starting point, then test the actual signed build.
4. Verify the signatures, create the archive, and submit it with `xcrun notarytool submit … --wait` using the selected credentials. Treat any result other than Accepted as a failure.
5. Staple the ticket to the app using `xcrun stapler`, validate it, then create a fresh release ZIP and checksum. Verify the distributed archive and Gatekeeper behavior on another Mac.
6. In an unconditional cleanup step, remove the ephemeral keychain and temporary certificate/key files. Attach only final distribution files; never upload signing material as an Actions artifact.

Do not run signing against untrusted pull-request code or expose these credentials through `pull_request_target`. Enabling this flow requires the owner's actual identity/keys and a verified signed/notarized walkthrough; the current unsigned preview workflow is not silently promoted into a signing workflow.

References: [Apple notarization workflow](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution), [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).
