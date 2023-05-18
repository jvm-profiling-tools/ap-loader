/*
 * Copyright 2023 SAP SE or an SAP affiliate company, Johannes Bechberger
 * and ap-loader contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.profiler;

import dev.dirs.ProjectDirectories;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Allows to work with the async-profiler libraries and tools stored in the resources folder.
 *
 * <p>Upon extraction the libraries and tools are stored in an application, version and user
 * specific application folder. The resulting files are therefore cached between executions of the
 * JVM. This folder can be manually specified by either setting the property
 * ap_loader_extraction_dir or by using the {@link
 * AsyncProfilerLoader#setExtractionDirectory(Path)}} method.
 *
 * <p>Running this class as an agent main class, makes it an agent that behaves the same as the
 * libasyncProfiler.so agent. Running the main method exposed the profiler.sh, jattach and converter
 * features.
 */
public final class AsyncProfilerLoader {

  private static class OSNotSupportedException extends Exception {
    OSNotSupportedException(String message) {
      super(message);
    }
  }

  private static final String EXTRACTION_PROPERTY_NAME = "ap_loader_extraction_dir";
  private static String librarySuffix;
  private static Path extractedAsyncProfiler;
  private static Path extractedJattach;
  private static Path extractedProfiler;
  private static Path extractionDir;
  private static List<String> availableVersions;
  private static String version;

  static {
    String dir = System.getProperty(EXTRACTION_PROPERTY_NAME, "");
    if (!dir.isEmpty()) {
      Path path = Paths.get(dir);
      if (Files.exists(path)) {
        if (!Files.isDirectory(path)) {
          throw new IllegalArgumentException("The extraction directory is not a directory: " + dir);
        }
        if (!Files.isWritable(path)) {
          throw new IllegalArgumentException("The extraction directory is not writable: " + dir);
        }
      }
      extractionDir = path;
    }
  }

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

  /**
   * Set the used version of async-profiler. This is required if there are multiple versions of this
   * library in the dependencies.
   *
   * @param version set version of async-profiler to use
   */
  public static void setVersion(String version) throws IOException {
    deleteExtractionDirectory();
    AsyncProfilerLoader.version = version;
  }

