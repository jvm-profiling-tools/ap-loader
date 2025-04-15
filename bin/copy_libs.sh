#! /bin/sh
# ./copy_libs.sh <base_dir> <artifact version, like 2.8.3-macos> [no-clean]

set -e

BASEDIR="$1"
VERSION_PLATFORM=$2
AP_RELEASE="ap-releases/async-profiler-$VERSION_PLATFORM"
OWN_DIR=$(dirname "$0")

cd "$BASEDIR" || exit 1

version=$(echo "$2" | cut -d '-' -f 1)
major_version=$(echo "$version" | cut -d '.' -f 1)
minor_version=$(echo "$version" | cut -d '.' -f 2)
if [ -z "$3" ]; then
  rm -fr target/classes/libs
fi
mkdir -p target/classes/libs

# test endings ".so" and ".dylib" in a loop
for ending in "so" "dylib"; do
  # if the file exists, copy it
  if [ -f "$AP_RELEASE/lib/libasyncProfiler.$ending" ]; then
    echo "Copy $AP_RELEASE/lib/libasyncProfiler.$ending"
    cp "$AP_RELEASE/lib/libasyncProfiler.$ending" \
      "target/classes/libs/libasyncProfiler-$VERSION_PLATFORM.$ending"
    echo "libasyncProfiler-$VERSION_PLATFORM.$ending" > target/classes/libs/ap-profile-lib-$VERSION_PLATFORM
  fi
done

cp "$AP_RELEASE/build/jattach" \
  "target/classes/libs/jattach-$VERSION_PLATFORM"

cp "$AP_RELEASE/build/jattach" \
  "target/classes/libs/jattach-$VERSION_PLATFORM"

cp "$AP_RELEASE/bin/jfrconv" \
  "target/classes/libs/jfrconv-$VERSION_PLATFORM"

python3 "$OWN_DIR/timestamp.py" > "target/classes/libs/ap-timestamp-$version"
echo "$version" > target/classes/libs/ap-version

echo "Copy $AP_RELEASE/bin/asprof"
cp "$AP_RELEASE/bin/asprof" "target/classes/libs/asprof-$VERSION_PLATFORM"

echo "asprof-$VERSION_PLATFORM" > target/classes/libs/ap-profile-script-$VERSION_PLATFORM

echo "Copy Java sources"
python3 "$OWN_DIR/copy_java_sources.py" "$BASEDIR" "$VERSION_PLATFORM"