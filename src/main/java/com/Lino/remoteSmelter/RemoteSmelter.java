package com.Lino.remoteSmelter;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RemoteSmelter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy economy;
    private final Map<UUID, Map<String, SmelterData>> playerSmelters = new ConcurrentHashMap<>();
    private final Map<UUID, String> viewingPlayer = new HashMap<>();
    private final Map<String, Integer> groupLimits = new HashMap<>();
    private final Set<UUID> addingGroup = new HashSet<>();
    private final Set<UUID> settingCost = new HashSet<>();
    private double smelterCost = 100.0;
    private boolean useEconomy = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        if (useEconomy && !setupEconomy()) {
            getLogger().severe("Vault not found! Economy features disabled.");
            useEconomy = false;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("remotesmelter").setExecutor(this);
        getCommand("remotesmelter").setTabCompleter(this);

        loadSmelters();

        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllSmelters();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @Override
    public void onDisable() {
        saveSmelters();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        useEconomy = config.getBoolean("economy.enabled", false);
        smelterCost = config.getDouble("economy.creation-cost", 100.0);

        groupLimits.clear();
        ConfigurationSection groups = config.getConfigurationSection("groups");
        if (groups != null) {
            for (String group : groups.getKeys(false)) {
                groupLimits.put(group, groups.getInt(group));
            }
        }

        if (groupLimits.isEmpty()) {
            groupLimits.put("default", 5);
            groupLimits.put("vip", 10);
            groupLimits.put("admin", -1);
        }
    }

    private void saveConfiguration() {
        FileConfiguration config = getConfig();
        config.set("economy.enabled", useEconomy);
        config.set("economy.creation-cost", smelterCost);

        config.set("groups", null);
        for (Map.Entry<String, Integer> entry : groupLimits.entrySet()) {
            config.set("groups." + entry.getKey(), entry.getValue());
        }

        saveConfig();
    }

    private void loadSmelters() {
        FileConfiguration config = getConfig();
        ConfigurationSection smelters = config.getConfigurationSection("smelters");
        if (smelters == null) return;

        for (String uuidStr : smelters.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            Map<String, SmelterData> playerData = new HashMap<>();

            ConfigurationSection playerSection = smelters.getConfigurationSection(uuidStr);
            for (String name : playerSection.getKeys(false)) {
                ConfigurationSection smelterSection = playerSection.getConfigurationSection(name);
                Location loc = new Location(
                        Bukkit.getWorld(smelterSection.getString("world")),
                        smelterSection.getInt("x"),
                        smelterSection.getInt("y"),
                        smelterSection.getInt("z")
                );

                SmelterData data = new SmelterData(name, loc);
                playerData.put(name, data);
            }

            playerSmelters.put(uuid, playerData);
        }
    }

    private void saveSmelters() {
        FileConfiguration config = getConfig();
        config.set("smelters", null);

        for (Map.Entry<UUID, Map<String, SmelterData>> entry : playerSmelters.entrySet()) {
            String uuidStr = entry.getKey().toString();
            for (Map.Entry<String, SmelterData> smelterEntry : entry.getValue().entrySet()) {
                SmelterData data = smelterEntry.getValue();
                String path = "smelters." + uuidStr + "." + data.name;
                config.set(path + ".world", data.location.getWorld().getName());
                config.set(path + ".x", data.location.getBlockX());
                config.set(path + ".y", data.location.getBlockY());
                config.set(path + ".z", data.location.getBlockZ());
            }
        }

        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /remotesmelter create <name>");
                    return true;
                }
                createSmelter(player, args[1]);
                break;

            case "view":
                openSmelterGUI(player);
                break;

            case "limit":
                showLimit(player);
                break;

            case "cost":
                player.sendMessage(ChatColor.GREEN + "Cost to create a smelter: " + ChatColor.GOLD + "$" + smelterCost);
                break;

            case "config":
                if (!player.hasPermission("remotesmelter.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    loadConfig();
                    player.sendMessage(ChatColor.GREEN + "Configuration reloaded!");
                } else {
                    openConfigGUI(player);
                }
                break;

            default:
                sendHelp(player);
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("create", "view", "limit", "cost", "config")
                    .stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("config")) {
            return Collections.singletonList("reload");
        }

        return Collections.emptyList();
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== RemoteSmelter Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/remotesmelter create <name>" + ChatColor.WHITE + " - Register a furnace");
        player.sendMessage(ChatColor.YELLOW + "/remotesmelter view" + ChatColor.WHITE + " - Open smelter GUI");
        player.sendMessage(ChatColor.YELLOW + "/remotesmelter limit" + ChatColor.WHITE + " - View your smelter limit");
        player.sendMessage(ChatColor.YELLOW + "/remotesmelter cost" + ChatColor.WHITE + " - View creation cost");
        if (player.hasPermission("remotesmelter.admin")) {
            player.sendMessage(ChatColor.YELLOW + "/remotesmelter config" + ChatColor.WHITE + " - Open config GUI");
        }
    }

    private void createSmelter(Player player, String name) {
        Block block = player.getTargetBlock(null, 5);

        if (!(block.getState() instanceof Furnace)) {
            player.sendMessage(ChatColor.RED + "You must be looking at a furnace, blast furnace, or smoker!");
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<String, SmelterData> smelters = playerSmelters.computeIfAbsent(uuid, k -> new HashMap<>());

        if (smelters.containsKey(name)) {
            player.sendMessage(ChatColor.RED + "You already have a smelter with that name!");
            return;
        }

        int limit = getPlayerLimit(player);
        if (limit != -1 && smelters.size() >= limit) {
            player.sendMessage(ChatColor.RED + "You have reached your smelter limit!");
            return;
        }

        for (Map<String, SmelterData> playerData : playerSmelters.values()) {
            for (SmelterData data : playerData.values()) {
                if (data.location.equals(block.getLocation())) {
                    player.sendMessage(ChatColor.RED + "This furnace is already registered!");
                    return;
                }
            }
        }

        if (useEconomy && economy != null && smelterCost > 0) {
            if (!economy.has(player, smelterCost)) {
                player.sendMessage(ChatColor.RED + "You need $" + smelterCost + " to create a smelter!");
                return;
            }
            economy.withdrawPlayer(player, smelterCost);
            player.sendMessage(ChatColor.GREEN + "$" + smelterCost + " has been deducted from your account.");
        }

        smelters.put(name, new SmelterData(name, block.getLocation()));
        player.sendMessage(ChatColor.GREEN + "Smelter '" + name + "' created successfully!");
        saveSmelters();
    }

    private int getPlayerLimit(Player player) {
        for (Map.Entry<String, Integer> entry : groupLimits.entrySet()) {
            if (player.hasPermission("remotesmelter.group." + entry.getKey())) {
                return entry.getValue();
            }
        }
        return groupLimits.getOrDefault("default", 5);
    }

    private void showLimit(Player player) {
        int limit = getPlayerLimit(player);
        int current = playerSmelters.getOrDefault(player.getUniqueId(), Collections.emptyMap()).size();

        if (limit == -1) {
            player.sendMessage(ChatColor.GREEN + "You have unlimited smelters! Current: " + current);
        } else {
            player.sendMessage(ChatColor.GREEN + "Smelter limit: " + current + "/" + limit);
        }
    }

    private void openSmelterGUI(Player player) {
        Map<String, SmelterData> smelters = playerSmelters.get(player.getUniqueId());

        if (smelters == null || smelters.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You don't have any registered smelters!");
            return;
        }

        int size = Math.min(54, ((smelters.size() - 1) / 9 + 1) * 9);
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.DARK_GREEN + "Your Smelters");

        int slot = 0;
        for (Map.Entry<String, SmelterData> entry : smelters.entrySet()) {
            SmelterData data = entry.getValue();
            Block block = data.location.getBlock();

            if (!(block.getState() instanceof Furnace)) {
                continue;
            }

            Furnace furnace = (Furnace) block.getState();
            Material material = block.getType();
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(ChatColor.GREEN + data.name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Location: " + data.location.getBlockX() + ", " +
                    data.location.getBlockY() + ", " + data.location.getBlockZ());
            lore.add(ChatColor.GRAY + "World: " + data.location.getWorld().getName());

            if (furnace.getBurnTime() > 0) {
                lore.add(ChatColor.YELLOW + "Status: " + ChatColor.GREEN + "Active");
            } else {
                lore.add(ChatColor.YELLOW + "Status: " + ChatColor.RED + "Inactive");
            }

            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to access");
            lore.add(ChatColor.RED + "Shift-click to delete");

            meta.setLore(lore);
            item.setItemMeta(meta);

            gui.setItem(slot++, item);
        }

        player.openInventory(gui);
    }

    private void openConfigGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_PURPLE + "RemoteSmelter Config");

        ItemStack economyItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta economyMeta = economyItem.getItemMeta();
        economyMeta.setDisplayName(ChatColor.GOLD + "Economy Settings");
        economyMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Enabled: " + (useEconomy ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"),
                ChatColor.GRAY + "Cost: " + ChatColor.GOLD + "$" + smelterCost,
                "",
                ChatColor.YELLOW + "Click to toggle",
                ChatColor.YELLOW + "Right-click to set cost"
        ));
        economyItem.setItemMeta(economyMeta);

        ItemStack groupsItem = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta groupsMeta = groupsItem.getItemMeta();
        groupsMeta.setDisplayName(ChatColor.AQUA + "Group Limits");
        groupsMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Manage group limits",
                "",
                ChatColor.YELLOW + "Click to open"
        ));
        groupsItem.setItemMeta(groupsMeta);

        ItemStack saveItem = new ItemStack(Material.EMERALD);
        ItemMeta saveMeta = saveItem.getItemMeta();
        saveMeta.setDisplayName(ChatColor.GREEN + "Save Configuration");
        saveItem.setItemMeta(saveMeta);

        gui.setItem(11, economyItem);
        gui.setItem(13, groupsItem);
        gui.setItem(15, saveItem);

        player.openInventory(gui);
    }

    private void openGroupsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Group Limits");

        int slot = 0;
        for (Map.Entry<String, Integer> entry : groupLimits.entrySet()) {
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + entry.getKey());
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Limit: " + (entry.getValue() == -1 ? ChatColor.GREEN + "Unlimited" :
                            ChatColor.YELLOW + String.valueOf(entry.getValue())),
                    "",
                    ChatColor.YELLOW + "Left-click to increase",
                    ChatColor.YELLOW + "Right-click to decrease",
                    ChatColor.RED + "Shift-click to delete"
            ));
            item.setItemMeta(meta);
            gui.setItem(slot++, item);
        }

        ItemStack addItem = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = addItem.getItemMeta();
        addMeta.setDisplayName(ChatColor.GREEN + "Add New Group");
        addItem.setItemMeta(addMeta);
        gui.setItem(53, addItem);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String title = event.getView().getTitle();

        if (title.equals(ChatColor.DARK_GREEN + "Your Smelters")) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            String name = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
            Map<String, SmelterData> smelters = playerSmelters.get(player.getUniqueId());

            if (smelters == null || !smelters.containsKey(name)) return;

            if (event.isShiftClick()) {
                smelters.remove(name);
                player.sendMessage(ChatColor.RED + "Smelter '" + name + "' deleted!");
                player.closeInventory();
                saveSmelters();
            } else {
                SmelterData data = smelters.get(name);
                Block block = data.location.getBlock();

                if (!(block.getState() instanceof Furnace)) {
                    player.sendMessage(ChatColor.RED + "The furnace no longer exists!");
                    smelters.remove(name);
                    player.closeInventory();
                    saveSmelters();
                    return;
                }

                Furnace furnace = (Furnace) block.getState();
                player.closeInventory();
                player.openInventory(furnace.getInventory());
                viewingPlayer.put(player.getUniqueId(), name);
            }
        } else if (title.equals(ChatColor.DARK_PURPLE + "RemoteSmelter Config")) {
            event.setCancelled(true);

            if (event.getSlot() == 11) {
                if (event.isLeftClick()) {
                    useEconomy = !useEconomy;
                    player.sendMessage(ChatColor.GREEN + "Economy " + (useEconomy ? "enabled" : "disabled") + "!");
                    openConfigGUI(player);
                } else if (event.isRightClick()) {
                    player.closeInventory();
                    settingCost.add(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "Type the new smelter cost in chat (current: $" + smelterCost + "):");
                }
            } else if (event.getSlot() == 13) {
                openGroupsGUI(player);
            } else if (event.getSlot() == 15) {
                saveConfiguration();
                player.sendMessage(ChatColor.GREEN + "Configuration saved!");
                player.closeInventory();
            }
        } else if (title.equals(ChatColor.DARK_AQUA + "Group Limits")) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            if (event.getSlot() == 53) {
                player.closeInventory();
                addingGroup.add(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Type the name of the new group in chat:");
                return;
            }

            String groupName = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());

            if (!groupLimits.containsKey(groupName)) return;

            if (event.isShiftClick()) {
                groupLimits.remove(groupName);
                openGroupsGUI(player);
            } else if (event.isLeftClick()) {
                int current = groupLimits.get(groupName);
                if (current == -1) {
                    groupLimits.put(groupName, 1);
                } else {
                    groupLimits.put(groupName, current + 1);
                }
                openGroupsGUI(player);
            } else if (event.isRightClick()) {
                int current = groupLimits.get(groupName);
                if (current > 0) {
                    groupLimits.put(groupName, current - 1);
                } else if (current == 0) {
                    groupLimits.put(groupName, -1);
                }
                openGroupsGUI(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        viewingPlayer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (addingGroup.contains(uuid)) {
            event.setCancelled(true);
            addingGroup.remove(uuid);

            String groupName = event.getMessage().trim().toLowerCase();

            if (groupName.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Group name cannot be empty!");
                return;
            }

            if (groupLimits.containsKey(groupName)) {
                player.sendMessage(ChatColor.RED + "Group already exists!");
                return;
            }

            groupLimits.put(groupName, 5);

            Bukkit.getScheduler().runTask(this, () -> {
                player.sendMessage(ChatColor.GREEN + "Group '" + groupName + "' created with limit 5!");
                openGroupsGUI(player);
            });
        } else if (settingCost.contains(uuid)) {
            event.setCancelled(true);
            settingCost.remove(uuid);

            try {
                double newCost = Double.parseDouble(event.getMessage().trim());

                if (newCost < 0) {
                    player.sendMessage(ChatColor.RED + "Cost cannot be negative!");
                    return;
                }

                smelterCost = newCost;

                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage(ChatColor.GREEN + "Smelter cost set to $" + smelterCost);
                    openConfigGUI(player);
                });
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid number! Please enter a valid cost.");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        viewingPlayer.remove(uuid);
        addingGroup.remove(uuid);
        settingCost.remove(uuid);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();

        for (Map.Entry<UUID, Map<String, SmelterData>> entry : playerSmelters.entrySet()) {
            Iterator<Map.Entry<String, SmelterData>> iter = entry.getValue().entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, SmelterData> smelterEntry = iter.next();
                if (smelterEntry.getValue().location.equals(loc)) {
                    iter.remove();
                    Player owner = Bukkit.getPlayer(entry.getKey());
                    if (owner != null && owner.isOnline()) {
                        owner.sendMessage(ChatColor.RED + "Your smelter '" + smelterEntry.getKey() + "' has been destroyed!");
                    }
                    saveSmelters();
                    return;
                }
            }
        }
    }

    private void updateAllSmelters() {
        for (Map<String, SmelterData> playerData : playerSmelters.values()) {
            for (SmelterData data : playerData.values()) {
                Block block = data.location.getBlock();
                if (block.getState() instanceof Furnace) {
                    Furnace furnace = (Furnace) block.getState();
                    if (furnace.getBurnTime() > 0 || furnace.getCookTime() > 0) {
                        furnace.update();
                    }
                }
            }
        }
    }

    private static class SmelterData {
        final String name;
        final Location location;

        SmelterData(String name, Location location) {
            this.name = name;
            this.location = location;
        }
    }
}