#! /usr/bin/python3

"""
This is the script to pull async-profiler releases from GitHub, create the wrappers for them (using the POMs) and
to test them.

It has no dependencies, only requiring Python 3.6+ to be installed.
"""
import sys
import traceback
import os
import shutil
import subprocess
from urllib import request
from typing import Any, Dict, List, Union, Tuple, Optional
import json
import time

HELP = """
Usage:
    python3 releaser.py <command> ... <command> [release or current if not present]

Commands:
    current_version   print the youngest released version of async-profiler
    versions          print all released versions of async-profiler (supported by this project)
    download          download and prepare the folders for the given release
    build             build the wrappers for the given release
    test              test the given release
    deploy            deploy the wrappers for the given release, i.e., use "mvn deploy"
    clear             clear the ap-releases and target folders for a fresh start

Environment variables:
    JAVA_VERSIONS  comma-separated list of Java versions to test with
                   (in the form of sdkman! version names, e.g. 17.0.1-sapmchn)
"""

CURRENT_DIR = os.path.abspath(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))

CACHE_DIR = f"{CURRENT_DIR}/.cache"
TESTS_DIR = f"{CURRENT_DIR}/.tests"
TESTS_CODE_DIR = f"{TESTS_DIR}_code"
CACHE_TIME = 60 * 60 * 24  # one day


def execute(args: Union[List[str], str]):
    subprocess.check_call(args, cwd=CURRENT_DIR, shell=isinstance(args, str), stdout=subprocess.DEVNULL)


def download_json(url, path: str) -> Any:
    """ Download the JSON file from the given URL and save it to the given path, return the JSON object """
    if not os.path.exists(CACHE_DIR):
        os.makedirs(CACHE_DIR)

    cache_path = f"{CACHE_DIR}/{path}"

    if not os.path.exists(cache_path) or os.path.getmtime(cache_path) + CACHE_TIME <= time.time():
        request.urlretrieve(url, cache_path)

    with open(cache_path) as f:
        return json.load(f)


def get_releases() -> List[Dict[str, Any]]:
    """ Return the releases of async-profiler from the GitHub API """
    return download_json("https://api.github.com/repos/jvm-profiling-tools/async-profiler/releases", "releases.json")


def get_release(release: str) -> Dict[str, Any]:
    rel = [release_dict for release_dict in get_releases() if release_dict["tag_name"] == "v" + release]
    if rel:
        return rel[0]
    print(f"Release {release} not found, available releases are: {', '.join(get_release_versions())}", file=sys.stderr)
    sys.exit(1)


def get_release_versions() -> List[str]:
    return [release["tag_name"][1:] for release in get_releases() if not release["tag_name"][1:].startswith("1.")]


def get_most_recent_release() -> str:
    return [version for version in get_release_versions() if version.startswith("2.")][0]


def get_release_info(release: str) -> str:
    return get_release(release)["body"]


def get_release_asset_urls(release: str) -> List[str]:
    return [url for url in (asset["browser_download_url"] for asset in get_release(release)["assets"])
            if url.endswith(".zip") or url.endswith(".tar.gz")]


def release_platform_for_url(archive_url: str) -> str:
    return archive_url.split("/")[-1].split("-", maxsplit=3)[3].split(".")[0]


def get_release_platforms(release: str) -> List[str]:
    return [release_platform_for_url(url) for url in get_release_asset_urls(release)]


def download_release_url(release: str, release_url: str):
    """ Download the release from the given URL and unpack it """
    dest_folder = f"{CURRENT_DIR}/ap-releases"
    os.makedirs(dest_folder, exist_ok=True)
    ending = [ending for ending in [".zip", ".tar.gz"] if release_url.endswith(ending)][0]
    archive_file = f"{dest_folder}/async-profiler-{release}-{release_platform_for_url(release_url)}{ending}"
    if not os.path.exists(archive_file):
        request.urlretrieve(release_url, archive_file)
    if release_url.endswith(".zip"):
        execute(["unzip", "-o", archive_file, "-d", dest_folder])
    elif release_url.endswith(".tar.gz"):
        execute(["tar", "-xzf", archive_file, "-C", dest_folder])
    else:
        raise Exception("Unknown archive type for " + release_url)


