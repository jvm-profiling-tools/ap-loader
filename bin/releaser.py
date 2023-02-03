#! /usr/bin/python3

"""
This is the script to pull async-profiler releases from GitHub, create the wrappers for them (using the POMs) and
to test them.

It has no dependencies, only requiring Python 3.6+ to be installed.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Union, Tuple, Optional
from urllib import request

SUB_VERSION = 4
RELEASE_NOTES = """- `AsyncProfiler.isSupported()` now returns `false` if the OS is not supported by any async-profiler binary, fixes #5"""

HELP = """
Usage:
 .   python3 releaser.py <command> ... <command> [release or current if not present]

Commands:
    current_version   print the youngest released version of async-profiler
    versions          print all released versions of async-profiler (supported by this project)
    download          download and prepare the folders for the given release
    build             build the wrappers for the given release
    test              test the given release
    deploy_mvn        deploy the wrappers for the given release as a snapshot to maven
    deploy_gh         deploy the wrappers for the given release as a snapshot to GitHub
    deploy            deploy the wrappers for the given release as a snapshot
    deploy_release    deploy the wrappers for the given release
    clear             clear the ap-releases and target folders for a fresh start
"""

CURRENT_DIR = os.path.abspath(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))

CACHE_DIR = f"{CURRENT_DIR}/.cache"
TESTS_DIR = f"{CURRENT_DIR}/.tests"
TESTS_CODE_DIR = f"{TESTS_DIR}_code"
CACHE_TIME = 60 * 60 * 24  # one day


def prepare_poms(release: str, platform: str, snapshot: bool = True) -> Tuple[str, str]:
    """ Prepare the POMs for the given release and platform """
    folder = CURRENT_DIR
    os.makedirs(folder, exist_ok=True)
    suffix = f"-{release}{'-SNAPSHOT' if snapshot else ''}"
    for pom in ["pom", "pom_all"]:
        pom_file = f"{CURRENT_DIR}/{pom}.xml"
        dest_pom = f"{folder}/{pom}{suffix}.xml"
        with open(pom_file) as f:
            pom_content = f.read()
            p_suffix = "-SNAPSHOT" if snapshot else ""
            pom_content = re.sub(r"<version>.*</version>", f"<version>{release}-{SUB_VERSION}{p_suffix}</version>", pom_content, count = 1)
            pom_content = pom_content.replace("${project.platform}", platform)
            pom_content = pom_content.replace("${project.artifactId}", f"ap-loader-{platform}")
            pom_content = re.sub(r"<project.vversion>.*</project.vversion>", f"<project.vversion>{release}</project.vversion>", pom_content, count = 1)
            pom_content = re.sub(r"<project.subversion>.*</project.subversion>", f"<project.subversion>{SUB_VERSION}</project.subversion>", pom_content, count = 1)
            pom_content = re.sub(r"<project.platform>.*</project.platform>", f"<project.platform>{platform}</project.platform>", pom_content, count = 1)
            pom_content = re.sub(r"<project.suffix>.*</project.suffix>", f"<project.suffix>{p_suffix}</project.suffix>", pom_content, count = 1)
            with open(dest_pom, "w") as f2:
                f2.write(pom_content)
    return f"{folder}/pom{suffix}.xml", f"{folder}/pom_all{suffix}.xml"


class PreparedPOMs:

    def __init__(self, release: str, platform: str, snapshot: bool = True):
        self.release = release
        self.platform = platform
        self.snapshot = snapshot

    def __enter__(self):
        self.pom, self.pom_all = prepare_poms(self.release, self.platform, self.snapshot)
        return self

    def __exit__(self, *args):
        os.remove(self.pom)
        os.remove(self.pom_all)


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
    return [release["tag_name"][1:] for release in get_releases() if not release["tag_name"][1:].startswith("1.")
            and release["tag_name"][-1].isdigit()]


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
    return f"{CURRENT_DIR}/releases/ap-loader-{platform}-{release}-{SUB_VERSION}.jar"


