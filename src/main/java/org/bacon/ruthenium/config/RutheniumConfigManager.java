package org.bacon.ruthenium.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.debug.FallbackValidator;
import org.bacon.ruthenium.debug.RegionDebug;

/**
 * Loads and persists Ruthenium's JSON config in the Fabric config directory.
 */
public final class RutheniumConfigManager {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();

    private static volatile RutheniumConfig ACTIVE = RutheniumConfig.defaults();

    private RutheniumConfigManager() {
    }

    public static RutheniumConfig getActive() {
        return ACTIVE;
    }

    public static Path getConfigPath() {
        return resolveConfigDir().resolve("ruthenium.json");
    }

    public static RutheniumConfig loadIfPresent() {
        return loadInternal(false);
    }

    public static RutheniumConfig loadOrCreate() {
        return loadInternal(true);
    }

    private static RutheniumConfig loadInternal(final boolean createIfMissing) {
        final Logger logger = Ruthenium.getLogger();
        final Path path = getConfigPath();

        if (!isFabricRuntime()) {
            ACTIVE = RutheniumConfig.defaults().validated();
            return ACTIVE;
        }

        if (createIfMissing) {
            try {
                Files.createDirectories(path.getParent());
            } catch (final IOException ex) {
                logger.warn("Failed to create config directory for {}", path, ex);
                ACTIVE = RutheniumConfig.defaults().validated();
                return ACTIVE;
            }
        }

        if (!Files.exists(path)) {
            final RutheniumConfig defaults = RutheniumConfig.defaults().validated();
            if (createIfMissing) {
                if (save(path, defaults)) {
                    logger.info("Created default Ruthenium config at {}", path);
                }
            }
            ACTIVE = defaults;
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final RutheniumConfig loaded = Objects.requireNonNullElseGet(
                GSON.fromJson(reader, RutheniumConfig.class),
                RutheniumConfig::defaults
            ).validated();
            ACTIVE = loaded;
            return loaded;
        } catch (final IOException | JsonParseException ex) {
            logger.warn("Failed to load Ruthenium config at {} (resetting to defaults)", path, ex);
            backupCorrupt(path);
            final RutheniumConfig defaults = RutheniumConfig.defaults().validated();
            save(path, defaults);
            ACTIVE = defaults;
            return defaults;
        }
    }

    public static RutheniumConfig reloadAndApply() {
        final RutheniumConfig config = loadOrCreate();
        applyRuntime(config);
        return config;
    }

    public static void applyRuntime(final RutheniumConfig config) {
        Objects.requireNonNull(config, "config");

        // Region debug defaults
        RegionDebug.setEnabledCategoriesQuietly(config.enabledDebugCategories());

        // Fallback validation
        FallbackValidator.setLogFallbacks(config.fallback.logFallbacks);
        FallbackValidator.setAssertMode(config.fallback.assertMode);
    }

    private static boolean save(final Path path, final RutheniumConfig config) {
        final Logger logger = Ruthenium.getLogger();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
            return true;
        } catch (final IOException ex) {
            logger.warn("Failed to write Ruthenium config to {}", path, ex);
            return false;
        }
    }

    private static void backupCorrupt(final Path path) {
        final Logger logger = Ruthenium.getLogger();
        if (!Files.exists(path)) {
            return;
        }
        final String suffix = ".bak-" + Instant.now().toEpochMilli();
        final Path backup = path.resolveSibling(path.getFileName().toString() + suffix);
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("Backed up corrupt config to {}", backup);
        } catch (final IOException ex) {
            logger.warn("Failed to back up corrupt config {}", path, ex);
        }
    }

    private static Path resolveConfigDir() {
        try {
            final Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            final Object instance = loader.getMethod("getInstance").invoke(null);
            final Object path = loader.getMethod("getConfigDir").invoke(instance);
            if (path instanceof Path configDir) {
                return configDir;
            }
        } catch (final Throwable ignored) {
            // Not running under Fabric in unit tests or classpath doesn't include loader.
        }
        return Path.of("config");
    }

    private static boolean isFabricRuntime() {
        try {
            final Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            final Object instance = loader.getMethod("getInstance").invoke(null);
            return instance != null;
        } catch (final Throwable ignored) {
            return false;
        }
    }
}
