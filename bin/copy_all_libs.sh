#! /bin/sh
# ./copy_all_libs.sh <base_dir> <artifact version, like 2.8.3-all>

set -e

OWN_DIR=$(dirname "$0")
VERSION_PLATFORM=$2
# extract the version without the platform suffix
version=$(echo "$VERSION_PLATFORM" | cut -d '-' -f 1)
echo "version: $version"
cd "$1" || exit 1
rm -fr target/classes/libs
mkdir -p target/classes/libs
for f in ap-releases/async-profiler-$version-*; do
  if (echo "$f" | grep 'code' || echo "$f" | grep ".tar.gz" || echo "$f" | grep ".zip"); then
    continue
  fi
  # extract the platform suffix
  platform=$(echo "$f" | cut -d '-' -f 5-10)
  # copy the library
  $OWN_DIR/copy_libs.sh "$1" "$version-$platform" no-clean
done