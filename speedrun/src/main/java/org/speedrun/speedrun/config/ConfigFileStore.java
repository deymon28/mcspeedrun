package org.speedrun.speedrun.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict YAML loading and independent snapshots shared by migration and the web editor. */
public final class ConfigFileStore {
    private ConfigFileStore() {
    }

    public static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static YamlConfiguration parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException | RuntimeException exception) {
            // Parser exceptions can include entire configuration lines, including credentials.
            throw new InvalidConfigurationException("Invalid YAML configuration. The original file has not been replaced.");
        }
        return config;
    }

    public static YamlConfiguration copy(FileConfiguration source) {
        try {
            YamlConfiguration copy = parse(source.saveToString());
            if (source.getDefaults() != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                copySection(source.getDefaults(), defaults);
                copy.setDefaults(defaults);
            }
            return copy;
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Configuration cannot be copied safely.");
        }
    }

    public static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection section) {
                copySection(section, target.createSection(key));
            } else {
                target.set(key, copyValue(value));
            }
            target.setComments(key, source.getComments(key));
            target.setInlineComments(key, source.getInlineComments(key));
        }
    }

    public static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, copyValue(item)));
            return copy;
        }
        return value;
    }

    /** Temporary files are owner-only on POSIX systems; keep the permissions of the replaced file. */
    private static void copyPermissions(Path source, Path target) {
        try {
            if (Files.exists(source) && Files.getFileStore(source).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Permissions stay at the platform default.
        }
    }

    public static void writeAtomically(Path target, FileConfiguration config) throws IOException {
        writeAtomically(target, config.saveToString());
    }

    /** Writes YAML text atomically; usable off the server thread with a snapshot string. */
    public static void writeAtomically(Path target, String yaml) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Refusing to replace a symbolic-link configuration file.");
        }
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".speedrun-config-", ".tmp");
        try {
            copyPermissions(absolute, temporary);
            byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            // If the filesystem cannot provide an atomic replacement, retain the original.
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
