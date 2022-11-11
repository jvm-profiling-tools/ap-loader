#!/usr/bin/python3

"""
./copy_java_sources.py <base_dir> <artifact version, like 2.8.3-macos>
"""

import os
import shutil
import sys
from pathlib import Path

BASEDIR = Path(sys.argv[1])
RELEASE = sys.argv[2]
VERSION = RELEASE.split("-")[0]
AP_SOURCE_DIR = BASEDIR / "ap-releases" / f"async-profiler-{VERSION}-code" / "src"
AP_CONVERTER_SOURCE_DIR = AP_SOURCE_DIR / "converter"
AP_API_SOURCE_DIR = AP_SOURCE_DIR / "api" / "one" / "profiler"
TARGET_SOURCE_DIR = BASEDIR / "src" / "main" / "java"
TARGET_ONE_DIR = TARGET_SOURCE_DIR / "one"
TARGET_ONE_PROFILER_DIR = TARGET_ONE_DIR / "profiler"
TARGET_CONVERTER_DIR = TARGET_ONE_DIR / "converter"

assert AP_SOURCE_DIR.exists(), f"Source directory {AP_SOURCE_DIR} does not exist"
assert AP_CONVERTER_SOURCE_DIR.exists(), f"Source directory {AP_CONVERTER_SOURCE_DIR} does not exist"
assert AP_API_SOURCE_DIR.exists()
assert TARGET_SOURCE_DIR.exists()
assert TARGET_ONE_PROFILER_DIR.exists()

PROJECT_FILES = ["AsyncProfilerLoader.java"]

DRY_RUN = False

os.chdir(BASEDIR)

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
            assert "java -cp converter.jar" in content
            content = content.replace("java -cp converter.jar ", "java -cp ap-loader.jar one.converter.")
        elif "Usage: java " in content:
            content = content.replace("Usage: java ", "Usage: java -cp ap-loader.jar ")
        with open(target_file, "w") as target:
            target.write(content)
