"""Shared helpers for building with unmodified Android SDK and JDK tools."""
import hashlib
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def pinned_file(cache, name, url, expected):
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / name
    if path.is_file() and sha256(path) == expected:
        return path
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache, suffix=".download", delete=False) as target:
            temp = Path(target.name)
        run(executable("curl"), "--fail", "--silent", "--show-error", "--location",
            "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "20",
            "--max-time", "300", "--retry", "2", "--output", temp, url)
        if sha256(temp) != expected:
            raise ValueError(f"Unexpected SHA-256 for {name} from {url}")
        temp.replace(path)
        return path
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def run(*command, cwd=None):
    subprocess.run([str(arg) for arg in command], cwd=cwd, check=True)


def executable(name, directory=None):
    filename = name + (".exe" if os.name == "nt" else "")
    if directory:
        path = directory / filename
    else:
        found = shutil.which(filename)
        if not found:
            raise FileNotFoundError(f"{filename} is required on PATH")
        path = Path(found)
    if not path.is_file():
        raise FileNotFoundError(path)
    return path
