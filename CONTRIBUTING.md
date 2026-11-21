# Contributing to Kasane

Kasane is maintained by Armando Picón (@devpicon) and is dual-licensed under **AGPL-3.0** (see [LICENSE](LICENSE)) and a **Commercial License** for proprietary or closed-source commercial use. Contributions are welcome! By submitting a pull request, you agree that your contribution is licensed under AGPL-3.0 and that the maintainer may also include your contribution under the commercial license.

For a bug, include reproducible steps, Kasane version, macOS version/architecture, Emulator version, AVD profile and Android API. Never attach endpoint registration files or gRPC tokens. Remove private application data from screenshots and logs.

Keep changes focused. Separate UI/state coordination, platform tools and domain rules; use suspending APIs below the UI boundary and preserve cancellation. Device capabilities come from metadata rather than device names.

Use JDK 17 and Python 3.10+, then run:

```sh
python3 scripts/test-version.py
python3 scripts/check-version.py
./gradlew test createDistributable --console=plain
```

Tests must use temporary SDK/AVD fixtures. Changes to emulator behavior also need an explicit real-device walkthrough with observed results and limitations. Test success does not establish that a real emulator boots on every supported Mac.

For visible changes, include a screenshot without private data. Explain the user-facing change, verification and remaining risks in the pull request. Keep planning documents, assistant instructions/state, SDK installations, credentials, generated files and binaries out of Git. Distribution files belong in Actions artifacts and Releases.

Work through a feature branch and pull request. Main requires the unit/Compose check and both macOS package checks, an up-to-date branch and resolved conversations. Force pushes and branch deletion are blocked. The sole maintainer can merge their own passing PR without a second reviewer; add required peer approval when the maintainer team grows.

The product name is provisional Android Emulator Hub; Kasane remains the repository codename. Use SemVer 0.x during development, and increment the independent macOS build counter for every distributed build. See [release policy](docs/releases.md).
