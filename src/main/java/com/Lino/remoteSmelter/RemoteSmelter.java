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

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RemoteSmelter extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Economy economy;
    private DatabaseManager databaseManager;
    private MessageManager messageManager;
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
        saveResource("messages.yml", false);

        messageManager = new MessageManager(this);
        databaseManager = new DatabaseManager(this);

        try {
            databaseManager.initialize();
        } catch (SQLException e) {
            getLogger().severe(messageManager.getMessage("error.database-error"));
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadConfig();

        if (useEconomy && !setupEconomy()) {
            getLogger().severe(messageManager.getMessage("error.vault-not-found"));
            useEconomy = false;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("remotesmelter").setExecutor(this);
        getCommand("remotesmelter").setTabCompleter(this);

        playerSmelters.putAll(databaseManager.loadAllSmelters());

        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllSmelters();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messageManager.getMessage("commands.player-only"));
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
                    messageManager.sendMessage(player, "commands.create.usage");
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
                messageManager.sendMessage(player, "commands.cost.display", "{COST}", String.valueOf(smelterCost));
                break;

            case "config":
                if (!player.hasPermission("remotesmelter.admin")) {
                    messageManager.sendMessage(player, "commands.config.no-permission");
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    loadConfig();
                    messageManager.reload();
                    messageManager.sendMessage(player, "commands.config.reloaded");
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
        player.sendMessage(messageManager.getMessage("commands.help.header"));
        player.sendMessage(messageManager.getMessage("commands.help.create"));
        player.sendMessage(messageManager.getMessage("commands.help.view"));
        player.sendMessage(messageManager.getMessage("commands.help.limit"));
        player.sendMessage(messageManager.getMessage("commands.help.cost"));
        if (player.hasPermission("remotesmelter.admin")) {
            player.sendMessage(messageManager.getMessage("commands.help.config"));
        }
    }

    private void createSmelter(Player player, String name) {
        Block block = player.getTargetBlock(null, 5);

        if (!(block.getState() instanceof Furnace)) {
            messageManager.sendMessage(player, "commands.create.not-furnace");
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<String, SmelterData> smelters = playerSmelters.computeIfAbsent(uuid, k -> new HashMap<>());

        if (databaseManager.smelterExists(uuid, name)) {
            messageManager.sendMessage(player, "commands.create.name-exists");
            return;
        }

        int limit = getPlayerLimit(player);
        if (limit != -1 && smelters.size() >= limit) {
            messageManager.sendMessage(player, "commands.create.limit-reached");
            return;
        }

        if (databaseManager.isLocationRegistered(block.getLocation())) {
            messageManager.sendMessage(player, "commands.create.already-registered");
            return;
        }

        if (useEconomy && economy != null && smelterCost > 0) {
            if (!economy.has(player, smelterCost)) {
                messageManager.sendMessage(player, "commands.create.insufficient-funds", "{COST}", String.valueOf(smelterCost));
                return;
            }
            economy.withdrawPlayer(player, smelterCost);
            messageManager.sendMessage(player, "commands.create.funds-deducted", "{COST}", String.valueOf(smelterCost));
        }

        if (databaseManager.addSmelter(uuid, name, block.getLocation())) {
            smelters.put(name, new SmelterData(name, block.getLocation()));
            messageManager.sendMessage(player, "commands.create.success", "{NAME}", name);
        }
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
            messageManager.sendMessage(player, "commands.limit.unlimited", "{CURRENT}", String.valueOf(current));
        } else {
            messageManager.sendMessage(player, "commands.limit.limited",
                    "{CURRENT}", String.valueOf(current),
                    "{LIMIT}", String.valueOf(limit));
        }
    }

    private void openSmelterGUI(Player player) {
        Map<String, SmelterData> smelters = playerSmelters.get(player.getUniqueId());

        if (smelters == null || smelters.isEmpty()) {
            messageManager.sendMessage(player, "commands.view.no-smelters");
            return;
        }

        int size = Math.min(54, ((smelters.size() - 1) / 9 + 1) * 9);
        Inventory gui = Bukkit.createInventory(null, size, messageManager.getMessage("commands.view.gui-title"));

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
            lore.add(messageManager.getMessage("gui.location",
                    "{X}", String.valueOf(data.location.getBlockX()),
                    "{Y}", String.valueOf(data.location.getBlockY()),
                    "{Z}", String.valueOf(data.location.getBlockZ())));
            lore.add(messageManager.getMessage("gui.world", "{WORLD}", data.location.getWorld().getName()));

            String status = furnace.getBurnTime() > 0 ?
                    messageManager.getMessage("gui.status-active") :
                    messageManager.getMessage("gui.status-inactive");
            lore.add(messageManager.getMessage("gui.status", "{STATUS}", status));

            lore.add("");
            lore.add(messageManager.getMessage("gui.click-access"));
            lore.add(messageManager.getMessage("gui.shift-delete"));

            meta.setLore(lore);
            item.setItemMeta(meta);

            gui.setItem(slot++, item);
        }

        player.openInventory(gui);
    }

    private void openConfigGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, messageManager.getMessage("commands.config.gui-title"));

        ItemStack economyItem = new ItemStack(Material.GOLD_INGOT);
        ItemMeta economyMeta = economyItem.getItemMeta();
        economyMeta.setDisplayName(messageManager.getMessage("gui.economy.title"));

        String enabledStatus = useEconomy ?
                messageManager.getMessage("gui.economy.enabled-yes") :
                messageManager.getMessage("gui.economy.enabled-no");

        economyMeta.setLore(Arrays.asList(
                messageManager.getMessage("gui.economy.enabled", "{STATUS}", enabledStatus),
                messageManager.getMessage("gui.economy.cost", "{COST}", String.valueOf(smelterCost)),
                "",
                messageManager.getMessage("gui.economy.click-toggle"),
                messageManager.getMessage("gui.economy.right-click-cost")
        ));
        economyItem.setItemMeta(economyMeta);

        ItemStack groupsItem = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta groupsMeta = groupsItem.getItemMeta();
        groupsMeta.setDisplayName(messageManager.getMessage("gui.groups.title"));
        groupsMeta.setLore(Arrays.asList(
                messageManager.getMessage("gui.groups.description"),
                "",
                messageManager.getMessage("gui.groups.click-open")
        ));
        groupsItem.setItemMeta(groupsMeta);

        ItemStack saveItem = new ItemStack(Material.EMERALD);
        ItemMeta saveMeta = saveItem.getItemMeta();
        saveMeta.setDisplayName(messageManager.getMessage("gui.save.title"));
        saveItem.setItemMeta(saveMeta);

        gui.setItem(11, economyItem);
        gui.setItem(13, groupsItem);
        gui.setItem(15, saveItem);

        player.openInventory(gui);
    }

    private void openGroupsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, messageManager.getMessage("commands.config.groups-gui-title"));

        int slot = 0;
        for (Map.Entry<String, Integer> entry : groupLimits.entrySet()) {
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + entry.getKey());

            String limitText = entry.getValue() == -1 ?
                    messageManager.getMessage("gui.groups.limit-unlimited") :
                    ChatColor.YELLOW + String.valueOf(entry.getValue());

            meta.setLore(Arrays.asList(
                    messageManager.getMessage("gui.groups.limit", "{LIMIT}", limitText),
                    "",
                    messageManager.getMessage("gui.groups.left-increase"),
                    messageManager.getMessage("gui.groups.right-decrease"),
                    messageManager.getMessage("gui.groups.shift-delete")
            ));
            item.setItemMeta(meta);
            gui.setItem(slot++, item);
        }

        ItemStack addItem = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = addItem.getItemMeta();
        addMeta.setDisplayName(messageManager.getMessage("gui.groups.add-new"));
        addItem.setItemMeta(addMeta);
        gui.setItem(53, addItem);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String title = event.getView().getTitle();

        if (title.equals(messageManager.getMessage("commands.view.gui-title"))) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            String name = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
            Map<String, SmelterData> smelters = playerSmelters.get(player.getUniqueId());

            if (smelters == null || !smelters.containsKey(name)) return;

            if (event.isShiftClick()) {
                if (databaseManager.removeSmelter(player.getUniqueId(), name)) {
                    smelters.remove(name);
                    messageManager.sendMessage(player, "smelter.deleted", "{NAME}", name);
                    player.closeInventory();
                }
            } else {
                SmelterData data = smelters.get(name);
                Block block = data.location.getBlock();

                if (!(block.getState() instanceof Furnace)) {
                    messageManager.sendMessage(player, "smelter.not-exists");
                    databaseManager.removeSmelter(player.getUniqueId(), name);
                    smelters.remove(name);
                    player.closeInventory();
                    return;
                }

                Furnace furnace = (Furnace) block.getState();
                player.closeInventory();
                player.openInventory(furnace.getInventory());
                viewingPlayer.put(player.getUniqueId(), name);
            }
        } else if (title.equals(messageManager.getMessage("commands.config.gui-title"))) {
            event.setCancelled(true);

            if (event.getSlot() == 11) {
                if (event.isLeftClick()) {
                    useEconomy = !useEconomy;
                    String status = useEconomy ? "enabled" : "disabled";
                    player.sendMessage(ChatColor.GREEN + "Economy " + status + "!");
                    openConfigGUI(player);
                } else if (event.isRightClick()) {
                    player.closeInventory();
                    settingCost.add(player.getUniqueId());
                    messageManager.sendMessage(player, "commands.config.set-cost-prompt", "{COST}", String.valueOf(smelterCost));
                }
            } else if (event.getSlot() == 13) {
                openGroupsGUI(player);
            } else if (event.getSlot() == 15) {
                saveConfiguration();
                messageManager.sendMessage(player, "commands.config.saved");
                player.closeInventory();
            }
        } else if (title.equals(messageManager.getMessage("commands.config.groups-gui-title"))) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

            if (event.getSlot() == 53) {
                player.closeInventory();
                addingGroup.add(player.getUniqueId());
                messageManager.sendMessage(player, "commands.config.add-group-prompt");
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
                messageManager.sendMessage(player, "commands.config.group-name-empty");
                return;
            }

            if (groupLimits.containsKey(groupName)) {
                messageManager.sendMessage(player, "commands.config.group-exists");
                return;
            }

            groupLimits.put(groupName, 5);

            Bukkit.getScheduler().runTask(this, () -> {
                messageManager.sendMessage(player, "commands.config.group-created", "{NAME}", groupName);
                openGroupsGUI(player);
            });
        } else if (settingCost.contains(uuid)) {
            event.setCancelled(true);
            settingCost.remove(uuid);

            try {
                double newCost = Double.parseDouble(event.getMessage().trim());

                if (newCost < 0) {
                    messageManager.sendMessage(player, "commands.config.cost-negative");
                    return;
                }

                smelterCost = newCost;

                Bukkit.getScheduler().runTask(this, () -> {
                    messageManager.sendMessage(player, "commands.config.cost-set", "{COST}", String.valueOf(smelterCost));
                    openConfigGUI(player);
                });
            } catch (NumberFormatException e) {
                messageManager.sendMessage(player, "commands.config.invalid-number");
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
        Map.Entry<UUID, String> owner = databaseManager.getSmelterOwner(loc);

        if (owner != null) {
            UUID ownerUUID = owner.getKey();
            String smelterName = owner.getValue();

            Map<String, SmelterData> smelters = playerSmelters.get(ownerUUID);
            if (smelters != null) {
                smelters.remove(smelterName);
            }

            databaseManager.removeSmelterByLocation(loc);

            Player ownerPlayer = Bukkit.getPlayer(ownerUUID);
            if (ownerPlayer != null && ownerPlayer.isOnline()) {
                messageManager.sendMessage(ownerPlayer, "smelter.destroyed", "{NAME}", smelterName);
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

    public static class SmelterData {
        final String name;
        final Location location;

        public SmelterData(String name, Location location) {
            this.name = name;
            this.location = location;
        }
    }
}