#! /bin/sh
# ./copy_java_file.sh <base_dir> <artifact version, like 2.8.3-all>

set -e

OWN_DIR=$(dirname "$0")

# extract the version without the platform suffix
version=$(echo "$2" | cut -d '-' -f 1)
echo "version: $version"
cd "$1" || exit 1

cp ap-releases/async-profiler-$version-code/src/api/one/profiler/*.java src/main/java/one/profiler
