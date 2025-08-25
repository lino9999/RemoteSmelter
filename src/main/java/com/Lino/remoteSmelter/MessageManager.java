package com.Lino.remoteSmelter;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final RemoteSmelter plugin;
    private FileConfiguration messagesConfig;
    private File messagesFile;
    private final Map<String, String> messages = new HashMap<>();

    public MessageManager(RemoteSmelter plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    private void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");

        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        for (String key : messagesConfig.getKeys(true)) {
            if (!messagesConfig.isConfigurationSection(key)) {
                messages.put(key, ChatColor.translateAlternateColorCodes('&', messagesConfig.getString(key)));
            }
        }
    }

    public void reload() {
        messages.clear();
        loadMessages();
    }

    public String getMessage(String key) {
        return messages.getOrDefault(key, "&cMessage not found: " + key);
    }

    public String getMessage(String key, String... replacements) {
        String message = getMessage(key);

        if (replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }

        return message;
    }

    public void sendMessage(Player player, String key) {
        player.sendMessage(getMessage(key));
    }

    public void sendMessage(Player player, String key, String... replacements) {
        player.sendMessage(getMessage(key, replacements));
    }
}