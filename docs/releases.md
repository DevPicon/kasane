# Releasing Kasane

The `Tests` and `Build` workflows run on main and pull requests. Build produces separate Apple Silicon and Intel ZIPs containing `Kasane.app`, plus SHA-256 checksums. Actions artifacts and test reports are retained for 14 days.

For a release:

1. Set `appVersion` in `gradle.properties` to the next numeric `major.minor.patch` version.
2. Update `CHANGELOG.md` and `docs/release-notes.md`. Run local tests, package, and the relevant emulator walkthrough.
3. Merge the reviewed change into main, then create an annotated tag matching that version:
   ```sh
   git tag -a v1.0.0 -m 'Kasane 1.0.0'
   git push origin main
   git push origin v1.0.0
   ```
4. `Release` checks the version, runs tests, builds both architectures, verifies checksums and creates the GitHub release with its assets. If a job fails, no release is published. Investigate before retrying; do not move existing version tags.
5. Download and inspect the release assets. CI package success does not replace real emulator validation, especially on Intel.

Workflow actions are pinned to verified upstream commit SHAs. The release job alone gets write access to repository contents; pull requests do not publish releases. Keep the repository private until the author explicitly changes its visibility. No project license is selected yet.

The first preview is unsigned and unnotarized. Developer ID signing and notarization require a later setup with author-owned Apple credentials. Do not put certificates or secrets in the repository.
