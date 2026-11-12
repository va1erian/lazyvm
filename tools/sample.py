"""Build a sample app: samples/<name>/*.java against lazyrt into dist/samples/<name>.jar,
runnable with `lazyvm[-ui] -jar` (its main class is the file named like the folder).

    python tools/sample.py writer          # dist/samples/writer.jar
    python tools/sample.py counter --no-lazyrt
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import javac  # noqa: E402
import lazyrt  # noqa: E402
from common import BUILD, DIST, LAZYRT_CLASSES, ROOT, die  # noqa: E402

SAMPLES = ROOT / "samples"


def build(name: str, rebuild_lazyrt: bool = True) -> Path:
    """Compile sample `name` and return its jar."""
    sources = sorted((SAMPLES / name).glob("*.java"))
    if not sources:
        die(f"no sample {name} (samples/{name}/*.java)")
    main = name[:1].upper() + name[1:]
    if not (SAMPLES / name / f"{main}.java").is_file():
        die(f"sample {name} has no {main}.java")
    if rebuild_lazyrt or not LAZYRT_CLASSES.is_dir():
        lazyrt.build()
    classes = BUILD / "samples" / name
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    javac.compile(sources, classes, bootclasspath=LAZYRT_CLASSES)
    return lazyrt.write_jar(classes, DIST / "samples" / f"{name}.jar", main_class=main)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("name", help="the folder under samples/")
    parser.add_argument("--no-lazyrt", action="store_true", help="reuse the built lazyrt classes")
    args = parser.parse_args()
    jar = build(args.name, not args.no_lazyrt)
    print(f"{jar.relative_to(ROOT)}: {jar.stat().st_size:,} B")


if __name__ == "__main__":
    main()
