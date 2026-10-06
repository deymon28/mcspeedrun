package org.speedrun.speedrun.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/** Bundled English, selected translation, then compatible local overrides, in that order. */
public final class LanguageConfigLoader {
    private LanguageConfigLoader() {
    }

    public static YamlConfiguration merge(FileConfiguration english, FileConfiguration selected,
                                          FileConfiguration overrides) {
        YamlConfiguration result = ConfigFileStore.copy(english);
        overlay(selected, result);
        overlay(overrides, result);
        return result;
    }

    private static void overlay(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            Object existing = target.get(key);
            if (value instanceof ConfigurationSection section) {
                if (existing != null && !(existing instanceof ConfigurationSection)) {
                    continue;
                }
                ConfigurationSection child = target.getConfigurationSection(key);
                overlay(section, child == null ? target.createSection(key) : child);
            } else if (value instanceof String && (existing == null || existing instanceof String)) {
                target.set(key, value);
            } else if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)
                    && (existing == null || existing instanceof List<?>)) {
                target.set(key, ConfigFileStore.copyValue(list));
            }
        }
    }
}