def download_release_code(release: str):
    """ Download the release code from GitHub """
    dest_folder = f"{CURRENT_DIR}/ap-releases/async-profiler-{release}-code"
    archive_file = dest_folder + ".zip"
    if not os.path.exists(archive_file):
        request.urlretrieve(f"https://github.com/jvm-profiling-tools/async-profiler/archive/refs/tags/v{release}.zip",
                            archive_file)
    execute(["unzip", "-o", archive_file, "-d", f"{CURRENT_DIR}/ap-releases"])
    shutil.rmtree(dest_folder, ignore_errors=True)
    shutil.move(f"{CURRENT_DIR}/ap-releases/async-profiler-{release}", dest_folder)
    os.makedirs(f"{dest_folder}/build", exist_ok=True)


def download_release(release: str):
    for release_url in get_release_asset_urls(release):
        download_release_url(release, release_url)
    download_release_code(release)


def release_target_file(release: str, platform: str):
    return f"{CURRENT_DIR}/releases/ap-loader-{release}-{platform}.jar"


def build_release(release: str):
    print("Download release files")
    RELEASE_FOLDER = CURRENT_DIR + "/releases"
    os.makedirs(RELEASE_FOLDER, exist_ok=True)
    download_release(release)
    for platform in get_release_platforms(release):
        release_file = f"ap-loader-{release}-{platform}-full.jar"
        dest_release_file = f"{RELEASE_FOLDER}/ap-loader-{release}-{platform}.jar"
        print(f"Build release for {platform}")
        execute(f"mvn -Dproject.versionPlatform={release}-{platform} package assembly:single")
        shutil.copy(f"{CURRENT_DIR}/target/{release_file}", dest_release_file)
    all_target = release_target_file(release, "all")
    print("Build release for all")
    execute(f"mvn -Dproject.versionPlatform={release}-all package assembly:single -f pom_all.xml")
    shutil.copy(f"{CURRENT_DIR}/target/ap-loader-{release}-all-full.jar", all_target)


def set_java_command(java_version: Optional[str]):
    return f"sdk use java {java_version};" if java_version else ""


def build_tests(release: str, java_cmd: str = ""):
    print("Build tests")
    shutil.rmtree(TESTS_CODE_DIR, ignore_errors=True)
    subprocess.check_call(f"java -jar {release_target_file(release, 'all')} clear", shell=True)
    os.makedirs(TESTS_DIR, exist_ok=True)
    code_folder = f"{CURRENT_DIR}/ap-releases/async-profiler-{release}-code"
    release_file = release_target_file(release, "all")
    shutil.copytree(code_folder, TESTS_CODE_DIR)
    test_folder = f"{TESTS_CODE_DIR}/test"
    for file in os.listdir(test_folder):
        if file.endswith(".java"):
            execute(f"{java_cmd} javac {test_folder}/{file}")
        if file.endswith(".sh") and not file.startswith("fd"):
            with open(f"{test_folder}/{file}") as f:
                content = f.read()
            content = content\
                .replace("../profiler.sh ", f"java -jar '{release_file}' profiler ")\
                .replace("-agentpath:../build/libasyncProfiler.so", f"-javaagent:{release_file}")
            with open(f"{test_folder}/{file}", "w") as f:
                f.write(content)


def clear_tests_dir():
    for f in os.listdir(TESTS_DIR):
        shutil.rmtree(f"{TESTS_DIR}/{f}", ignore_errors=True)


def test_release_basic_execution(release: str, platform: str, ignore_output=True,
                                 java_cmd: str = "") -> bool:
    """
    Tests that the agentpath command returns a usable agent on this platform
    """
    release_file = release_target_file(release, platform)
    try:
        pipe = subprocess.PIPE if not ignore_output else subprocess.DEVNULL
        clear_tests_dir()
        agentpath_cmd = f"{java_cmd}java -jar '{release_file}' agentpath"
        if not ignore_output:
            print(f"Execute {agentpath_cmd}")
        agentpath = subprocess.check_output(agentpath_cmd, shell=True, stderr=subprocess.DEVNULL).decode().strip()
        if not agentpath.endswith(".so") or not os.path.exists(agentpath):
            print(f"Invalid agentpath: {agentpath}")
            return False
        profile_file = f"{TESTS_DIR}/profile.html"
        cmd = f"{java_cmd} java -javaagent:{release_file}=start,file={profile_file} " \
              f"-cp {TESTS_CODE_DIR}/test ThreadsTarget"
        if not ignore_output:
            print(f"Execute {cmd}")
        subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=pipe, stderr=pipe)
        if not os.path.exists(profile_file):
            return False
        return True
    except subprocess.CalledProcessError:
        return False


