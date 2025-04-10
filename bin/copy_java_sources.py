#!/usr/bin/python3

"""
./copy_java_sources.py <base_dir> <artifact version, like 2.8.3-macos>
"""

import os
import shutil
import sys
from pathlib import Path
from typing import Tuple, Optional

BASEDIR = Path(sys.argv[1])
RELEASE = sys.argv[2]
VERSION = RELEASE.split("-")[0]

def either(*dirs: Path) -> Path:
    for dir in dirs:
        if dir.exists():
            return dir
    raise AssertionError(f"None of {', '.join(map(str, dirs))} exists")


def either_or_none(*dirs: Path) -> Optional[Path]:
    return ([dir for dir in dirs if dir.exists()] + [None]) [0]


AP_SOURCE_DIR = either(BASEDIR / "ap-releases" / f"async-profiler-{VERSION}-code" / "src")
AP_CONVERTER_SOURCE_DIR = either(AP_SOURCE_DIR / "converter")
AP_RESOURCES_SOURCE_DIR = either_or_none(AP_SOURCE_DIR / "res", AP_SOURCE_DIR / "main" / "resources")
AP_API_SOURCE_DIR = either(AP_SOURCE_DIR / "api" / "one" / "profiler")
TARGET_SOURCE_DIR = either(BASEDIR / "src" / "main" / "java")
TARGET_ONE_DIR = TARGET_SOURCE_DIR / "one"
TARGET_ONE_PROFILER_DIR = either(TARGET_ONE_DIR / "profiler")
TARGET_CONVERTER_DIR = TARGET_ONE_DIR / "converter"
TARGET_RESOURCES_DIR = BASEDIR / "src" / "main" / "resources"


PROJECT_FILES = ["AsyncProfilerLoader.java"]

DRY_RUN = False

os.chdir(BASEDIR)

os.makedirs(TARGET_RESOURCES_DIR, exist_ok=True)

print("Remove old files")
for f in TARGET_ONE_PROFILER_DIR.glob("*"):
    if f.name not in PROJECT_FILES:
        if DRY_RUN:
            print("would remove " + str(f))
        else:
            if f.is_dir():
                f.rmdir()
            else:
                f.unlink()

for f in TARGET_ONE_DIR.glob("*"):
    if not f.is_dir() or f.name == "profiler":
        continue
    if DRY_RUN:
        print("would remove " + str(f))
    else:
        shutil.rmtree(f)

for f in TARGET_SOURCE_DIR.glob("*.java"):
    if DRY_RUN:
        print("would remove " + str(f))
    else:
        f.unlink()

print("Copy api files")
assert next(AP_API_SOURCE_DIR.glob("*.java"), "empty") != "empty", f"{AP_API_SOURCE_DIR} is empty"
for f in AP_API_SOURCE_DIR.glob("*.java"):
    target_file = TARGET_ONE_PROFILER_DIR / f.name
    if DRY_RUN:
        print(f"would copy {f} to {target_file}")
    else:
        shutil.copy(f, target_file)

if AP_RESOURCES_SOURCE_DIR:
    print("Copy converter resource files")
    for f in AP_RESOURCES_SOURCE_DIR.glob("*"):
        target_file = TARGET_RESOURCES_DIR / f.name
        if DRY_RUN:
            print(f"would copy {f} to {target_file}")
        else:
            shutil.copy(f, target_file)

print("Copy converter directories")
for directory in AP_CONVERTER_SOURCE_DIR.glob("one/*"):
    if not directory.is_dir():
        continue
    if DRY_RUN:
        print(f"would copy {directory} to {TARGET_SOURCE_DIR / 'one' / directory.name}")
    else:
        shutil.copytree(directory, TARGET_SOURCE_DIR / 'one' / directory.name)

print("Copy converters")
# Problem: These converters reside in the default package
os.makedirs(TARGET_CONVERTER_DIR, exist_ok=True)
for f in AP_CONVERTER_SOURCE_DIR.glob("*.java"):
    target_file = TARGET_CONVERTER_DIR / f.name
    if DRY_RUN:
        print(f"would modify and copy {f} to {target_file}")
    else:
        with open(f, "r") as source:
            content = "package one.converter;\n" + source.read()
        if f.name == "Main.java":
            assert "java -cp converter.jar" in content or "jfrconv [options]" in content
            content = content.replace("java -cp converter.jar ", "java -cp ap-loader.jar one.converter.")
            content = content.replace("jfrconv [options]", "java -jar ap-loader.jar jfrconv [options]")
        elif "Usage: java " in content:
            content = content.replace("Usage: java ", "Usage: java -cp ap-loader.jar ")
        with open(target_file, "w") as target:
            target.write(content)
