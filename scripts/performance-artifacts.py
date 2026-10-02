"""Reproduce packaged dependency sizes and validate all nested-mod declarations.

python scripts/performance-artifacts.py build/libs/skymyce-2.4.12.jar build/libs/Sky-Myce-Loader.jar
Nested byte counts overlap their parents; do not add them to the artifact size.
"""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZipFile


def inspect(data, name, depth=0):
    if depth > 8:
        raise ValueError("Unexpected dependency nesting depth")
    with ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate zip entries: {name}")
        metadata = json.loads(archive.read("fabric.mod.json")) if "fabric.mod.json" in names else {}
        row = {"path": name, "bytes": len(data), "id": metadata.get("id"),
               "version": metadata.get("version"), "depends": metadata.get("depends", {}), "nested": []}
        for declaration in metadata.get("jars", []):
            entry = archive.getinfo(declaration["file"])
            nested = inspect(archive.read(entry), name + "!/" + entry.filename, depth + 1)
            nested["compressed_in_parent"] = entry.compress_size
            row["nested"].append(nested)
        return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jars", type=Path, nargs="+")
    arguments = parser.parse_args()
    print(json.dumps([inspect(path.read_bytes(), path.name) for path in arguments.jars], indent=2))


if __name__ == "__main__":
    main()
