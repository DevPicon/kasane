# Build Android Emulator Hub from source

Building locally is useful for inspecting the implementation, trying a change, debugging or producing your own package. It is optional for users of the release packages and is governed by the project's [LICENSE](../LICENSE) (AGPL-3.0, with commercial licensing available from the author).

## Prerequisites

- macOS. CI builds Apple Silicon and Intel separately; cross-compilation is not used.
- JDK 17, available to Gradle (`java -version` / `JAVA_HOME`).
- Python 3.10+ for version validation and package metadata finalization.
- Git.

You do not need a real Android SDK to run the automated tests: they use temporary fixtures. Running the Hub against real emulators requires the SDK tools/images described in the README. The app's Java runtime is bundled during packaging; the SDK's own CLI still needs a working JDK.

## Clone, check and package

```sh
git clone https://github.com/DevPicon/kasane.git
cd kasane
python3 scripts/test-version.py
python3 scripts/check-version.py
./gradlew test createDistributable --console=plain
```

The Gradle wrapper pins Gradle and verifies its distribution checksum. Kotlin/JVM and dependency versions are pinned in the build script.

Output: `build/compose/binaries/main/app/Android Emulator Hub.app`. Tests: `build/reports/tests/test/index.html` and `build/test-results/test/`. Launch the packaged executable from your terminal to inherit your SDK environment:

```sh
'./build/compose/binaries/main/app/Android Emulator Hub.app/Contents/MacOS/Android Emulator Hub'
```

`./gradlew run` is the quicker development loop. Close the running application before rebuilding its package. Closing the Hub shuts down the emulators it lists, including externally started instances.

## Icon source

The original icon is in `assets/icons/hub.svg`; the checked-in `.icns` and PNG are generated assets used by normal builds. Regeneration requires macOS `iconutil` and `rsvg-convert` from librsvg:

```sh
./scripts/build-icon.sh
```

Normal builds do not require that renderer. Keep the vector and generated assets together when changing the icon.

## Versions and signatures

`appVersion` is public SemVer; `macBundleVersion` is a separate positive, increasing build counter. JDK 17's macOS packager requires a positive internal app-version. The build finalizes the bundle with the product's numeric short version and full prerelease metadata, then refreshes and verifies its ad-hoc signature. This is not Developer ID signing and does not produce notarization.

The finalizer refuses to alter a bundle already signed with an identity. Any later Developer ID signing must happen after metadata finalization. See [release policy](releases.md) and [signing setup](macos-signing.md).

## Private test DMG

Run `./gradlew packageTestDmg` on macOS. It wraps the finalized, ad-hoc signed app in a compressed read-only disk image for the host architecture, verifies it with `hdiutil verify`, and writes a SHA-256 file alongside it under `build/dmg/`. The volume contains the app, an Applications shortcut and test instructions. No Android SDK, system images or AVDs are bundled. The image is for private evaluation and is not Developer ID signed or notarized.

Install by dragging the app to Applications, ejecting the image and opening the installed copy. The full version and author appear under Help → Acerca de… and the native macOS About menu.

For Developer ID builds and Apple notarization, use the explicit local flow in [macOS signing](macos-signing.md). `KASANE_SIGNING_IDENTITY` is a public certificate name; passwords remain in the Keychain. The normal CI/test packaging path stays credential-free.

Close any app running directly from `build/compose/binaries/main/app/` before rebuilding its distribution. Packaging checks whether that exact launcher is in use and stops rather than overwriting a live Java runtime/JAR. Installed copies in other paths can remain open.
