package net.minecraft.bundler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class Main {
   public static void main(String[] argv) {
      new Main().run(argv);
   }

   private void run(String[] argv) {
      try {
         String defaultMainClassName = this.readResource("main-class", BufferedReader::readLine);
         String mainClassName = System.getProperty("bundlerMainClass", defaultMainClassName);
         String repoDir = System.getProperty("bundlerRepoDir", "");
         Path outputDir = Paths.get(repoDir);
         Files.createDirectories(outputDir);
         List<URL> extractedUrls = new ArrayList<>();
         this.readAndExtractDir("versions", outputDir, extractedUrls);
         this.readAndExtractDir("libraries", outputDir, extractedUrls);
         if (mainClassName == null || mainClassName.isEmpty()) {
            System.out.println("Empty main class specified, exiting");
            System.exit(0);
         }

         ClassLoader maybePlatformClassLoader = this.getClass().getClassLoader().getParent();
         URLClassLoader classLoader = new URLClassLoader(extractedUrls.toArray(new URL[0]), maybePlatformClassLoader);
         System.out.println("Starting " + mainClassName);
         Thread runThread = new Thread(() -> {
            try {
               Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
               MethodHandle mainHandle = MethodHandles.lookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class)).asFixedArity();
               mainHandle.invoke((Object)argv);
            } catch (Throwable var5x) {
               Main.Thrower.INSTANCE.sneakyThrow(var5x);
            }
         }, "ServerMain");
         runThread.setContextClassLoader(classLoader);
         runThread.start();
      } catch (Exception var10) {
         var10.printStackTrace(System.out);
         System.out.println("Failed to extract server libraries, exiting");
      }
   }

   private <T> T readResource(String resource, Main.ResourceParser<T> parser) throws Exception {
      String fullPath = "/META-INF/" + resource;

      Object var5;
      try (InputStream is = this.getClass().getResourceAsStream(fullPath)) {
         if (is == null) {
            throw new IllegalStateException("Resource " + fullPath + " not found");
         }

         var5 = parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
      }

      return (T)var5;
   }

   private void readAndExtractDir(String subdir, Path outputDir, List<URL> extractedUrls) throws Exception {
      List<Main.FileEntry> entries = this.readResource(subdir + ".list", reader -> reader.lines().map(Main.FileEntry::parseLine).toList());
      Path subdirPath = outputDir.resolve(subdir);

      for (Main.FileEntry entry : entries) {
         Path outputFile = subdirPath.resolve(entry.path);
         this.checkAndExtractJar(subdir, entry, outputFile);
         extractedUrls.add(outputFile.toUri().toURL());
      }
   }

   private void checkAndExtractJar(String subdir, Main.FileEntry entry, Path outputFile) throws Exception {
      if (!Files.exists(outputFile) || !checkIntegrity(outputFile, entry.hash())) {
         System.out.printf("Unpacking %s (%s:%s) to %s%n", entry.path, subdir, entry.id, outputFile);
         this.extractJar(subdir, entry.path, outputFile);
      }
   }

   private void extractJar(String subdir, String jarPath, Path outputFile) throws IOException {
      Files.createDirectories(outputFile.getParent());

      try (InputStream input = this.getClass().getResourceAsStream("/META-INF/" + subdir + "/" + jarPath)) {
         if (input == null) {
            throw new IllegalStateException("Declared library " + jarPath + " not found");
         }

         Files.copy(input, outputFile, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static boolean checkIntegrity(Path file, String expectedHash) throws Exception {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      try (InputStream output = Files.newInputStream(file)) {
         output.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
         String actualHash = byteToHex(digest.digest());
         if (actualHash.equalsIgnoreCase(expectedHash)) {
            return true;
         }

         System.out.printf("Expected file %s to have hash %s, but got %s%n", file, expectedHash, actualHash);
      }

      return false;
   }

   private static String byteToHex(byte[] bytes) {
      StringBuilder result = new StringBuilder(bytes.length * 2);

      for (byte b : bytes) {
         result.append(Character.forDigit(b >> 4 & 15, 16));
         result.append(Character.forDigit(b >> 0 & 15, 16));
      }

      return result.toString();
   }

   private record FileEntry(String hash, String id, String path) {
      public static Main.FileEntry parseLine(String line) {
         String[] fields = line.split("\t");
         if (fields.length != 3) {
            throw new IllegalStateException("Malformed library entry: " + line);
         } else {
            return new Main.FileEntry(fields[0], fields[1], fields[2]);
         }
      }
   }

   @FunctionalInterface
   private interface ResourceParser<T> {
      T parse(BufferedReader var1) throws Exception;
   }

   private static class Thrower<T extends Throwable> {
      private static final Main.Thrower<RuntimeException> INSTANCE = new Main.Thrower<>();

      public void sneakyThrow(Throwable exception) throws T {
         throw exception;
      }
   }
}