def build_release(release: str):
    print("Download release files")
    release_folder = CURRENT_DIR + "/releases"
    os.makedirs(release_folder, exist_ok=True)
    download_release(release)
    for platform in get_release_platforms(release):
        release_file = f"ap-loader-{platform}-{release}-{SUB_VERSION}-full.jar"
        dest_release_file = f"{release_folder}/ap-loader-{platform}-{release}-{SUB_VERSION}.jar"
        print(f"Build release for {platform}")
        execute(f"mvn -Duser.name='' -Dproject.vversion={release} -Dproject.subversion={SUB_VERSION} -Dproject.platform={platform} package assembly:single")
        shutil.copy(f"{CURRENT_DIR}/target/{release_file}", dest_release_file)
    all_target = release_target_file(release, "all")
    print("Build release for all")
    execute(f"mvn -Duser.name='' -Dproject.vversion={release} -Dproject.subversion={SUB_VERSION} -Dproject.platform=all package assembly:single -f pom_all.xml")
    shutil.copy(f"{CURRENT_DIR}/target/ap-loader-all-{release}-{SUB_VERSION}-full.jar", all_target)


def build_tests(release: str):
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
            execute(f"javac {test_folder}/{file}")
        if file.endswith(".sh") and not file.startswith("fd"):
            with open(f"{test_folder}/{file}") as f:
                content = f.read()
            content = content \
                .replace("../profiler.sh ", f"java -jar '{release_file}' profiler ") \
                .replace("-agentpath:../build/libasyncProfiler.so", f"-javaagent:{release_file}")
            with open(f"{test_folder}/{file}", "w") as f:
                f.write(content)


def clear_tests_dir():
    for f in os.listdir(TESTS_DIR):
        shutil.rmtree(f"{TESTS_DIR}/{f}", ignore_errors=True)


def test_release_basic_execution(release: str, platform: str, ignore_output=True) -> bool:
    """
    Tests that the agentpath command returns a usable agent on this platform
    """
    release_file = release_target_file(release, platform)
    try:
        pipe = subprocess.PIPE if not ignore_output else subprocess.DEVNULL
        clear_tests_dir()
        agentpath_cmd = f"java -jar '{release_file}' agentpath"
        if not ignore_output:
            print(f"Execute {agentpath_cmd}")
        agentpath = subprocess.check_output(agentpath_cmd, shell=True, stderr=subprocess.DEVNULL).decode().strip()
        if not agentpath.endswith(".so") or not os.path.exists(agentpath):
            print(f"Invalid agentpath: {agentpath}")
            return False
        profile_file = f"{TESTS_DIR}/profile.jfr"
        cmd = f"java -javaagent:{release_file}=start,file={profile_file},jfr " \
              f"-cp {TESTS_CODE_DIR}/test ThreadsTarget"
        if not ignore_output:
            print(f"Execute {cmd}")
        subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=pipe, stderr=pipe)
        if not os.path.exists(profile_file):
            return False
        flamegraph_file = f"{TESTS_DIR}/flamegraph.html"
        cmd = f"java -jar '{release_file}' converter jfr2flame {profile_file} {flamegraph_file}"
        if not ignore_output:
            print(f"Execute {cmd}")
        subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=pipe, stderr=pipe)
        if not os.path.exists(flamegraph_file):
            return False
        return True
    except subprocess.CalledProcessError:
        return False


def test_releases_basic_execution(release: str):
    """ Throws an error if none of the platform JARs works (and the all JAR should also work) """
    print("Test basic execution of javaagent")
    results = [platform for platform in get_release_platforms(release)
               if test_release_basic_execution(release, platform)]
    if not results:
        for platform in get_release_platforms(release):
            print(f"Test release {release} for {platform} failed:")
            test_release_basic_execution(release, platform, ignore_output=False)
        raise Exception(f"None of the platform JARs for {release} works")
    if len(results) > 1:
        raise Exception(f"Multiple platform JARs work for {release}: {results}, this should not be the case")
    if not test_release_basic_execution(release, "all"):
        print(f"Test release {release} for all failed:")
        if not test_release_basic_execution(release, "all", ignore_output=False):
            raise Exception(f"The all JAR for {release} does not work")


def run_async_profiler_test(test_script: str) -> bool:
    print(f"Execute {test_script}")
    cmd = f"sh '{TESTS_CODE_DIR}/test/{test_script}'"
    try:
        subprocess.check_call(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return True
    except BaseException as ex:
        print(f"Test {test_script} failed, {cmd}: {ex}")
        return False


def run_async_profiler_tests():
    print("Run async-profiler tests")
    test_folder = f"{TESTS_CODE_DIR}/test"
    failed = False
    for file in os.listdir(test_folder):
        if file.endswith(".sh") and not file.startswith("fd"):
            if not run_async_profiler_test(file):
                failed = True
    if failed:
        raise Exception("Some async-profiler tests failed")


def test_release(release: str):
    """ Be sure to build it before """
    print("Basic release tests")
    build_tests(release)
    test_releases_basic_execution(release)
    run_async_profiler_tests()


def deploy_maven_platform(release: str, platform: str, snapshot: bool):
    print(f"Deploy {release}-{SUB_VERSION} for {platform} to maven")
    with PreparedPOMs(release, platform, snapshot) as poms:
        pom = poms.pom_all if platform == "all" else poms.pom
        cmd = f"mvn -Duser.name='' -Dproject.vversion={release} -Dproject.subversion={SUB_VERSION} -Dproject.platform={platform} " \
              f"-Dproject.suffix='{'-SNAPSHOT' if snapshot else ''}' -f {pom} clean deploy"
        try:
            subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL)
            #os.system(f"cd {CURRENT_DIR}; {cmd}")
        except subprocess.CalledProcessError:
            os.system(
                f"cd {CURRENT_DIR}; {cmd}")
            raise


