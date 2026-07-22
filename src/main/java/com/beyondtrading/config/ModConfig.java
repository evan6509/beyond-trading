package com.beyondtrading.config;

import com.beyondtrading.BeyondTradingMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance;

    public LLMConfig llm = LLMConfig.defaults();

    public static ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static void reload() {
        instance = load();
    }

    private static ModConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("beyondtrading.json");

        if (Files.exists(configFile)) {
            try {
                String content = Files.readString(configFile);
                var config = GSON.fromJson(content, ModConfig.class);
                if (config != null && config.llm != null) {
                    return config;
                }
            } catch (IOException e) {
                BeyondTradingMod.LOGGER.warn("Failed to load config, using defaults", e);
            }
        }

        ModConfig defaults = new ModConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("beyondtrading.json");
        try {
            Files.createDirectories(configDir);
            Files.writeString(configFile, GSON.toJson(this));
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to save config", e);
        }
    }
}
