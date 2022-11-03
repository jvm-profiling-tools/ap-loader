/*
 * Copyright (C) 2022 Johannes Bechberger
 *
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package one.profiler;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.dirs.ProjectDirectories;

/**
 * Allows to work with the async-profiler libraries and tools stored in the resources folder.
 *
 * <p>Upon extraction the libraries and tools are stored in an application, version and user
 * specific application folder. The resulting files are therefore cached between executions of the
 * JVM.
 *
 * <p>Running this class as an agent main class, makes it an agent that behaves the same as the
 * libasyncProfiler.so agent. Running the main method exposed the profiler.sh, jattach and converter
 * features.
 */
public final class AsyncProfilerLoader {

  private static final String LIBRARY_BASE_NAME = "libasyncProfiler";
  private static final String JATTACH_BASE_NAME = "jattach";

  private static String librarySuffix;
  private static Path extractedAsyncProfiler;
  private static Path extractedConverter;
  private static Path extractedJattach;
  private static Path extractedProfiler;
  private static Path extractionDir;
  private static String version;

  private static String getCurrentJARFileName() {
    // source https://stackoverflow.com/a/320595/19040822
    String[] pathParts =
        AsyncProfilerLoader.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .getFile()
            .split("/");
    String last = pathParts[pathParts.length - 1];
    if (last.endsWith(".jar")) {
      return last;
    }
    return null;
  }

  /** Returns directory used for storing the extracted libraries, binaries and JARs */
  public static void setExtractionDirectory(Path extractionDir) {
    AsyncProfilerLoader.extractionDir = extractionDir;
  }

