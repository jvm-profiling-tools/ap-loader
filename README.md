Loader for AsyncProfiler
========================

Packages [async-profiler](https://github.com/jvm-profiling-tools/async-profiler) releases in a JAR
with a `AsyncProfilerLoader` that loads the suitable native library for the current platform.

This is usable as a java agent (same arguments as the async-profiler agent) and as the basis for other libraries.
The real rationale behind this library is that the async-profiler is a nice tool, but it cannot be easily integrated
into other Java based tools.

Take the `all` build and you have a JAR that provides the important features of async-profiler on all supported platforms:

A changelog can be found at the async-profiler repository, as this library should rarely change itself.

Supported Platforms
-------------------

- Required Java version: 8 or higher
- Supported OS: Linux and macOS (on all platforms the async-profiler has binaries for)

Variants
--------
The JAR can be obtained in the following variants:

- `macos`, `linux-x64`, ...: `jattach`, `profiler.sh` and `libasyncProfiler.so` for the given platform
- `all`: all of the above

Commands
--------

The following is a more in-depth description of the commands of `java -jar ap-loader.jar`.

Be aware that it is recommended to use run the JVM with the 
`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` flags when attaching to a running JVM.
This improves the accuracy of the profiler.

Overview over the commands:

```sh
Usage: java -jar ap-loader.jar <command> [args]
Commands:
  help        show this help
  jattach     run the included jattach binary
  profiler    run the included profiler.sh
  supported   fails if this JAR does not include a profiler for the current OS and architecture
  converter   run the included converter JAR
  version     version of the included async-profiler
  clear       clear the directory used for storing extracted files
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
`java -jar ap-loader.jar profiler` is equivalent to calling the suitable `profiler.sh`:

```sh
# Profile a process for a `n` seconds
java -jar ap-loader.jar profiler -d <n> <pid>

# Profile a process for allocation, CPU and lock events and save the results to a JFR file
java -jar ap-loader.jar profiler -e alloc,cpu,lock -f <file.jfr> <pid>
```

See the [GitHub page of async-profiler](https://github.com/jvm-profiling-tools/async-profiler) for more details.

### supported

Allows you to check whether your JAR includes a `jattact`, `profile.sh` and `libasyncProfiler.so` for
your current OS and architecture.

### converter

`java -jar ap-loader.jar converter` is equivalent to calling the included `jconverter.jar`:

```sh
java -jar ap-loader.jar converter <Converter> [options] <input> <output>

# Convert a JFR file to flame graph
java -jar ap-loader.jar converter jfr2flame <input.jfr> <output.html>
```

The available converters depend on the included async-profiler version.
Call `java -jar converter` to a list of available converters, see
[the source code on GitHub](https://github.com/jvm-profiling-tools/async-profiler/blob/master/src/converter/Main.java) 
for more details.

### clear

Clear the application directory used for storing extracted files,
like `/Users/<user>/Library/Application Support/me.bechberger.ap-loader-<version>/`
to redo the extraction on the next run.


Running as an agent
-------------------

`java -javaagent:ap-loader.jar=<options>` is equivalent to `java -agentpath:libasyncProfiler.so=<options>`
with the suitable library.
This can be used to profile a Java process from the start.

```sh
# Profile the application and output a flamegraph
java -javaagent:/path/to/libasyncProfiler.so=start,event=cpu,file=profile.html <java arguments>
```

See the [GitHub page of async-profiler](https://github.com/jvm-profiling-tools/async-profiler) for more details.

Using in Java code
------------------

Then you can use the `AsyncProfilerLoader` class to load the native library:

```java
AsyncProfiler profiler + one.profiler.AsyncProfilerLoader.load();
```

`AsyncProfiler` is the [main API class](https://github.com/jvm-profiling-tools/async-profiler/blob/master/src/api/one/profiler/AsyncProfiler.java)
from the async-profiler.jar.

The API of the `AsyncProfilerLoader` can be used to execute all commands the CLI programmatically.

### Snapsshots
We currently only release to snapshop, as the API is not stable yet.

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>ap-loader</artifactId>
    <version>version-variant-SNAPSHOT</version>
</dependency>
```

For example for the all variant of version 2.8.3:
```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>ap-loader</artifactId>
    <version>2.8.3-all-SNAPSHOT</version>
</dependency>
```

You also have to add the snapshot repository:
```xml
<repositories>
    <repository>
        <id>snapshots</id>
        <url>https://s01.oss.sonatype.org/content/repositories/snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```


Build and test
--------------

The following describes how to build the different JARs and how to test them.
It requires a platform supported by async-profiler and Python 3.6+.

### Build the JARs using maven

```sh
# download and unzip the platform releases that you want to include
wget https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.8.3/async-profiler-2.8.3-macos.zip
unzip -o async-profiler-2.8.3-macos.zip -d ap-releases
# build the JAR for the release
# maven might throw warnings, related to the project version setting,
# but the alternative solutions don't work, so we ignore the warning for now
mvn -Dproject.versionPlatform=2.8.3-macos assembly:single
# use it
java -jar target/ap-loader-2.8.3-macos-full.jar ...
# build the all JAR
mvn -Dproject.versionPlatform=2.8.3-all -f pom_all.xml assembly:single
```

Development
-----------
This project is written in Java 8, to support all relevant platforms.
The feature set should not increase beyond what is currently: 
Just build your library on top of it. But I'm of course happy for bug reports and fixes.

The code is formatted using [google-java-format](https://github.com/google/google-java-format).

License
-------
Apache 2.0