  /** Deletes the directory used for extraction */
  public static void deleteExtractionDirectory() throws IOException {
    if (extractionDir != null) {
      try (Stream<Path> stream = Files.walk(getExtractionDirectory())) {
        stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
    extractedAsyncProfiler = null;
    extractedJattach = null;
    extractedProfiler = null;
    extractionDir = null;
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

  /**
   * @throws IllegalStateException if OS or Arch not supported
   */
  private static String getLibrarySuffix() throws OSNotSupportedException {
    if (librarySuffix == null) {
      String version = getVersion();
      boolean oldVersion = version.startsWith("1.");
      String os = System.getProperty("os.name").toLowerCase();
      String arch = System.getProperty("os.arch").toLowerCase();
      if (os.startsWith("linux")) {
        if (arch.equals("arm64") || arch.equals("aarch64")) {
          librarySuffix = version + "-linux-arm64.so";
        } else if (arch.equals("x86") && oldVersion) {
          librarySuffix = version + "-linux-x86.so";
        } else if (arch.equals("x86_64") || arch.equals("x64") || arch.equals("amd64")) {
          if (isOnMusl()) {
            librarySuffix = version + "-linux-x64-musl.so";
          } else {
            librarySuffix = version + "-linux-x64.so";
          }
        } else {
          throw new OSNotSupportedException("Async-profiler does not work on Linux " + arch);
        }
      } else if (os.startsWith("macosx") || os.startsWith("mac os x")) {
        if (oldVersion) {
          if (!arch.contains("x86")) {
            throw new OSNotSupportedException("Async-profiler does not work on MacOS " + arch);
          }
          librarySuffix = version + "-macosx-x86.so";
        } else {
          librarySuffix = version + "-macos.so";
        }
      } else {
        throw new OSNotSupportedException("Async-profiler does not work on " + os);
      }
    }
    return librarySuffix;
  }

  /** Get available versions of the library */
  public static List<String> getAvailableVersions() {
    if (availableVersions == null) {
      availableVersions = new ArrayList<>();

      try {
        Enumeration<URL> indexFiles =
            AsyncProfilerLoader.class.getClassLoader().getResources("libs/ap-version");
        while (indexFiles.hasMoreElements()) {
          URL indexFile = indexFiles.nextElement();
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(indexFile.openStream()))) {
            String line = reader.readLine();
            if (line != null) {
              availableVersions.add(line);
            }
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
        return Collections.emptyList();
      }
    }
    return availableVersions;
  }

  /**
   * Returns the version of the included async-profiler library (or the version set by {@link
   * #setVersion(String)}
   *
   * @throws IllegalStateException if the version could not be determined
   */
  public static String getVersion() {
    if (version == null) {
      if (getAvailableVersions().size() > 1) {
        throw new IllegalStateException(
            "Multiple versions of async-profiler found: " + getAvailableVersions());
      }
      version = getAvailableVersions().get(0);
    }
    return version;
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

  private static String getAsyncProfilerFileName() throws OSNotSupportedException {
    return "libasyncProfiler-" + getLibrarySuffix();
  }

  private static String getJattachFileName() throws OSNotSupportedException {
    return "jattach-" + getLibrarySuffix().replace(".so", "");
  }

  private static String getProfilerFileName() {
    return "profiler-" + getVersion() + ".sh";
  }

  private static boolean hasFileInResources(String fileName) {
    try {
      Enumeration<URL> indexFiles =
          AsyncProfilerLoader.class.getClassLoader().getResources("libs/" + fileName);
      return indexFiles.hasMoreElements();
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }

  private static URL getUrl(String fileName) {
    try {
      Enumeration<URL> indexFiles =
          AsyncProfilerLoader.class.getClassLoader().getResources("libs/" + fileName);
      if (indexFiles.hasMoreElements()) {
        return indexFiles.nextElement();
      }
      throw new IllegalStateException("Could not find file " + fileName);
    } catch (IOException e) {
      throw new IllegalStateException("Could not find file " + fileName, e);
    }
  }

  /**
   * Checks if an async-profiler library for the current OS, architecture and glibc is available in
   * this JAR.
   *
   * @return true if a library is available, false otherwise
   */
  public static boolean isSupported() {
    try {
      return hasFileInResources(getAsyncProfilerFileName());
    } catch (OSNotSupportedException e) {
      return false;
    }
  }

  /** Copy from resources if needed */
  private static Path copyFromResources(String fileName, Path destination) throws IOException {
    if (!isSupported()) {
      throw new IllegalStateException(
          "Async-profiler is not supported on this OS and architecture");
    }
    if (Files.exists(destination)) {
      // already copied sometime before, but certainly the same file as it belongs to the same
      // async-profiler version
      return destination;
    }
    try {
      URL url = getUrl(fileName);
      try (InputStream in = url.openStream()) {
        Files.copy(in, destination);
      }
      return destination;
    } catch (IOException e) {
      throw new IOException("Could not copy file " + fileName + " to " + destination, e);
    }
  }

  /**
   * Extracts a custom agent from the resources
   *
   * <p>
   *
   * @param classLoader the class loader to load the resources from
   * @param fileName the name of the file to copy, maps the library name if the fileName does not start with "lib",
   *                 e.g. "jni" will be treated as "libjni.so" on Linux and as "libjni.dylib" on macOS
   * @return the path of the library
   * @throws IOException if the extraction fails
   */
  public static Path extractCustomLibraryFromResources(ClassLoader classLoader, String fileName) throws IOException {
    return extractCustomLibraryFromResources(classLoader, fileName, null);
  }

  /**
   * Extracts a custom native library from the resources and returns the alternative source
   * if the file is not in the resources.
   *
   * <p>If the file is extracted, then it is copied to a new temporary folder which is deleted upon JVM exit.</p>
   *
   * <p>This method is mainly seen as a helper method to obtain custom native agents for {@link #jattach(Path)} and
   * {@link #jattach(Path, String)}. It is included in ap-loader to make it easier to write applications that need
   * custom native libraries.</p>
   *
   * <p>This method works on all architectures.</p>
   *
   * @param classLoader the class loader to load the resources from
   * @param fileName the name of the file to copy, maps the library name if the fileName does not start with "lib",
   *                 e.g. "jni" will be treated as "libjni.so" on Linux and as "libjni.dylib" on macOS
   * @param alternativeSource the optional resource directory to use if the resource is not found in the resources,
   *                          this is typically the case when running the application from an IDE, an example would be
   *                          "src/main/resources" or "target/classes" for maven projects
   * @return the path of the library
   * @throws IOException if the extraction fails and the alternative source is not present for the current architecture
   */
  public static Path extractCustomLibraryFromResources(ClassLoader classLoader, String fileName, Path alternativeSource) throws IOException {
    Path filePath = Paths.get(fileName);
    String name = filePath.getFileName().toString();
    if (!name.startsWith("lib")) {
      name = System.mapLibraryName(name);
    }
    Path realFilePath = filePath.getParent() == null ? Paths.get(name) : filePath.getParent().resolve(name);
    Enumeration<URL> indexFiles = classLoader.getResources(realFilePath.toString());
    if (!indexFiles.hasMoreElements()) {
       if (alternativeSource == null) {
         throw new IOException("Could not find library " + fileName + " in resources");
       }
       if (!alternativeSource.toFile().isDirectory()) {
         throw new IOException("Could not find library " + fileName + " in resources and alternative source " + alternativeSource + " is not a directory");
       }
       if (alternativeSource.resolve(realFilePath).toFile().exists()) {
         return alternativeSource.resolve(realFilePath);
       }
       throw new IOException("Could not find library " + fileName + " in resources and alternative source " + alternativeSource + " does not contain " + realFilePath);
    }
    URL url = indexFiles.nextElement();
    Path tempDir = Files.createTempDirectory("ap-loader");
    try {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try (Stream<Path> stream = Files.walk(getExtractionDirectory())) {
          stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      }));
    } catch (RuntimeException e) {
      throw (IOException) e.getCause();
    }
    Path destination = tempDir.resolve(name);
    try {
      try (InputStream in = url.openStream()) {
        Files.copy(in, destination);
      }
      return destination;
    } catch (IOException e) {
      throw new IOException("Could not copy file " + fileName + " to " + destination, e);
    }
  }

  /**
   * Extracts the jattach tool
   *
   * @return path to the extracted jattach tool
   * @throws IllegalStateException if OS or arch are not supported
   * @throws IOException if the extraction fails
   */
  public static Path getJattachPath() throws IOException {
    if (extractedJattach == null) {
      Path path = null;
      try {
        path = copyFromResources(getJattachFileName(), getExtractionDirectory().resolve("jattach"));
      } catch (OSNotSupportedException e) {
        throw new IllegalStateException(e.getMessage());
      }
      if (!path.toFile().setExecutable(true)) {
        throw new IOException("Could not make jattach (" + path + ") executable");
      }
      extractedJattach = path;
    }
    return extractedJattach;
  }

  /**
   * Extracts the profiler.sh script
   *
   * @return path to the extracted profiler.sh
   * @throws IllegalStateException if OS or arch are not supported
   * @throws IOException if the extraction fails
   */
  private static Path getProfilerPath() throws IOException {
    if (extractedProfiler == null) {
      Path path =
          copyFromResources(getProfilerFileName(), getExtractionDirectory().resolve("profiler.sh"));
      if (!path.toFile().setExecutable(true)) {
        throw new IOException("Could not make profiler.sh (" + path + ") executable");
      }
      extractedProfiler = path;
    }
    return extractedProfiler;
  }

  /**
   * Extracts the async-profiler and returns the path to the extracted file.
   *
   * @return the path to the extracted library
   * @throws IOException if the library could not be loaded
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static Path getAsyncProfilerPath() throws IOException {
    if (extractedAsyncProfiler == null) {
      try {
        extractedAsyncProfiler =
            copyFromResources(
                getAsyncProfilerFileName(), getExtractionDirectory().resolve("libasyncProfiler.so"));
      } catch (OSNotSupportedException e) {
        throw new IllegalStateException(e.getMessage());
      }
    }
    return extractedAsyncProfiler;
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
   * One can therefore start/stop the async-profiler via <code>
   * executeJattach(PID, "load", "libasyncProfiler.so", true, "start"/"stop")</code>.
   *
   * Use the {@link #jattach(Path)} or {@link #jattach(Path, String)} to load agents via jattach directly,
   * without the need to construct the command line arguments yourself.
   *
   * @throws IOException if something went wrong (e.g. the jattach binary is not found or the
   *     execution fails)
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static ExecutionResult executeJattach(String... args) throws IOException {
    return executeCommand("jattach", processJattachArgs(args));
  }

  private static void executeJattachInteractively(String[] args) throws IOException {
    executeCommandInteractively("jattach", processJattachArgs(args));
  }

  /**
   * See <a href="https://github.com/apangin/jattach">jattach</a> for more information.
   *
   * <p>It loads the passed agent via jattach to the current JVM, mapping
   * "libasyncProfiler.so" to the extracted async-profiler library for the load command.</p>
   *
   * @return true if the agent was successfully attached, false otherwise
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static boolean jattach(Path agentPath) {
    return jattach(agentPath, null);
  }

  /**
   * See <a href="https://github.com/apangin/jattach">jattach</a> for more information.
   *
   * <p>It loads the passed agent via jattach to the current JVM, mapping
   * "libasyncProfiler.so" to the extracted async-profiler library for the load command.</p>
   *
   * @return true if the agent was successfully attached, false otherwise
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static boolean jattach(Path agentPath, String arguments) {
    List<String> args = new ArrayList<>();
    args.add(String.valueOf(getProcessId()));
    args.add("load");
    args.add(agentPath.toString());
    args.add("true");
    if (arguments != null) {
      args.add(arguments);
    }
    try {
      executeJattach(args.toArray(new String[0]));
      return true;
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }

  private static String[] processConverterArgs(String[] args) throws IOException {
    List<String> argList = new ArrayList<>();
    argList.add(System.getProperty("java.home") + "/bin/java");
    argList.add("-cp");
    argList.add(System.getProperty("java.class.path"));
    List<String> profilerArgs = new ArrayList<>(Arrays.asList(args));
    if (profilerArgs.size() > 0 && profilerArgs.get(0).startsWith("jfr")) {
      profilerArgs.set(0, "one.converter." + profilerArgs.get(0));
    } else {
      profilerArgs.add("one.converter.Main");
    }
    argList.addAll(profilerArgs);
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
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static ExecutionResult executeConverter(String... args) throws IOException {
    return executeCommand("converter", processConverterArgs(args));
  }

  private static void executeConverterInteractively(String[] args) throws IOException {
    executeCommandInteractively("converter", processConverterArgs(args));
  }

  private static String[] getEnv() throws IOException {
    return new String[] {
      "JATTACH=" + getJattachPath().toString(), "PROFILER=" + getAsyncProfilerPath()
    };
  }

  private static String[] processProfilerArgs(String[] args) throws IOException {
    List<String> argList = new ArrayList<>();
    argList.add("sh");
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
   * @throws IOException if something went wrong (e.g. the execution fails)
   * @throws IllegalStateException if OS or Arch are not supported
   */
  public static ExecutionResult executeProfiler(String... args) throws IOException {
    return executeCommand("profiler", processProfilerArgs(args));
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
    String[] command = processProfilerArgs(args);
    Process proc = Runtime.getRuntime().exec(command, getEnv());
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

  private static ExecutionResult executeCommand(String name, String[] args) throws IOException {
    Process process = Runtime.getRuntime().exec(args, getEnv());
    try (BufferedReader stdout =
            new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader stderr =
            new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
      String stdoutStr = stdout.lines().collect(Collectors.joining("\n"));
      String stderrStr = stderr.lines().collect(Collectors.joining("\n"));
      int exitCode = process.waitFor();
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

  private static void executeCommandInteractively(String name, String[] args) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(args);
    pb.inheritIO();
    Map<String, String> env = pb.environment();
    String[] envArray = getEnv();
    for (String s : envArray) {
      String[] parts = s.split("=", 2);
      env.put(parts[0], parts[1]);
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
    } catch (IOException | IllegalStateException e) {
      return null;
    }
  }

  /**
   * Loads the included async-profiler for the current OS, architecture and glibc
   *
   * @return the loaded async-profiler
   * @throws IOException if the library could not be loaded
   * @throws IllegalStateException if OS or arch are not supported
   */
  public static AsyncProfiler load() throws IOException {
    synchronized (AsyncProfiler.class) {
      try {
        return AsyncProfiler.getInstance(getAsyncProfilerPath().toString());
      } catch (UnsatisfiedLinkError e) {
        throw new IllegalStateException(
            "Could not load async-profiler from the extraction directory "
                + getExtractionDirectory()
                + ". "
                + "Please make sure that the extraction directory allows execution. "
                + "You can specify an alternative using the system property "
                + EXTRACTION_PROPERTY_NAME
                + ".",
            e);
      }
    }
  }

  public static void premain(String agentArgs, Instrumentation instrumentation) {
    agentmain(agentArgs, instrumentation);
  }

  /**
   * Returns the id of the current process
   *
   * @throws IllegalStateException if the id can not be obtained, this should never happen
   */
  public static int getProcessId() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int index = name.indexOf('@');
    if (index < 1) {
      throw new IllegalStateException("Could not get process id from " + name);
    }
    try {
      return Integer.parseInt(name.substring(0, index));
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Could not get process id from " + name);
    }
  }

  /**
   * Attach the extracted async-profiler agent to the current JVM.
   *
   * @param arguments arguments string passed to the agent, might be null
   * @throws IllegalStateException if the agent could not be attached
   */
  public static void attach(String arguments) {
    try {
      List<String> args = new ArrayList<>();
      args.add(getProcessId() + "");
      args.add("load");
      args.add(getAsyncProfilerPath().toString());
      args.add("true");
      if (arguments != null) {
        args.add(arguments);
      }
      executeJattach(args.toArray(new String[0]));
    } catch (Exception e) {
      throw new IllegalStateException("Could not attach to the currentd process", e);
    }
  }

  public static void agentmain(String agentArgs, Instrumentation instrumentation) {
    attach(agentArgs);
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: " + getApplicationCall() + " <command> [args]");
    out.println("Commands:");
    out.println("  help         show this help");
    if (isSupported()) {
      out.println("  jattach      run the included jattach binary");
      out.println("  profiler     run the included profiler.sh");
      out.println("  agentpath    prints the path of the extracted async-profiler agent");
      out.println("  jattachpath  prints the path of the extracted jattach binary");
    }
    out.println("  supported    fails if this JAR does not include a profiler");
    out.println("               for the current OS and architecture");
    out.println("  converter    run the included converter");
    out.println("  version      version of the included async-profiler");
    out.println("  clear        clear the directory used for storing extracted files");
  }

  private static void checkCommandAvailability(String command) {
    switch (command) {
      case "jattach":
      case "profiler":
      case "agentpath":
      case "jattachpath":
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
      case "agentpath":
        System.out.println(getAsyncProfilerPath());
        break;
      case "jattachpath":
        System.out.println(getJattachPath());
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
