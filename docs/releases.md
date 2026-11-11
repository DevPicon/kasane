# Versioning and releasing

Android Emulator Hub is the provisional product name. Kasane remains the repository codename and `dev.picon.kasane` remains the bundle identifier.

## SemVer during initial development

The public version is `appVersion` in `gradle.properties`, currently **0.1.0-alpha.1**. Follow [Semantic Versioning](https://semver.org/): `0.y.z` is initial development, and `-alpha.N` identifies an experimental prerelease. Compatibility is not stable yet. During 0.x, use minor versions for feature/contract changes and patch versions for fixes; increment the alpha suffix for successive preview candidates.

The historical `v1.0.0` was incorrectly numbered during private prototyping. Its tag, commit and assets stay unchanged. Its release metadata identifies it as a legacy preview, not a stable contract. Do not move or recycle that tag, and do not modify the contents of any published version.

## macOS packaging counter

The public version is separate from `macBundleVersion`, a positive counter that increases for every distributed macOS build. The current counter is **2**. JDK 17 validates a positive internal app-version before reading the custom plist. The build uses the counter for that internal value, finalizes `CFBundleShortVersionString` with the public numeric version, stores full SemVer as `KasaneSemanticVersion`, and verifies a fresh ad-hoc signature.

This metadata finalization happens **before** any future Developer ID signing. It refuses to modify an identity-signed bundle. Ad-hoc signing is not notarization and does not identify the developer to Gatekeeper.

## CI and release process

`Tests` and `Build` run on main and pull requests. Tests include the version contract and Kotlin/Compose tests. Build produces separate Apple Silicon and Intel ZIPs containing `Android Emulator Hub.app`, plus read-only test DMGs and SHA-256 checksums. Actions artifacts/test reports are retained for 14 days; Releases hold durable downloads.

1. Change `appVersion` and increment `macBundleVersion`. Update the changelog, release notes and README's current preview.
2. From a feature branch, run:
   ```sh
   python3 scripts/test-version.py
   python3 scripts/check-version.py
   ./gradlew test createDistributable --console=plain
   ```
3. Exercise the relevant real-emulator flow. Open a PR, pass the required checks and merge into main.
4. Create a matching annotated tag, for example:
   ```sh
   git switch main
   git pull --ff-only
   git tag -a v0.1.0-alpha.1 -m 'Android Emulator Hub 0.1.0-alpha.1'
   git push origin v0.1.0-alpha.1
   ```
5. `Release` validates SemVer/tag consistency, runs tests and builds both packages. It verifies checksums before attaching assets. All 0.x and hyphenated prerelease tags are published with GitHub's prerelease flag and are never marked latest stable. Link users to the Releases list/current alpha, because `/releases/latest` excludes prereleases.
6. Inspect the release assets. CI builds do not replace real emulator validation, particularly on Intel. Investigate failed jobs before retrying; do not move existing tags.

Actions are pinned to upstream commit SHAs. Only the release publishing job has contents-write permission. Pull-request jobs have no signing credentials. See [signing setup](macos-signing.md) before configuring Developer ID/notarization; this preview remains unsigned by Developer ID and unnotarized.

Main is intended to require an up-to-date PR, passing Unit and Compose tests / macOS arm64 / macOS x64 checks and resolved conversations, with force pushes and deletion blocked. A single maintainer may merge their own passing PR without mandatory peer approval. Keep the repository private until explicitly authorized to publish it; no project license has been selected.
