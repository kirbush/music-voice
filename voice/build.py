"""Build the Android ARM64 APK, or run the host tests, with standard SDK tools."""
import argparse
import os
from pathlib import Path
import shutil
import tempfile
import zipfile

from build_support import executable, pinned_file, run, sha256
from native.build import build_native

HERE = Path(__file__).resolve().parent
WORK = HERE.parent / ".build"
API = 30
BUILD_TOOLS = "34.0.0"
NDK = "27.2.12479018"
ABI = "arm64-v8a"
NATIVE = {"libonnxruntime.so", "libsherpa-onnx-jni.so", "libmusicvoice_futo.so"}
SHERPA = (
    "sherpa.aar",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar",
    "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e",
)
KOTLIN = (
    "kotlin-stdlib-1.9.20.jar",
    "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.20/kotlin-stdlib-1.9.20.jar",
    "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c",
)


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT"))
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--java-home", type=Path, default=os.getenv("JAVA_HOME"))
    parser.add_argument("--cache", type=Path, default=WORK / "cache")
    parser.add_argument("--output", type=Path, default=WORK / "MusicVoice-arm64.apk")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--key-alias")
    parser.add_argument("--store-pass-file", type=Path)
    config = parser.parse_args()
    if not config.sdk:
        parser.error("Set ANDROID_HOME or pass --sdk")
    signing = (config.keystore is not None, config.key_alias is not None, config.store_pass_file is not None)
    if (any(signing) and not all(signing)) or (config.unsigned and any(signing)):
        parser.error("Use --unsigned or provide all three signing arguments")
    for name in ("sdk", "build_tools", "ndk", "java_home", "cache", "output", "keystore", "store_pass_file"):
        value = getattr(config, name)
        if value is not None:
            setattr(config, name, value.resolve())
    return config


def jdk(config, name):
    return executable(name, config.java_home / "bin" if config.java_home else None)


def test_sources(config, platform_jar):
    source = HERE / "src/ru/local/voyah/musicsearch"
    tests = HERE / "bench"
    with tempfile.TemporaryDirectory(prefix="musicvoice-tests-") as temp:
        files = [source / (name + ".java") for name in
                 ("SpokenRequest", "TitleCorrection", "ModelStore", "VoiceSettings")]
        files += [tests / (name + ".java") for name in ("TitleCorrectionTest", "ModelStoreTest")]
        run(jdk(config, "javac"), "-encoding", "UTF-8", "-cp", platform_jar, "-d", temp, *files)
        classpath = os.pathsep.join((temp, str(platform_jar)))
        for name in ("TitleCorrectionTest", "ModelStoreTest"):
            run(jdk(config, "java"), "-cp", classpath, "ru.local.voyah.musicsearch." + name)
    print("PASS parsing, Spotify result ranking and model downloader tests")


def package_assets(apk, dex, aar, futo):
    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as archive:
        for item in sorted(dex.glob("*.dex")):
            archive.write(item, item.name)
        for name in sorted(NATIVE - {futo.name}):
            archive.writestr(f"lib/{ABI}/{name}", aar.read(f"jni/{ABI}/{name}"))
        archive.write(futo, f"lib/{ABI}/{futo.name}")
        for name in ("model-files.tsv", "en-pieces.tsv", "ru-pieces.tsv"):
            archive.write(HERE / "assets" / name, f"assets/{name}")
        archive.write(HERE / "assets/keywords.txt", "assets/wake/keywords.txt")
        for license_file in sorted((HERE / "licenses").glob("*.txt")):
            archive.write(license_file, f"assets/licenses/{license_file.name}")


def verify_contents(apk):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        if len(set(entries)) != len(entries) or archive.testzip():
            raise ValueError("Invalid APK ZIP")
        libraries = {entry for entry in entries if entry.startswith("lib/")}
        if libraries != {f"lib/{ABI}/{name}" for name in NATIVE}:
            raise ValueError("Unexpected APK libraries")
        for name in libraries:
            with archive.open(name) as library:
                header = library.read(20)
            if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
                raise ValueError(f"Unexpected library architecture: {name}")
        for name in entries:
            if name.lower().endswith((".bin", ".onnx", ".pcm", ".wav", ".apk", ".p12", ".keystore")):
                raise ValueError(f"Unexpected bundled data: {name}")


def signing_args(config):
    if config.keystore:
        return ("--ks", config.keystore, "--ks-key-alias", config.key_alias,
                "--ks-pass", f"file:{config.store_pass_file}")
    key = WORK / "debug.p12"
    if not key.exists():
        WORK.mkdir(exist_ok=True)
        # This known password is for a per-checkout development key only.
        run(jdk(config, "keytool"), "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=MusicVoice Debug")
    return "--ks", key, "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android"


def build(config, platform_jar):
    tools = config.build_tools or config.sdk / "build-tools" / BUILD_TOOLS
    ndk = config.ndk or config.sdk / "ndk" / NDK
    aapt2 = executable("aapt2", tools)
    zipalign = executable("zipalign", tools)
    java = jdk(config, "java")
    sherpa = pinned_file(config.cache, *SHERPA)
    kotlin = pinned_file(config.cache, *KOTLIN)
    config.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=config.output.parent) as directory:
        work = Path(directory)
        futo = work / "libmusicvoice_futo.so"
        build_native(ndk, config.cache, futo, config.sdk / "cmake/3.22.1/bin")
        for folder in ("classes", "dex", "generated"):
            (work / folder).mkdir()
        shutil.copytree(HERE / "res", work / "res")
        shutil.copyfile(HERE / "AndroidManifest.xml", work / "AndroidManifest.xml")
        shutil.copyfile(platform_jar, work / "android.jar")
        run(aapt2, "compile", "--dir", "res", "-o", "res.zip", cwd=work)
        run(aapt2, "link", "--manifest", "AndroidManifest.xml", "-I", "android.jar",
            "--min-sdk-version", API, "--target-sdk-version", API, "--java", "generated",
            "-o", "unsigned.apk", "res.zip", cwd=work)
        with zipfile.ZipFile(sherpa) as aar:
            jar = Path(aar.extract("classes.jar", work))
            classpath = os.pathsep.join(map(str, [platform_jar, jar, kotlin]))
            run(jdk(config, "javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-Xlint:-options", "-cp", classpath, "-d", work / "classes",
                *sorted((HERE / "src").rglob("*.java")), *sorted((work / "generated").rglob("R.java")))
            run(java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", API,
                "--lib", platform_jar, "--output", work / "dex", jar, kotlin,
                *sorted((work / "classes").rglob("*.class")))
            package_assets(work / "unsigned.apk", work / "dex", aar, futo)
        run(zipalign, "-f", "4", "unsigned.apk", "aligned.apk", cwd=work)
        output = work / "aligned.apk"
        if not config.unsigned:
            output = work / "signed.apk"
            signer = (java, "-jar", tools / "lib/apksigner.jar")
            run(*signer, "sign", "--min-sdk-version", API, "--v4-signing-enabled", "false",
                *signing_args(config), "--out", output, work / "aligned.apk")
            run(*signer, "verify", "--min-sdk-version", API, output)
        verify_contents(output)
        output.replace(config.output)
    print(f"{config.output}: {config.output.stat().st_size} bytes, SHA-256 {sha256(config.output)}")


def main():
    config = options()
    platform = config.sdk / "platforms" / f"android-{API}" / "android.jar"
    if not platform.is_file():
        raise SystemExit(f"Install the Android {API} SDK Platform: {platform}")
    if config.test:
        test_sources(config, platform)
    else:
        build(config, platform)


if __name__ == "__main__":
    main()
