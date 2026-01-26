#! /bin/sh
# ./copy_none.sh <base_dir>
# stores the build mode "none"

set -e

OWN_DIR=$(dirname "$0")

cd "$BASEDIR" || exit 1

mkdir -p target/classes/libs

echo "none" > target/classes/libs/ap-profile-script-build