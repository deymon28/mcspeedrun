package org.speedrun.speedrun.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bundled message files and their customizable copies in {@code plugins/Speedrun/lang}.
 *
 * <p>Customized message files are never overwritten. When a plugin update adds messages, the
 * file on disk is rebuilt from the bundled file with every customized message kept, so new
 * keys become visible for editing; the previous file is backed up first. At runtime messages
 * are resolved from the disk file, then the bundled translation, then bundled English, so a
 * missing key never shows up as an error in game.</p>
 */
public final class LanguageFiles {
    public static final String DEFAULT_LANGUAGE = "en";
    /** Bundled languages in display order. */
    public static final List<String> SUPPORTED = List.of("en", "uk", "de", "es", "fr", "pt_BR", "pl", "zh_CN");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("eng", "en"), Map.entry("english", "en"),
            Map.entry("ua", "uk"), Map.entry("ukrainian", "uk"),
            Map.entry("deutsch", "de"), Map.entry("german", "de"),
            Map.entry("spanish", "es"), Map.entry("espanol", "es"),
            Map.entry("french", "fr"), Map.entry("francais", "fr"),
            Map.entry("pt", "pt_BR"), Map.entry("portuguese", "pt_BR"), Map.entry("brazilian", "pt_BR"),
            Map.entry("polish", "pl"), Map.entry("polski", "pl"),
            Map.entry("zh", "zh_CN"), Map.entry("chinese", "zh_CN"));
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private LanguageFiles() {
    }

    /**
     * Normalizes user input such as {@code EN}, {@code pt-br}, or {@code chinese}.
     *
     * @return the supported language code, or null when the language is not bundled
     */
    public static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        String normalized = rawCode.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        String alias = ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        for (String code : SUPPORTED) {
            if (code.toLowerCase(Locale.ROOT).equals(normalized)) {
                return code;
            }
        }
        return null;
    }

    /**
     * Ensures the disk copy exists and contains every bundled message.
     *
     * @param file        the language file on disk
     * @param bundledText the bundled file content
     * @return number of messages added to an existing file (0 when unchanged or newly created)
     */
    public static int upgrade(Path file, String bundledText) throws IOException, InvalidConfigurationException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Refusing to update a symbolic-link language file.");
        }
        if (!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, bundledText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return 0;
        }
        YamlConfiguration bundled = ConfigFileStore.parse(bundledText);
        byte[] originalBytes = Files.readAllBytes(file);
        YamlConfiguration disk = ConfigFileStore.parse(new String(originalBytes, StandardCharsets.UTF_8));

        int missing = 0;
        for (String key : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && !disk.contains(key, true)) {
                missing++;
            }
        }
        if (missing == 0) {
            return 0;
        }

        YamlConfiguration merged = LanguageConfigLoader.merge(bundled, new YamlConfiguration(), disk);
        // Keep keys that only exist in the customized file (for example removed messages).
        for (String key : disk.getKeys(true)) {
            if (!disk.isConfigurationSection(key) && !merged.contains(key, true)) {
                merged.set(key, ConfigFileStore.copyValue(disk.get(key)));
            }
        }

        Path backups = file.toAbsolutePath().getParent().resolve("lang-backups");
        Files.createDirectories(backups);
        String name = file.getFileName().toString().replace(".yml", "");
        Path backup = backups.resolve(name + "-" + STAMP.format(Instant.now()) + ".yml");
        for (int attempt = 2; Files.exists(backup); attempt++) {
            backup = backups.resolve(name + "-" + STAMP.format(Instant.now()) + "-" + attempt + ".yml");
        }
        Files.write(backup, originalBytes, StandardOpenOption.CREATE_NEW);
        ConfigFileStore.writeAtomically(file, merged);
        return missing;
    }
}