def deploy_maven(release: str, snapshot: bool = True):
    print(f"Deploy {release}-{SUB_VERSION}{' snapshot' if snapshot else ''}")
    for platform in get_release_platforms(release):
        deploy_maven_platform(release, platform, snapshot)
    deploy_maven_platform(release, "all", snapshot)


def get_changelog(release: str) -> str:
    url = get_release(release)["html_url"]
    return f"## ap-loader v{SUB_VERSION}\n\n{RELEASE_NOTES}\n\n" \
           f"_The following is copied from the wrapped [async-profiler release]({url}) " \
           f"by [Andrei Pangin](https://github.com/apangin). " \
           f"The source code linked below should be ignored._\n\n{get_release_info(release)}"


def deploy_github(release: str):
    changelog = get_changelog(release)
    is_latest = release == get_most_recent_release()
    title = f"Loader for {release} (v{SUB_VERSION}): {get_release(release)['name']}"
    prerelease = get_release(release)["prerelease"]
    print(f"Deploy {release}-{SUB_VERSION} ({title}) to GitHub")
    if not os.path.exists(f"{CURRENT_DIR}/releases/ap-loader-all-{release}-{SUB_VERSION}.jar"):
        build_release(release)
    with tempfile.TemporaryDirectory() as d:
        changelog_file = f"{d}/CHANGELOG.md"
        with open(changelog_file, "w") as of:
            of.write(changelog)
            of.close()
        releases_dir = f"{CURRENT_DIR}/releases"
        platform_paths = []
        for platform in get_release_platforms(release) + ["all"]:
            path = f"{d}/ap-loader-{platform}.jar"
            shutil.copy(f"{releases_dir}/ap-loader-{platform}-{release}-{SUB_VERSION}.jar", path)
            platform_paths.append(path)

        flags_str = f"-F {changelog_file} -t '{title}' {'--latest' if is_latest else ''}" \
                    f" {'--prerelease' if prerelease else ''}"
        paths_str = " ".join(f'"{p}"' for p in platform_paths)
        cmd = f"gh release create {release}-{SUB_VERSION} {flags_str} {paths_str}"
        try:
            subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            # this is either a real problem or it means that the release already exists
            # in the latter case, we can just update it
            cmd = f"gh release edit {release}-{SUB_VERSION} {flags_str}; gh release upload {release}-{SUB_VERSION} {paths_str} --clobber"
            try:
                subprocess.check_call(cmd, shell=True, cwd=CURRENT_DIR, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL)
            except subprocess.CalledProcessError:
                os.system(
                    f"cd {CURRENT_DIR}; {cmd}")


def deploy(release: str, snapshot: bool = True):
    deploy_maven(release, snapshot)
    deploy_github(release)


def clear():
    shutil.rmtree(f"{CURRENT_DIR}/ap-releases", ignore_errors=True)
    shutil.rmtree(f"{CURRENT_DIR}/releases", ignore_errors=True)
    shutil.rmtree(f"{CURRENT_DIR}/target", ignore_errors=True)
    shutil.rmtree(TESTS_DIR, ignore_errors=True)
    shutil.rmtree(TESTS_CODE_DIR, ignore_errors=True)


def parse_cli_args() -> Tuple[List[str], Optional[str]]:
    available_commands = ["current_version", "versions", "download", "build", "test", "deploy_mvn", "deploy_gh",
                          "deploy",
                          "deploy_release", "clear"]
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
        "deploy_mvn": lambda: deploy_maven(release),
        "deploy_gh": lambda: deploy_github(release),
        "deploy": lambda: deploy(release, snapshot=True),
        "deploy_release": lambda: deploy(release, snapshot=False),
        "clear": clear,
    }
    for command in commands:
        coms[command]()


if __name__ == "__main__":
    cli()
