"""Shared paths and helpers for the LazyVM tools.

Every tool imports this module instead of re-deriving the repo layout, so the
layout lives in one place (docs/decisions.md D10).
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
DIST = ROOT / "dist"
LAZYRT_SRC = ROOT / "java" / "lazyrt" / "src"
LAZYRT_CLASSES = BUILD / "lazyrt" / "classes"
TESTS_SRC = ROOT / "tests" / "java"
TESTS_CLASSES = BUILD / "tests" / "classes"

EXE = ".exe" if os.name == "nt" else ""


def die(message: str) -> None:
    """Print a readable error and exit with status 2."""
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def find_tool(env: str, name: str) -> str:
    """Return the JDK tool named by `env`, else `name` on PATH, else exit."""
    explicit = os.environ.get(env)
    if explicit:
        if not Path(explicit).is_file():
            die(f"{env}={explicit} does not exist")
        return explicit
    found = shutil.which(name)
    if not found:
        die(f"{name} not found: put a JDK (8 or newer) on PATH or set {env}")
    return found


def java_sources(root: Path) -> list[Path]:
    """All .java files under `root`, sorted so builds are reproducible."""
    return sorted(root.rglob("*.java"))


def lazyvm_exe(release: bool = True, target: str | None = None) -> Path:
    """Path of the built launcher (`cargo build -p launcher`)."""
    profile = "release" if release else "debug"
    base = ROOT / "target"
    if target:
        base = base / target
    suffix = "" if target and "linux" in target else EXE
    return base / profile / f"lazyvm{suffix}"


def cargo_build_launcher(release: bool = True) -> Path:
    """Build the launcher and return its path; exit on a build failure."""
    cmd = ["cargo", "build", "-q", "-p", "launcher"]
    if release:
        cmd.append("--release")
    if subprocess.run(cmd, cwd=ROOT).returncode != 0:
        die("cargo build -p launcher failed")
    return lazyvm_exe(release)