def test_releases_basic_execution(release: str, java_cmd: str = "") -> bool:
    """ Throws an error if none of the platform JARs works (and the all JAR should also work) """
    print("Test basic execution of javaagent")
    results = [platform for platform in get_release_platforms(release)
               if test_release_basic_execution(release, platform, java_cmd=java_cmd)]
    if not results:
        for platform in get_release_platforms(release):
            print(f"Test release {release} for {platform} failed:")
            test_release_basic_execution(release, platform, ignore_output=False, java_cmd=java_cmd)
        raise Exception(f"None of the platform JARs for {release} works")
    if len(results) > 1:
        raise Exception(f"Multiple platform JARs work for {release}: {results}, this should not be the case")
    if not test_release_basic_execution(release, "all", java_cmd=java_cmd):
        print(f"Test release {release} for all failed:")
        if not test_release_basic_execution(release, "all", ignore_output=False, java_cmd=java_cmd):
            raise Exception(f"The all JAR for {release} does not work")


def run_async_profiler_test(test_script: str, java_cmd: str = "") -> bool:
    print(f"Execute {test_script}")
    cmd = f"{java_cmd} sh '{TESTS_CODE_DIR}/test/{test_script}'"
    try:
        subprocess.check_call(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return True
    except BaseException as ex:
        print(f"Test {test_script} failed, {cmd}: {ex}")
        return False


def run_async_profiler_tests(release: str, java_cmd: str = ""):
    print("Run async-profiler tests")
    test_folder = f"{TESTS_CODE_DIR}/test"
    failed = False
    for file in os.listdir(test_folder):
        if file.endswith(".sh") and not file.startswith("fd"):
            if not run_async_profiler_test(file, java_cmd=java_cmd):
                failed = True
    if failed:
        raise Exception("Some async-profiler tests failed")


def test_release_for_java(release: str, with_java_version: Optional[str] = None):
    """ Be sure to build it before """
    print("Basic release tests")
    java_cmd = set_java_command(with_java_version)
    build_tests(release, java_cmd)
    test_releases_basic_execution(release, java_cmd=java_cmd)
    run_async_profiler_tests(release, java_cmd=java_cmd)


def test_release(release: str):
    """ Be sure to build it before """
    versions = os.getenv("JAVA_VERSIONS", "")
    if versions:
        failed = False
        for version in versions.split(","):
            print(f"Test release {release} with Java {version}")
            try:
                test_release_for_java(release, version)
            except:
                print(f"Test release {release} with Java {version} failed: {traceback.format_exc()}")
                failed = True
        if failed:
            raise Exception(f"Test release {release} failed")
    else:
        test_release_for_java(release)


def clear():
    shutil.rmtree(f"{CURRENT_DIR}/ap-releases", ignore_errors=True)
    shutil.rmtree(f"{CURRENT_DIR}/releases", ignore_errors=True)
    shutil.rmtree(f"{CURRENT_DIR}/target", ignore_errors=True)
    shutil.rmtree(TESTS_DIR, ignore_errors=True)
    shutil.rmtree(TESTS_CODE_DIR, ignore_errors=True)


def parse_cli_args() -> Tuple[List[str], Optional[str]]:
    available_commands = ["current_version", "versions", "download", "build", "test", "deploy",
                          "deploy-release", "clear"]
    commands = []
    release = sys.argv[-1] if sys.argv[-1][0].isnumeric() else None
    for arg in sys.argv[1:(-1 if release else None)]:
        if arg not in available_commands:
            print(f"Unknown command: {arg}")
            print(HELP)
            sys.exit(1)
        commands.append(arg)
    if not commands:
        print(HELP)
    if not release:
        release = get_most_recent_release()
    return commands, release


def cli():
    commands, release = parse_cli_args()
    coms = {
        "current_version": lambda: print(get_most_recent_release()),
        "versions": lambda: print(" ".join(version for version in get_release_versions() if version.startswith("2."))),
        "download": lambda: download_release(release),
        "build": lambda: build_release(release),
        "test": lambda: test_release(release),
        "clear": clear,
    }
    for command in commands:
        coms[command]()


if __name__ == "__main__":
    cli()