  /** Deletes the directory used for extraction */
  public static void deleteExtractionDirectory() throws IOException {
    if (extractionDir != null && Files.exists(extractionDir)) {
      try (Stream<Path> stream = Files.walk(extractionDir)) {
        stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
      extractedAsyncProfiler = null;
      extractedConverter = null;
      extractedJattach = null;
      extractedProfiler = null;
    }
  }

  /** Returns the directory used for storing the extracted libraries, binaries and JARs */
  public static Path getExtractionDirectory() throws IOException {
    if (extractionDir == null) {
      extractionDir =
          Paths.get(
              ProjectDirectories.from("me", "bechberger", "ap-loader-" + getVersion()).dataDir);
      if (Files.notExists(extractionDir)) {
        Files.createDirectories(extractionDir);
      }
    }
    return extractionDir;
  }

  private static boolean isFilePresentInExtractionDirectory(String name) {
    try {
      return Files.exists(getExtractionDirectory().resolve(name));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * @throws IllegalStateException if OS or Arch not supported
   */
  private static String getLibrarySuffix() {
    String os = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();
    if (os.contains("linux")) {
      if (arch.contains("arm") || arch.contains("aarch64")) {
        return "linux-aarch64.so";
      } else if (arch.contains("64")) {
        if (isOnGLibc()) {
          return "linux-x64.so";
        } else if (isOnMusl()) {
          return "linux-x64-musl.so";
        } else {
          throw new IllegalStateException("Async-profiler does not work with the given libc");
        }
      } else {
        throw new IllegalStateException("Async-profiler does not work on Linux " + arch);
      }
    } else if (os.contains("mac")) {
      return "macos.so";
    } else {
      throw new IllegalStateException("Async-profiler does not work on " + os);
    }
  }

  /**
   * @throws IllegalStateException if OS or Arch not supported
   */
  private static String getLibrarySuffixCached() {
    if (librarySuffix == null) {
      librarySuffix = getLibrarySuffix();
    }
    return librarySuffix;
  }

  private static List<String> getAvailableLibraries() {
    return getAvailableLibs().stream()
        .map(
            u -> {
              String[] parts = u.getFile().split("/");
              return parts[parts.length - 1];
            })
        .collect(Collectors.toList());
  }

  private static List<URL> getAvailableLibs() {
    List<URL> libs = new ArrayList<>();
    try {
      Enumeration<URL> indexFiles =
          AsyncProfilerLoader.class.getClassLoader().getResources("libs/index");
      while (indexFiles.hasMoreElements()) {
        URL indexFile = indexFiles.nextElement();
        List<String> matchingResources = new ArrayList<>();
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(indexFile.openStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.startsWith(LIBRARY_BASE_NAME)
                || line.startsWith(JATTACH_BASE_NAME)
                || line.startsWith("profiler")) {
              matchingResources.add(line);
            }
          }
        }
        for (String resource : matchingResources) {
          Enumeration<URL> lib =
              AsyncProfilerLoader.class.getClassLoader().getResources("libs/" + resource);
          while (lib.hasMoreElements()) {
            libs.add(lib.nextElement());
          }
        }
      }
      return libs;
    } catch (IOException e) {
      e.printStackTrace();
      return Collections.emptyList();
    }
  }

  /**
   * Returns the version of the included async-profiler library
   *
   * @throws IllegalStateException if the version could not be determined
   */
  public static String getVersion() {
    if (version == null) {
      List<String> versions =
          getAvailableLibs().stream()
              .map(
                  u -> {
                    String[] parts = u.getFile().split("/");
                    parts = parts[parts.length - 1].split("-");
                    return parts[1];
                  })
              .distinct()
              .collect(Collectors.toList());
      if (versions.size() > 1) {
        throw new IllegalStateException("Multiple versions of async-profiler found: " + versions);
      }
      version = versions.get(0);
    }
    return version;
  }

  private static boolean isOnGLibc() {
    // see https://unix.stackexchange.com/questions/120380/what-c-library-version-does-my-system-use
    try {
      Process process = Runtime.getRuntime().exec(new String[] {"getconf", "GNU_LIBC_VERSION"});
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      return reader.readLine() != null;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isOnMusl() {
    // based on https://docs.pleroma.social/backend/installation/otp_en/#detecting-flavour
    try {
      Process process = Runtime.getRuntime().exec(new String[] {"ldd", "--version"});
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      return reader.lines().anyMatch(line -> line.contains("musl"));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Checks if an async-profiler library for the current OS, architecture and glibc is available in
   * this JAR.
   *
   * @return true if a library is available, false otherwise
   */
  public static boolean isSupported() {
    return getAvailableLibraries().stream().anyMatch(s -> s.endsWith(getLibrarySuffixCached()));
  }

  private static URL getLibraryURL() {
    return getAvailableLibs().stream()
        .filter(url -> url.getFile().endsWith(getLibrarySuffix()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No suitable async-profiler library found in" + " this JAR"));
  }

  /**
   * Extracts the converter JAR
   *
   * @return path to the extracted converter JAR
   * @throws IOException if the extraction fails
   */
  public static Path getConverterPath() throws IOException {
    if (extractedConverter == null) {
      Path converterPath = getExtractionDirectory().resolve("converter.jar");
      if (isFilePresentInExtractionDirectory("converter.jar")) {
        extractedConverter = converterPath;
        return extractedConverter;
      }
      URL converterURL =
          AsyncProfilerLoader.class.getClassLoader().getResource("libs/converter.jar");
      if (converterURL == null) {
        throw new IllegalStateException("No converter JAR found in this JAR");
      }
      try (InputStream in = converterURL.openStream()) {
        Files.copy(in, converterPath);
      }
      extractedConverter = converterPath;
    }
    return extractedConverter;
  }

  private static String getJattachSuffix() {
    return getLibrarySuffix().replace(".so", "");
  }

  private static String getProfilerSuffix() {
    return getLibrarySuffix().replace(".so", ".sh");
  }

  /**
   * Extracts the jattach binary if it is included.
   *
   * @return path to the extracted jattach
   * @throws IllegalStateException if jattach is included
   * @throws IOException if the extraction fails
   */
  public static Path getJattachPath() throws IOException {
    if (extractedJattach == null) {
      if (isFilePresentInExtractionDirectory("jattach")) {
        extractedJattach = getExtractionDirectory().resolve("jattach");
        return extractedJattach;
      }
      String suffix = getJattachSuffix();
      URL jattachURL =
          getAvailableLibs().stream()
              .filter(
                  u -> {
                    String[] parts = u.getFile().split("/");
                    String lastPart = parts[parts.length - 1];
                    return lastPart.startsWith("jattach-") && lastPart.endsWith("-" + suffix);
                  })
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No jattach binary found in this JAR"));
      Path jattachPath = getExtractionDirectory().resolve("jattach");
      try (InputStream in = jattachURL.openStream()) {
        Files.copy(in, jattachPath);
      }
      if (!jattachPath.toFile().setExecutable(true)) {
        throw new IOException("Could not make jattach (" + jattachPath + ") executable");
      }
      extractedJattach = jattachPath;
    }
    return extractedJattach;
  }

  /**
   * Extracts the profiler.sh script if it is included.
   *
   * @return path to the extracted profiler.sh
   * @throws IllegalStateException if profiler.sh is included
   * @throws IOException if the extraction fails
   */
  private static Path getProfilerPath() throws IOException {
    if (extractedProfiler == null) {
      if (isFilePresentInExtractionDirectory("profiler.sh")) {
        extractedProfiler = getExtractionDirectory().resolve("profiler.sh");
        return extractedProfiler;
      }
      String suffix = getProfilerSuffix();
      URL profilerURL =
          getAvailableLibs().stream()
              .filter(
                  u -> {
                    String[] parts = u.getFile().split("/");
                    String lastPart = parts[parts.length - 1];
                    return lastPart.startsWith("profiler-") && lastPart.endsWith("-" + suffix);
                  })
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No profiler.sh found in this JAR"));
      Path profilerPath = getExtractionDirectory().resolve("profiler.sh");
      try (InputStream in = profilerURL.openStream()) {
        Files.copy(in, profilerPath);
      }
      if (!profilerPath.toFile().setExecutable(true)) {
        throw new IOException("Could not make profiler.sh (" + profilerPath + ") executable");
      }
      extractedProfiler = profilerPath;
    }
    return extractedProfiler;
  }

  /** Output and error output of a successful process execution. */
  public static class ExecutionResult {
    private final String stdout;
    private final String stderr;

    private ExecutionResult(String stdout, String stderr) {
      this.stdout = stdout;
      this.stderr = stderr;
    }

    @Override
    public String toString() {
      return "ExecutionResult{" + "stdout='" + stdout + '\'' + ", stderr='" + stderr + '\'' + '}';
    }

    public String getStdout() {
      return stdout;
    }

    public String getStderr() {
      return stderr;
    }
  }

  private static String[] processJattachArgs(String[] args) throws IOException {
    List<String> argList = new ArrayList<>(Arrays.asList(args));
    if (argList.size() >= 4
        && argList.get(1).equals("load")
        && argList.get(2).endsWith("libasyncProfiler.so")) {
      argList.set(2, getAsyncProfilerPath().toString());
      argList.set(3, "true");
    }
    argList.add(0, getJattachPath().toString());
    return argList.toArray(new String[0]);
  }

  /**
   * See <a href="https://github.com/apangin/jattach">jattach</a> for more information.
   *
   * <p>It runs the same as jattach with the only exception that every string that ends with
   * "libasyncProfiler.so" is mapped to the extracted async-profiler library for the load command.
   * One can therefore start/stop the async-profiler via <verb>executeJattach(PID, "load",
   * "libasyncProfiler.so", true, "start"/"stop")</verb>.
   *
   * @throws IOException if something went wrong (e.g. the jattach binary is not found or the
   *     execution fails)
   */
  public static ExecutionResult executeJattach(String... args) throws IOException {
    return executeCommand("jattach", processJattachArgs(args), new String[0]);
  }

  private static void executeJattachInteractively(String[] args) throws IOException {
    executeCommandInteractively("jattach", processJattachArgs(args), new String[0]);
  }

  private static String[] processConverterArgs(String[] args) throws IOException {
    List<String> argList = new ArrayList<>();
    argList.add(System.getProperty("java.home") + "/bin/java");
    argList.add("-jar");
    argList.add(getConverterPath().toString());
    argList.addAll(Arrays.asList(args));
    return argList.toArray(new String[0]);
  }

  /**
   * See <a
   * href="https://github.com/jvm-profiling-tools/async-profiler/blob/master/src/converter/Main.java">converter</a>
   * for more information.
   *
   * <p>Just pass it the arguments that you would normally pass to the JVM after <code>
   * java -cp converter.jar</code>
   *
   * @throws IOException if something went wrong (e.g. the execution fails)
   */
  public static ExecutionResult executeConverter(String... args) throws IOException {
    return executeCommand("converter", processConverterArgs(args), new String[0]);
  }

  private static void executeConverterInteractively(String[] args) throws IOException {
    executeCommandInteractively("converter", processConverterArgs(args), new String[0]);
  }

  private static String[] getProfilerShEnv() throws IOException {
    return new String[] {"JATTACH", getJattachPath().toString()};
  }

  private static String[] processProfilerArgs(String[] args) throws IOException {
    List<String> argList = new ArrayList<>();
    argList.add(getProfilerPath().toString());
    argList.addAll(Arrays.asList(args));
    return argList.toArray(new String[0]);
  }

  /**
   * See <a
   * href="https://github.com/jvm-profiling-tools/async-profiler#profiler-options">profiler.sh</a>
   * for more information.
   *
   * <p>
   *
   * @throws IOException if something went wrong (e.g. the profiler.sh is not found or the execution
   *     fails)
   */
  public static ExecutionResult executeProfiler(String... args) throws IOException {
    return executeCommand("profiler", processProfilerArgs(args), getProfilerShEnv());
  }

  private static String getApplicationCall() {
    String fileName = getCurrentJARFileName();
    if (fileName == null) {
      return "java -jar ap-loader.jar";
    }
    return "java -jar " + fileName;
  }

  private static String processProfilerOutput(String string) throws IOException {
    return string.replace(getProfilerPath().toString(), getApplicationCall() + " profiler");
  }

  private static void executeProfilerInteractively(String[] args) throws IOException {
    String[] env = getProfilerShEnv();
    String[] command = processProfilerArgs(args);
    Process proc = Runtime.getRuntime().exec(command, env);
    try (BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
      String stdoutStr = stdout.lines().collect(Collectors.joining("\n"));
      String stderrStr = stderr.lines().collect(Collectors.joining("\n"));
      int exitCode = proc.waitFor();
      System.out.println(processProfilerOutput(stdoutStr));
      System.err.println(processProfilerOutput(stderrStr));
      if (exitCode != 0) {
        System.exit(exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.exit(1);
    }
  }

  private static ExecutionResult executeCommand(
      String name, String[] args, String[] environmentVariables) throws IOException {
    Process proc = Runtime.getRuntime().exec(args, environmentVariables);
    try (BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        BufferedReader stderr = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
      String stdoutStr = stdout.lines().collect(Collectors.joining("\n"));
      String stderrStr = stderr.lines().collect(Collectors.joining("\n"));
      int exitCode = proc.waitFor();
      if (exitCode != 0) {
        throw new IOException(
            name
                + " failed with exit code "
                + exitCode
                + ", stderr: "
                + stderrStr
                + ", "
                + "stdout: "
                + stdoutStr);
      }
      return new ExecutionResult(stdoutStr, stderrStr);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(name + " failed because it got interrupted");
    }
  }

  private static void executeCommandInteractively(
      String name, String[] args, String[] environmentVariables) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(args);
    pb.inheritIO();
    Map<String, String> env = pb.environment();
    for (int i = 0; i < environmentVariables.length; i += 2) {
      env.put(environmentVariables[i], environmentVariables[i + 1]);
    }
    Process proc = pb.start();
    try {
      System.exit(proc.waitFor());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(name + " failed because it got interrupted");
    }
  }

  /**
   * Loads the included async-profiler for the current OS, architecture and glibc.
   *
   * @return the loaded async-profiler or null if anything went wrong (e.g. unsupported OS)
   */
  public static AsyncProfiler loadOrNull() {
    try {
      return load();
    } catch (IOException | IllegalStateException | UnsatisfiedLinkError e) {
      return null;
    }
  }

  /**
   * Loads the included async-profiler for the current OS, architecture and glibc
   *
   * @return the loaded async-profiler
   * @throws UnsatisfiedLinkError if the library could not be loaded
   * @throws IOException if the library could not be loaded
   * @throws IllegalStateException if OS or arch are not supported or the library is not included at
   *     all
   */
  public static AsyncProfiler load() throws IOException {
    return AsyncProfiler.getInstance(getAsyncProfilerPath().toString());
  }

  /**
   * Extracts the async-profiler and returns the path to the extracted file.
   *
   * @return the path to the extracted library
   * @throws IOException if the library could not be loaded
   * @throws IllegalStateException if OS or Arch are not supported or the library is not included at
   *     all
   */
  public static Path getAsyncProfilerPath() throws IOException {
    if (!isSupported()) {
      throw new IllegalStateException(
          "Async-profiler is not supported on this OS and architecture, using this " + "JAR");
    }
    if (extractedAsyncProfiler == null) {
      extractedAsyncProfiler = getExtractionDirectory().resolve(LIBRARY_BASE_NAME);
      if (!isFilePresentInExtractionDirectory(LIBRARY_BASE_NAME)) {
        try (InputStream in = getLibraryURL().openStream()) {
          Files.copy(in, extractedAsyncProfiler);
        }
      }
    }
    return extractedAsyncProfiler;
  }

  public static void premain(String agentArgs, Instrumentation instrumentation) {
    agentmain(agentArgs, instrumentation);
  }

  public static void agentmain(String agentArgs, Instrumentation instrumentation) {
    try {
      load().execute(agentArgs);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: " + getApplicationCall() + " <command> [args]");
    out.println("Commands:");
    out.println("  help        show this help");
    if (isSupported()) {
      out.println("  jattach     run the included jattach binary");
      out.println("  profiler    run the included profiler.sh");
    }
    out.println("  supported   fails if this JAR does not include a profiler");
    out.println("              for the current OS and architecture");
    out.println("  converter   run the included converter JAR");
    out.println("  version     version of the included async-profiler");
    out.println("  clear       clear the directory used for storing extracted files");
  }

  private static void checkCommandAvailability(String command) {
    switch (command) {
      case "jattach":
      case "profiler":
        if (!isSupported()) {
          System.err.println(
              "The "
                  + command
                  + " command is not supported on this OS and architecture, using this "
                  + "JAR");
          System.exit(1);
        }
        break;
      default:
        break;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 0 || args[0].equals("help")) {
      printUsage(System.out);
      return;
    }
    String command = args[0];
    String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
    checkCommandAvailability(command);
    switch (command) {
      case "jattach":
        executeJattachInteractively(commandArgs);
        break;
      case "profiler":
        executeProfilerInteractively(commandArgs);
        break;
      case "supported":
        if (!isSupported()) {
          System.exit(1);
        }
        break;
      case "converter":
        executeConverterInteractively(commandArgs);
        break;
      case "version":
        System.out.println(getVersion());
        break;
      case "clear":
        deleteExtractionDirectory();
        break;
      default:
        System.err.println("Unknown command: " + command);
        System.err.println();
        printUsage(System.err);
    }
  }
}
