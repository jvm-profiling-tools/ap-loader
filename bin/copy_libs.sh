#! /bin/sh
# ./copy_libs.sh <base_dir> <artifact version, like 2.8.3-macos>

set -e

BASEDIR="$1"
VERSION_PLATFORM=$2
AP_RELEASE="ap-releases/async-profiler-$VERSION_PLATFORM"
OWN_DIR=$(dirname "$0")

cd "$BASEDIR" || exit 1

version=$(echo "$2" | cut -d '-' -f 1)
rm -fr target/classes/libs
mkdir -p target/classes/libs
cp "$AP_RELEASE/build/libasyncProfiler.so" \
  "target/classes/libs/libasyncProfiler-$VERSION_PLATFORM.so"
cp "$AP_RELEASE/build/jattach" \
  "target/classes/libs/jattach-$VERSION_PLATFORM"

python3 "$OWN_DIR/timestamp.py" > "target/classes/libs/ap-timestamp-$version"
echo "$version" > target/classes/libs/ap-version

echo "Copy $AP_RELEASE/build/converter.jar"
cp "$AP_RELEASE/build/converter.jar" "target/classes/libs/converter-$version.jar"
echo "Copy $AP_RELEASE/profiler.sh"
cp "$AP_RELEASE/profiler.sh" "target/classes/libs/profiler-$version.sh"
python3 "$OWN_DIR/profile_processor.py" "target/classes/libs/profiler-$version.sh"
echo "Extracting $AP_RELEASE/build/async-profiler.jar"
unzip -o "ap-releases/async-profiler-$VERSION_PLATFORM/build/async-profiler.jar" \
  "*.class" -d "target/classes"
