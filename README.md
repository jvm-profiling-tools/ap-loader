Loader for AsyncProfiler
========================

[![Maven Central](https://img.shields.io/maven-central/v/me.bechberger/ap-loader-all)](https://search.maven.org/search?q=ap-loader) [![GitHub](https://img.shields.io/github/license/jvm-profiling-tools/ap-loader)](https://github.com/jvm-profiling-tools/ap-loader/blob/main/LICENSE)

Packages [async-profiler](https://github.com/jvm-profiling-tools/async-profiler) releases in a JAR
with an `AsyncProfilerLoader` (version 3.*, 2.* and 1.8.*)
that loads the suitable native library for the current platform.

*In 3.* it also includes the latest [jattach](https://github.com/apangin/jattach) binary. This was previously
part of async-profiler.*

This is usable as a Java agent (same arguments as the async-profiler agent) and as the basis for other libraries.
The real rationale behind this library is that the async-profiler is a nice tool, but it cannot be easily integrated
into other Java-based tools.

The `AsyncProfilerLoader` API integrates async-profiler and jattach with a user-friendly interface (see below).

The wrapper is tested against all relevant tests of the async-profiler tool, ensuring that it has the same behavior.

Take the [`all` build](https://github.com/jvm-profiling-tools/ap-loader/releases/latest/download/ap-loader-all.jar) and you have a JAR that provides the important features of async-profiler on all supported
platforms.

A changelog can be found in the async-profiler repository, as this library should rarely change itself.

_This project assumes that you used async-profiler before, if not, don't worry, you can still use this project,
but be aware that its documentation refers you to the async-profiler documentation a lot._

_fdtransfer is currently not supported, feel free to create an issue if you need it._

I wrote two blog posts about this project:

1. [AP-Loader: A new way to use and embed async-profiler](https://mostlynerdless.de/blog/2022/11/21/ap-loader-a-new-way-to-use-and-embed-async-profiler/)
2. [Using Async-Profiler and Jattach Programmatically with AP-Loader](https://mostlynerdless.de/blog/2023/05/02/using-async-profiler-and-jattach-programmatically-with-ap-loader/)

Download
--------

You can download the latest release from the 
[latest release page](https://github.com/jvm-profiling-tools/ap-loader/releases/latest/).
As a shortcut, the wrapper for all platforms can be found 
[here](https://github.com/jvm-profiling-tools/ap-loader/releases/latest/download/ap-loader-all.jar).

It should be up-to-date with the latest async-profiler release, but if not, feel free to create an issue.

To use the library as a dependency, you can depend on `me.bechberger.ap-loader-<variant>:<version>-<ap-loader-version>`
from maven central, e.g:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>ap-loader-all</artifactId>
    <version>3.0-8</version>
</dependency>
```

Others are of course available, see [maven central](https://central.sonatype.com/artifact/me.bechberger/ap-loader-all/3.0-8).

You can also use [JBang](https://jbang.dev) to simplify the usage of ap-loader. There are examples in documentation below.

Supported Platforms
-------------------

- Required Java version: 8 or higher
- Supported OS: Linux and macOS (on all platforms the async-profiler has binaries for)

Variants
--------
The JAR can be obtained in the following variants:

- `macos`, `linux-x64`, ...: `jattach`, `profiler.sh`/`asprof` and `libasyncProfiler.so` for the given platform
- `all`: all of the above

Regarding file sizes: The `all` variant are` `typically around 800KB and the individual variants around 200 to 400KB.

Commands
--------

The following is a more in-depth description of the commands of `java -jar ap-loader.jar`.

To run with JBang you can do `jbang ap-loader@jvm-profiling-tools/ap-loader` or install it as an application:

```sh
jbang app install ap-loader@jvm-profiling-tools/ap-loader
```

and run it directly with `ap-loader` instead of `java -jar ap-loader.jar`.

If you want to install a specific `ap-loader` rather than latest you can use `jbang app install me.bechberger:ap-loader-all:<version>`.

Be aware that it is recommended to run the JVM with the
`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` flags.
This improves the accuracy of the profiler.

Overview of the commands:

```sh
Usage: java -jar ap-loader.jar <command> [args]
Commands:
  help         show this help
  jattach      run the included jattach binary
  profiler     run the included profiler.sh/asprof script
  agentpath    prints the path of the extracted async-profiler agent
  jattachpath  prints the path of the extracted jattach binary
  supported    fails if this JAR does not include a profiler for the current OS and architecture
  converter    run the included converter
  version      version of the included async-profiler
  clear        clear the directory used for storing extracted files
```

### jattach

`java -jar ap-loader.jar jattach` is equivalent to calling the suitable `jattach` binary
from [GitHub](https://github.com/apangin/jattach)>:

```sh
# Load a native agent
java -jar ap-loader.jar jattach <pid> load <.so-path> { true | false, is path absolute? } [ options ]

# Load a java agent
java -jar ap-loader.jar jattach <pid> load instrument false "javaagent.jar=arguments"

# Running the help command for jcmd
java -jar ap-loader.jar jattach <pid> jcmd "help -all"
```

See the [GitHub page of jattach](https://github.com/apangin/jattach) for more details.

### profiler

`java -jar ap-loader.jar profiler` is equivalent to calling the suitable `profiler.sh`/`asprof`:

```sh
# Profile a process for `n` seconds
java -jar ap-loader.jar profiler -d <n> <pid>

# Profile a process for allocation, CPU and lock events and save the results to a JFR file
java -jar ap-loader.jar profiler -e alloc,cpu,lock -f <file.jfr> <pid>
```

See the [GitHub page of async-profiler](https://github.com/jvm-profiling-tools/async-profiler) for more details.

### supported

Allows you to check whether your JAR includes a `jattact`, `profile.sh` and `libasyncProfiler.so` for
your current OS and architecture.

### converter

`java -jar ap-loader.jar converter` is equivalent to calling the included converters:

```sh
java -jar ap-loader.jar converter <Converter> [options] <input> <output>

# Convert a JFR file to flame graph
java -jar ap-loader.jar converter jfr2flame <input.jfr> <output.html>
```

The available converters depend on the included async-profiler version.
Call `java -jar ap-loader.jar converter` to a list of available converters, see
[the source code on GitHub](https://github.com/jvm-profiling-tools/async-profiler/blob/master/src/converter/Main.java)
for more details.

### clear

Clear the application directory used for storing extracted files,
like `/Users/<user>/Library/Application Support/me.bechberger.ap-loader-<version>/`
to redo the extraction on the next run.


Running as an agent
-------------------

`java -javaagent:ap-loader.jar=<options>` is equivalent to `java -agentpath:libasyncProfiler.so=<options>`
with a suitable library.
This can be used to profile a Java process from the start.

```sh
# Profile the application and output a flame graph
java -javaagent:ap-loader.jar=start,event=cpu,file=profile.html <java arguments>
```

With JBang you can do:

```sh
jbang --javaagent:ap-loader@jvm-profiling-tools/ap-loader=start,event=cpu,file=profile.html <java arguments>
```

See the [GitHub page of async-profiler](https://github.com/jvm-profiling-tools/async-profiler) for more details.

Usage in Java Code
------------------

Then you can use the `AsyncProfilerLoader` class to load the native library:

```java
AsyncProfiler profiler = one.profiler.AsyncProfilerLoader.load();
```

`AsyncProfiler` is
the [main API class](https://github.com/jvm-profiling-tools/async-profiler/blob/master/src/api/one/profiler/AsyncProfiler.java)
from the async-profiler.jar.

The API of the `AsyncProfilerLoader` can be used to execute all commands of the CLI programmatically.

The converters reside in the `one.converter` package.

Attaching a Custom Agent Programmatically
---------------------------------
A notable part of the API are the jattach related methods that allow you to call `jattach` to attach
your own native library to the currently running JVM:

```java
// extract the agent first from the resources
Path p = one.profiler.AsyncProfilerLoader.extractCustomLibraryFromResources(....getClassLoader(), "library name");
// attach the agent to the current JVM
one.profiler.AsyncProfilerLoader.jattach(p, "optional arguments")
// -> returns true if jattach succeeded
```

### Releases

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>ap-loader-variant</artifactId>
  <version>version</version>
</dependency>
```

The latest `all` version can be added via:

```xml
<dependency>
  <groupId>me.bechberger</groupId>
  <artifactId>ap-loader-all</artifactId>
  <version>3.0-8</version>
</dependency>
```

Build and test
--------------

The following describes how to build the different JARs and how to test them.
It requires a platform supported by async-profiler and Python 3.6+.

### Build the JARs using maven

```sh
# download the release sources and binaries
python3 ./bin/releaser.py download 3.0

# build the JAR for the release
# maven might throw warnings, related to the project version setting,
# but the alternative solutions don't work, so we ignore the warning for now
mvn -Dproject.vversion=3.0 -Dproject.subrelease=7 -Dproject.platform=macos package assembly:single
# use it
java -jar target/ap-loader-macos-3.0-8-full.jar ...
# build the all JAR
mvn -Dproject.vversion=3.0 -Dproject.subrelease=7 -Dproject.platform=all package assembly:single
```

Development
-----------
This project is written in Java 8, to support all relevant platforms.
The feature set should not increase beyond what is currently:
Just build your library on top of it. But I'm of course happy for bug reports and fixes.

The code is formatted using [google-java-format](https://github.com/google/google-java-format).

### bin/releaser.py

```sh
Usage:
    python3 ./bin/releaser.py <command> ... <command> [release or current if not present]

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
```

Deploy the latest version via ` bin/releaser.py download build test deploy` as a snapshot.

For a release use `bin/releaser.py download build test deploy_release`,
but before make sure to do the following for a new sub release:

- update the version number in the README
- update the changelog in the README and `bin/releaser.py`
- and increment the `SUB_VERSION` variable in `bin/releaser.py` afterwards

And the following for a new async-profiler release:
- update the version in the README

Changelog
---------

### v8

- Support for [async-profiler 3.0](https://github.com/async-profiler/async-profiler/releases/tag/v3.0)
- Breaking changes with async-profiler 3.0:
  - async-profiler 3.0 changed the meaning of the `--lib` option from `--lib path        full path to libasyncProfiler.so in the container`
    to `-l, --lib         prepend library names`, so the `AsyncProfilerLoader` will throw an UnsupportedOperation exception
    when using the `--lib` option with a path argument and async-profiler 3.0 or higher

### v7

- Drop dev.dirs:directories dependency #13 (thanks to @jsjant for spotting the potential licensing issue and fixing it in #14)

### v6

- Fix Linux Arm64 release #12 (thanks to @dkrawiec-c for fixing this issue)

### v5

- Add new jattach methods (`AsyncProfilerLoader.jattach(Path agent, String args)`) to make using it programmatically easier
- Add new `AsyncProfilerLoader.extractCustomLibraryFromResources(ClassLoader, String)`
  method to extract a custom library from the resources
  - this also has a variant that looks in an alternative resource directory if the resource does not exist

### v4

- `AsyncProfiler.isSupported()` now returns `false` if the OS is not supported by any async-profiler binary, fixes #5

### v3

- Create specific artifacts for each platform fixing previous issues with maven version updates (issue #4, thanks @ginkel for reporting it)


### v2
- Fixed the library version in the pom #3 again
  (thanks to @gavlyukovskiy, @dpsoft and @krzysztofslusarski for spotting the bug)

### v1
- Fixed the library version in the pom #3 (thanks to @gavlyukovskiy for spotting the bug)

### v0
- 11.11.2022: Improve Converter

## License
Apache 2.0, Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
and ap-loader contributors


*This project is maintained by the [SapMachine](https://sapmachine.io) team
at [SAP SE](https://sap.com)*