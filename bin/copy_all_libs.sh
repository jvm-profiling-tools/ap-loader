#! /bin/sh
# ./copy_all_libs.sh <base_dir> <artifact version, like 2.8.3-all

set -e

# extract the version without the platform suffix
version=$(echo "$2" | cut -d '-' -f 1)
echo "version: $version"
cd "$1" || exit 1
mkdir -p target/classes/libs
for f in ap-releases/async-profiler-$version-*; do
  if (echo "$f" | grep 'code' || echo "$f" | grep ".tar.gz" || echo "$f" | grep ".zip"); then
    continue
  fi
  # extract the platform suffix
  platform=$(echo "$f" | cut -d '-' -f 5-10)
  # copy the library
  echo "Copying $f/build/libasyncProfiler.so for platform $platform"
  cp "$f/build/libasyncProfiler.so" "target/classes/libs/libasyncProfiler-$platform.so"
  echo "Copying $f/build/jattach for platform $platform"
  cp "$f/build/jattach" "target/classes/libs/jattach-$platform"
  echo "Copying $f/profiler.sh for platform $platform"
  cp "$f/profiler.sh" "target/classes/libs/profiler-$platform.sh"
done
first_folder=$(echo ap-releases/async-profiler-$version-linux* | cut -d " " -f 1)
# copy the converter
echo "Copy $first_folder/build/converter.jar"
cp "$first_folder/build/converter.jar" "target/classes/libs/converter.jar"
# extract the async-profiler JAR
echo "Extracting $first_folder/build/async-profiler.jar"
ls target/classes/libs >target/classes/libs/index
unzip -o $first_folder/build/async-profiler* "*.class" -d target/classes
