#!/usr/bin/env python3
"""Create the dedicated macOS test AVD from Android Studio's official Pixel Fold profile."""

import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="AndroidHub_Pixel_Fold_API_36_1")
    parser.add_argument("--sdk", type=Path, default=Path.home() / "Library/Android/sdk")
    parser.add_argument("--studio", type=Path, default=Path.home() / "Applications/Android Studio.app")
    args = parser.parse_args()
    avd_home = Path.home() / ".android/avd"
    config = avd_home / (args.name + ".avd/config.ini")
    index = avd_home / (args.name + ".ini")
    if config.exists() or index.exists():
        raise SystemExit("El AVD ya existe. Revisa su configuración antes de cambiarlo.")

    image = "system-images;android-36.1;google_apis_playstore;arm64-v8a"
    if not (args.sdk / "system-images/android-36.1/google_apis_playstore/arm64-v8a/system.img").is_file():
        raise SystemExit("Instala primero la imagen ARM64 Google Play de Android 36.1 con SDK Manager.")
    schema = "http://schemas.android.com/sdk/devices/7"
    with zipfile.ZipFile(args.studio / "Contents/plugins/android/lib/sdklib.jar") as archive:
        source = ET.fromstring(archive.read("com/android/sdklib/devices/nexus.xml"))
    namespace = source.tag.split("}")[0] + "}"
    device = next(device for device in source if device.findtext(namespace + "id") == "pixel_fold")
    # The installed CLI supports schema 7. Adapt the container format while retaining display/hinge data.
    for node in device.iter():
        node.tag = "{" + schema + "}" + node.tag.split("}")[-1]
    ns = {"d": schema}
    hardware = device.find("d:hardware", ns)
    networking = hardware.find("d:networking", ns)
    networking.text = " ".join(value for value in networking.text.split() if value != "UWB")
    abis = hardware.find("d:abis", ns)
    if abis is not None:
        position = list(hardware).index(abis)
        hardware.remove(abis)
        for offset, value in enumerate(abis.text.split()):
            node = ET.Element("{" + schema + "}abi")
            node.text = value
            hardware.insert(position + offset, node)
    root = ET.Element("{" + schema + "}devices")
    root.append(device)
    profile_home = Path(__file__).resolve().parents[1] / "build/avd-profile"
    profile_home.mkdir(parents=True, exist_ok=True)
    ET.register_namespace("d", schema)
    ET.ElementTree(root).write(profile_home / "devices.xml", encoding="utf-8", xml_declaration=True)
    environment = dict(os.environ, ANDROID_USER_HOME=str(profile_home), ANDROID_AVD_HOME=str(avd_home))
    subprocess.run([
        str(args.sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd",
        "--name", args.name, "--package", image, "--device", "pixel_fold",
    ], input="no\n", text=True, check=True, env=environment)
    values = dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line)
    values = {key.strip(): value.strip() for key, value in values.items()}
    values.pop("disk.dataPartition.path", None)
    values.update({
        "AvdId": args.name, "avd.id": args.name, "avd.name": args.name,
        "avd.ini.displayname": "Android Hub · Pixel Fold API 36.1",
        "disk.dataPartition.size": "6G", "PlayStore.enabled": "true", "vm.heapSize": "384",
        "hw.ramSize": "3072", "hw.cpu.ncore": "2", "hw.keyboard": "yes",
        "hw.gpu.enabled": "yes", "hw.gpu.mode": "auto", "fastboot.forceColdBoot": "yes",
        "firstboot.bootFromDownloadableSnapshot": "no",
    })
    config.write_text("".join(key + "=" + value + "\n" for key, value in values.items()))
    index.write_text(index.read_text().replace("target=android-0", "target=android-36.1"))
    print("AVD creado en", config.parent)


if __name__ == "__main__":
    main()
