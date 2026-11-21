"""Build the pinned whisper.cpp Android backend."""
import os
from pathlib import Path
import shutil
import tarfile
import tempfile

from build_support import executable, pinned_file, run

SOURCE_TAG = "b5454"
SOURCE_SHA256 = "9aa617f410d9c7f0dae952eb2b77eafdcfb310c2d622cca1f83633fd89a213bc"
HERE = Path(__file__).resolve().parent


def toolchain(ndk):
    cmake_file = ndk / "build/cmake/android.toolchain.cmake"
    if cmake_file.is_file():
        strip = next((ndk / "toolchains/llvm/prebuilt").glob("*/bin/llvm-strip" + (".exe" if os.name == "nt" else "")))
        return [
            f"-DCMAKE_TOOLCHAIN_FILE={cmake_file.as_posix()}",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-30", "-DANDROID_STL=c++_static",
        ], strip
    # Also accept a standalone Clang/sysroot layout on Windows.
    if os.name == "nt" and (ndk / "sysroot").is_dir():
        bin_dir = ndk / "bin"
        clang = executable("clang", bin_dir).as_posix()
        target = "aarch64-linux-android30"
        return [
            "-DCMAKE_SYSTEM_NAME=Linux", "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY", "-DCMAKE_HAVE_LIBC_PTHREAD=1",
            f"-DCMAKE_C_COMPILER={clang}", f"-DCMAKE_CXX_COMPILER={clang}",
            "-DCMAKE_CXX_COMPILER_ARG1=--driver-mode=g++",
            f"-DCMAKE_C_COMPILER_TARGET={target}", f"-DCMAKE_CXX_COMPILER_TARGET={target}",
            f"-DCMAKE_AR={executable('llvm-ar', bin_dir).as_posix()}",
            f"-DCMAKE_RANLIB={executable('llvm-ranlib', bin_dir).as_posix()}",
            f"-DCMAKE_SYSROOT={(ndk / 'sysroot').as_posix()}",
        ], executable("llvm-strip", bin_dir)
    raise FileNotFoundError(f"Install the Android NDK at {ndk} or pass --ndk")


def build_native(ndk, cache, output, sdk_cmake):
    flags, strip = toolchain(ndk)
    cmake = executable("cmake", sdk_cmake if sdk_cmake.is_dir() else None)
    ninja = executable("ninja", sdk_cmake if sdk_cmake.is_dir() else None)
    archive = pinned_file(cache, f"whisper-{SOURCE_TAG}.tar.gz",
                          f"https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/{SOURCE_TAG}",
                          SOURCE_SHA256)
    with tempfile.TemporaryDirectory(prefix="musicvoice-native-") as directory:
        work = Path(directory)
        with tarfile.open(archive) as package:
            package.extractall(work, filter="data")
        source = work / f"whisper.cpp-{SOURCE_TAG}"
        run("git", "apply", HERE / "whisper-language.patch", cwd=source)
        build = work / "build"
        run(
            cmake, "-S", HERE, "-B", build, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}", "-DCMAKE_BUILD_TYPE=Release",
            f"-DWHISPER_SOURCE={source.as_posix()}", *flags,
        )
        run(cmake, "--build", build, "--parallel", "4")
        library = build / "libmusicvoice_futo.so"
        run(strip, "--strip-unneeded", library)
        shutil.copyfile(library, output)
