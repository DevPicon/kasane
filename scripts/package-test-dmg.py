#!/usr/bin/env python3
"""Package the already finalized macOS app without changing its signature."""
import hashlib
import pathlib
import platform
import shutil
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
properties = dict(line.strip().split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                  if "=" in line and not line.lstrip().startswith("#"))
name = properties["appDisplayName"]
version = properties["appVersion"]
arch = {"arm64": "arm64", "x86_64": "x64"}.get(platform.machine())
if platform.system() != "Darwin" or arch is None:
    raise SystemExit("A macOS arm64 or x64 host is required.")
bundle = root / "build/compose/binaries/main/app" / f"{name}.app"
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(bundle)], check=True)
output = root / "build/dmg" / f"kasane-{version}-macos-{arch}.dmg"
output.parent.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory(prefix="kasane-dmg-") as temporary:
    staging = pathlib.Path(temporary) / "content"
    staging.mkdir()
    # Copy only the finalized app, never the checkout, SDK or developer settings.
    shutil.copytree(bundle, staging / bundle.name, symlinks=True)
    (staging / "Applications").symlink_to("/Applications", target_is_directory=True)
    (staging / "LEEME.txt").write_text(
        f"{name} {version} — prueba privada para macOS {arch}\n\n"
        "Arrastra la aplicación a Applications, expulsa este volumen y abre la copia instalada.\n"
        "Incluye Java para la app. Android SDK, Emulator y Command-line Tools se instalan aparte;\n"
        "las herramientas del SDK requieren su propio JDK. No incluye AVDs ni imágenes Android.\n\n"
        "Esta preview tiene firma ad hoc, sin Developer ID ni notarización de Apple.\n"
        "macOS puede pedir autorización explícita de la persona que la prueba.\n"
        "No desactives Gatekeeper. Guía oficial de Apple:\n"
        "https://support.apple.com/en-us/102445\n\n"
        "Al cerrar el Hub se apagan los emuladores que figuran en su inventario, incluso los iniciados externamente.\n"
        "Indica la versión de Acerca de, modelo de Mac y pasos para reproducir cualquier problema.\n\n"
        "Autor: Armando Picón — @devpicon en todas las redes.\n"
        "Copia privada de evaluación; no se ha otorgado una licencia para el código del proyecto.\n"
        "Los componentes de terceros conservan sus propias licencias.\n",
        encoding="utf-8",
    )
    # Remove only our previous local build artifact. Published releases stay immutable.
    output.unlink(missing_ok=True)
    output.with_suffix(".dmg.sha256").unlink(missing_ok=True)
    subprocess.run(["hdiutil", "create", "-volname", name, "-srcfolder", str(staging),
                    "-format", "UDZO", "-fs", "HFS+", str(output)], check=True)
subprocess.run(["hdiutil", "verify", str(output)], check=True)
hasher = hashlib.sha256()
with output.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        hasher.update(chunk)
digest = hasher.hexdigest()
output.with_suffix(".dmg.sha256").write_text(f"{digest}  {output.name}\n")
print(f"Verified private test DMG: {output}")
