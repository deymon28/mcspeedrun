package org.speedrun.speedrun.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFilesTest {
    @TempDir
    Path folder;

    @Test
    void normalizesCodesAndAliases() {
        assertEquals("en", LanguageFiles.normalize("EN"));
        assertEquals("uk", LanguageFiles.normalize("ua"));
        assertEquals("pt_BR", LanguageFiles.normalize("pt-br"));
        assertEquals("zh_CN", LanguageFiles.normalize("Chinese"));
        assertEquals("de", LanguageFiles.normalize(" de "));
        assertNull(LanguageFiles.normalize("xx"));
        assertNull(LanguageFiles.normalize(""));
    }

    @Test
    void addsNewMessagesAndKeepsCustomizedOnes() throws Exception {
        Path file = folder.resolve("en.yml");
        Files.writeString(file, "prefix: \"&6[SR] \"\ncommands:\n  reloaded: \"Custom reload text\"\nold:\n  removed: \"kept\"\n");
        String bundled = "prefix: \"\"\ncommands:\n  reloaded: \"Reloaded.\"\n  run-started: \"Started.\"\n";

        int added = LanguageFiles.upgrade(file, bundled);

        YamlConfiguration result = ConfigFileStore.read(file);
        assertEquals(1, added);
        assertEquals("&6[SR] ", result.getString("prefix"));
        assertEquals("Custom reload text", result.getString("commands.reloaded"));
        assertEquals("Started.", result.getString("commands.run-started"));
        assertEquals("kept", result.getString("old.removed"));
        try (Stream<Path> backups = Files.list(folder.resolve("lang-backups"))) {
            assertEquals(1, backups.count());
        }
        assertEquals(0, LanguageFiles.upgrade(file, bundled), "complete files are not rewritten again");
    }

    @Test
    void everyBundledLanguageHasTheSameKeysAsEnglish() throws Exception {
        Set<String> english = leafKeys(bundled("en"));
        for (String code : LanguageFiles.SUPPORTED) {
            Set<String> keys = leafKeys(bundled(code));
            assertEquals(english, keys, "lang/" + code + ".yml keys");
        }
    }

    @Test
    void translationsKeepEveryPlaceholder() throws Exception {
        YamlConfiguration english = bundled("en");
        for (String code : LanguageFiles.SUPPORTED) {
            YamlConfiguration translated = bundled(code);
            for (String key : leafKeys(english)) {
                for (String placeholder : placeholders(english.getString(key, ""))) {
                    assertTrue(translated.getString(key, "").contains(placeholder),
                            "lang/" + code + ".yml " + key + " keeps " + placeholder);
                }
            }
        }
    }

    private static YamlConfiguration bundled(String code) throws Exception {
        try (InputStream input = LanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            assertNotNull(input, "lang/" + code + ".yml is bundled");
            return ConfigFileStore.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Set<String> leafKeys(YamlConfiguration config) {
        return config.getKeys(true).stream().filter(key -> !config.isConfigurationSection(key)).collect(Collectors.toSet());
    }

    private static Set<String> placeholders(String text) {
        return java.util.regex.Pattern.compile("%[a-z_]+%").matcher(text).results()
                .map(java.util.regex.MatchResult::group).collect(Collectors.toSet());
    }
}
