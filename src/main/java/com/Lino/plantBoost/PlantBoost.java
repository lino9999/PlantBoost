package com.Lino.plantBoost;

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlantBoost extends JavaPlugin implements Listener, TabCompleter {
    private boolean boostActive = false;
    private String boosterName;
    private UUID boosterUUID;
    private BukkitTask announceTask;
    private BukkitTask bossBarTask;
    private BukkitTask endTask;
    private BukkitTask particleTask;
    private BossBar bossBar;
    private long endTimestamp;
    private long startTimestamp;
    private long totalDurationMillis;
    private String currentBoostType;

    private Map<String, BoostType> boostTypes = new HashMap<>();
    private Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private Map<UUID, BoostStatistics> playerStats = new ConcurrentHashMap<>();
    private Set<UUID> playersWithBossBar = new HashSet<>();

    private int announceInterval;
    private boolean particlesEnabled;
    private boolean soundsEnabled;
    private int cooldownMinutes;
    private boolean saveStats;
    private boolean allowStack;
    private int maxStackDuration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadStatistics();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("plantboost").setTabCompleter(this);

        // Recupera boost attivo dopo reload
        if (getConfig().contains("active-boost")) {
            recoverActiveBoost();
        }

        getLogger().info("PlantBoost v" + getDescription().getVersion() + " abilitato!");
    }

    @Override
    public void onDisable() {
        if (boostActive) {
            saveActiveBoost();
        }
        if (saveStats) {
            saveStatistics();
        }
        cleanupBoost();
    }

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();
        announceInterval = cfg.getInt("announce-interval", 5);
        particlesEnabled = cfg.getBoolean("effects.particles", true);
        soundsEnabled = cfg.getBoolean("effects.sounds", true);
        cooldownMinutes = cfg.getInt("cooldown-minutes", 30);
        saveStats = cfg.getBoolean("save-statistics", true);
        allowStack = cfg.getBoolean("allow-stack", false);
        maxStackDuration = cfg.getInt("max-stack-duration", 120);

        boostTypes.clear();
        if (!cfg.contains("boosts")) {
            createDefaultBoosts();
        }

        for (String key : cfg.getConfigurationSection("boosts").getKeys(false)) {
            int duration = cfg.getInt("boosts." + key + ".duration");
            int speed = cfg.getInt("boosts." + key + ".speed");
            String matName = cfg.getString("boosts." + key + ".material");
            Material mat = Material.matchMaterial(matName != null ? matName.toUpperCase() : "");
            if (mat == null) {
                getLogger().warning("Material per boost '" + key + "' non valido: " + matName);
                mat = Material.WHEAT_SEEDS;
            }

            List<String> lore = cfg.getStringList("boosts." + key + ".lore");
            String displayName = cfg.getString("boosts." + key + ".display-name", key + " boost");
            boolean glowing = cfg.getBoolean("boosts." + key + ".glowing", true);
            List<String> worlds = cfg.getStringList("boosts." + key + ".allowed-worlds");

            boostTypes.put(key, new BoostType(duration, speed, mat, lore, displayName, glowing, worlds));
        }
    }

    private void createDefaultBoosts() {
        FileConfiguration cfg = getConfig();
        cfg.set("boosts.small.duration", 5);
        cfg.set("boosts.small.speed", 10);
        cfg.set("boosts.small.material", "WHEAT_SEEDS");
        cfg.set("boosts.small.display-name", "&aBoost Piccolo");
        cfg.set("boosts.small.lore", Arrays.asList("&7Durata: 5 minuti", "&7Velocità: 10x"));
        cfg.set("boosts.small.glowing", true);

        cfg.set("boosts.medium.duration", 15);
        cfg.set("boosts.medium.speed", 20);
        cfg.set("boosts.medium.material", "GOLDEN_CARROT");
        cfg.set("boosts.medium.display-name", "&6Boost Medio");
        cfg.set("boosts.medium.lore", Arrays.asList("&7Durata: 15 minuti", "&7Velocità: 20x"));
        cfg.set("boosts.medium.glowing", true);

        cfg.set("boosts.large.duration", 30);
        cfg.set("boosts.large.speed", 50);
        cfg.set("boosts.large.material", "ENCHANTED_GOLDEN_APPLE");
        cfg.set("boosts.large.display-name", "&dBoost Grande");
        cfg.set("boosts.large.lore", Arrays.asList("&7Durata: 30 minuti", "&7Velocità: 50x"));
        cfg.set("boosts.large.glowing", true);

        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("plantboost")) return false;

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give":
                return handleGiveCommand(sender, args);
            case "reload":
                return handleReloadCommand(sender);
            case "stop":
                return handleStopCommand(sender);
            case "info":
                return handleInfoCommand(sender);
            case "stats":
                return handleStatsCommand(sender, args);
            case "list":
                return handleListCommand(sender);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleGiveCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plantboost.give")) {
            sender.sendMessage(colorize("&cNon hai il permesso!"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(colorize("&cUso: /plantboost give <tipo> [giocatore] [quantità]"));
            return true;
        }

        String key = args[1].toLowerCase();
        BoostType type = boostTypes.get(key);
        if (type == null) {
            sender.sendMessage(colorize("&cTipo boost sconosciuto. Disponibili: " + boostTypes.keySet()));
            return true;
        }

        Player target = null;
        if (args.length >= 3) {
            target = getServer().getPlayer(args[2]);
        } else if (sender instanceof Player) {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(colorize("&cGiocatore non trovato!"));
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                amount = Math.max(1, Math.min(64, amount));
            } catch (NumberFormatException ignored) {}
        }

        giveBoostItem(target, key, type, amount);
        sender.sendMessage(colorize("&aHai dato " + amount + "x " + type.displayName + " a " + target.getName()));

        if (!sender.equals(target)) {
            target.sendMessage(colorize("&aHai ricevuto " + amount + "x " + type.displayName + "!"));
        }

        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("plantboost.reload")) {
            sender.sendMessage(colorize("&cNon hai il permesso!"));
            return true;
        }

        reloadConfig();
        loadConfigValues();
        sender.sendMessage(colorize("&aPlantBoost configurazione ricaricata!"));

        if (boostActive && bossBar != null) {
            scheduleBossBarUpdates();
        }
        return true;
    }

    private boolean handleStopCommand(CommandSender sender) {
        if (!sender.hasPermission("plantboost.stop")) {
            sender.sendMessage(colorize("&cNon hai il permesso!"));
            return true;
        }

        if (!boostActive) {
            sender.sendMessage(colorize("&eNessun boost attivo!"));
            return true;
        }

        endBoost();
        Bukkit.broadcastMessage(colorize("&eIl boost è stato fermato da " + sender.getName()));
        return true;
    }

    private boolean handleInfoCommand(CommandSender sender) {
        if (!boostActive) {
            sender.sendMessage(colorize("&eNessun boost attivo al momento."));
            return true;
        }

        long remainingMillis = endTimestamp - System.currentTimeMillis();
        String timeFormatted = formatTime(remainingMillis);

        sender.sendMessage(colorize("&a&l=== Boost Attivo ==="));
        sender.sendMessage(colorize("&7Tipo: &e" + currentBoostType));
        sender.sendMessage(colorize("&7Attivato da: &e" + boosterName));
        sender.sendMessage(colorize("&7Tempo rimanente: &e" + timeFormatted));

        return true;
    }

    private boolean handleStatsCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plantboost.stats")) {
            sender.sendMessage(colorize("&cNon hai il permesso!"));
            return true;
        }

        if (args.length < 2) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(colorize("&cSpecifica un giocatore!"));
                return true;
            }
            showPlayerStats(sender, ((Player) sender).getUniqueId());
        } else {
            Player target = getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(colorize("&cGiocatore non trovato!"));
                return true;
            }
            showPlayerStats(sender, target.getUniqueId());
        }
        return true;
    }

    private boolean handleListCommand(CommandSender sender) {
        sender.sendMessage(colorize("&a&l=== Tipi di Boost Disponibili ==="));

        for (Map.Entry<String, BoostType> entry : boostTypes.entrySet()) {
            String key = entry.getKey();
            BoostType type = entry.getValue();

            sender.sendMessage(colorize("&e• " + key + ": &f" + type.displayName +
                    " &7(" + type.durationMinutes + " min, " + type.tickSpeed + "x)"));
        }
        return true;
    }

    private void showPlayerStats(CommandSender sender, UUID playerUUID) {
        BoostStatistics stats = playerStats.get(playerUUID);
        if (stats == null) {
            sender.sendMessage(colorize("&eNessuna statistica trovata per questo giocatore."));
            return;
        }

        sender.sendMessage(colorize("&a&l=== Statistiche Boost ==="));
        sender.sendMessage(colorize("&7Boost usati: &e" + stats.totalBoostsUsed));
        sender.sendMessage(colorize("&7Tempo totale boost: &e" + formatTime(stats.totalBoostTime)));
        sender.sendMessage(colorize("&7Ultimo boost: &e" + (stats.lastBoostType != null ? stats.lastBoostType : "N/A")));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(colorize("&a&l=== PlantBoost Comandi ==="));
        sender.sendMessage(colorize("&e/plantboost give <tipo> [giocatore] [quantità] &7- Dai un boost"));
        sender.sendMessage(colorize("&e/plantboost reload &7- Ricarica config"));
        sender.sendMessage(colorize("&e/plantboost stop &7- Ferma boost attivo"));
        sender.sendMessage(colorize("&e/plantboost info &7- Info boost attivo"));
        sender.sendMessage(colorize("&e/plantboost stats [giocatore] &7- Mostra statistiche"));
        sender.sendMessage(colorize("&e/plantboost list &7- Lista tipi boost"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("plantboost")) return null;

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("give", "reload", "stop", "info", "stats", "list"));
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) {
                completions.addAll(boostTypes.keySet());
            } else if (args[0].equalsIgnoreCase("stats")) {
                return null; // Mostra giocatori online
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return null; // Mostra giocatori online
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            completions.addAll(Arrays.asList("1", "5", "10", "32", "64"));
        }

        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }

    private void giveBoostItem(Player target, String key, BoostType type, int amount) {
        ItemStack item = new ItemStack(type.material, amount);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(colorize(type.displayName));

        List<String> loreList = new ArrayList<>();
        for (String loreLine : type.lore) {
            loreList.add(colorize(loreLine));
        }
        loreList.add("");
        loreList.add(colorize("&aClicca per attivare!"));
        meta.setLore(loreList);

        if (type.glowing) {
            meta.addEnchant(Enchantment.FORTUNE, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }

        NamespacedKey durKey = new NamespacedKey(this, "boostDuration");
        NamespacedKey spdKey = new NamespacedKey(this, "boostSpeed");
        NamespacedKey typeKey = new NamespacedKey(this, "boostType");

        meta.getPersistentDataContainer().set(durKey, PersistentDataType.INTEGER, type.durationMinutes);
        meta.getPersistentDataContainer().set(spdKey, PersistentDataType.INTEGER, type.tickSpeed);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, key);

        item.setItemMeta(meta);

        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            overflow.values().forEach(overflowItem ->
                    target.getWorld().dropItemNaturally(target.getLocation(), overflowItem));
            target.sendMessage(colorize("&eAlcuni oggetti sono stati droppati per mancanza di spazio!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (!e.hasItem()) return;

        ItemMeta meta = e.getItem().getItemMeta();
        if (meta == null) return;

        NamespacedKey durKey = new NamespacedKey(this, "boostDuration");
        if (!meta.getPersistentDataContainer().has(durKey, PersistentDataType.INTEGER)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();

        if (!p.hasPermission("plantboost.use")) {
            p.sendMessage(colorize("&cNon hai il permesso di usare i boost!"));
            return;
        }

        // Check cooldown
        if (cooldowns.containsKey(p.getUniqueId()) && !p.hasPermission("plantboost.cooldown.bypass")) {
            long cooldownEnd = cooldowns.get(p.getUniqueId());
            if (System.currentTimeMillis() < cooldownEnd) {
                long remainingCooldown = cooldownEnd - System.currentTimeMillis();
                p.sendMessage(colorize("&cDevi aspettare ancora " + formatTime(remainingCooldown) + " prima di usare un altro boost!"));
                return;
            }
        }

        // Check world restrictions
        NamespacedKey typeKey = new NamespacedKey(this, "boostType");
        String boostTypeKey = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        BoostType boostType = boostTypes.get(boostTypeKey);

        if (boostType != null && !boostType.allowedWorlds.isEmpty() && !boostType.allowedWorlds.contains(p.getWorld().getName())) {
            p.sendMessage(colorize("&cNon puoi usare questo boost in questo mondo!"));
            return;
        }

        if (boostActive && !allowStack) {
            p.sendMessage(colorize("&cUn boost è già attivo! Attendi che finisca."));
            p.sendMessage(colorize("&eTempo rimanente: " + formatTime(endTimestamp - System.currentTimeMillis())));
            return;
        }

        int minutes = meta.getPersistentDataContainer().get(durKey, PersistentDataType.INTEGER);
        NamespacedKey spdKey = new NamespacedKey(this, "boostSpeed");
        int speed = meta.getPersistentDataContainer().get(spdKey, PersistentDataType.INTEGER);

        if (boostActive && allowStack) {
            long currentRemaining = endTimestamp - System.currentTimeMillis();
            long additionalTime = minutes * 60L * 1000L;
            long newTotal = currentRemaining + additionalTime;

            if (newTotal > maxStackDuration * 60L * 1000L) {
                p.sendMessage(colorize("&cNon puoi estendere il boost oltre " + maxStackDuration + " minuti!"));
                return;
            }

            extendBoost(additionalTime);
            p.sendMessage(colorize("&aBoost esteso di " + minutes + " minuti!"));
        } else {
            startBoost(p.getName(), p.getUniqueId(), minutes, speed, boostTypeKey);
        }

        e.getItem().setAmount(e.getItem().getAmount() - 1);

        // Add cooldown
        if (cooldownMinutes > 0) {
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis() + (cooldownMinutes * 60L * 1000L));
        }

        // Update stats
        updatePlayerStats(p.getUniqueId(), boostTypeKey, minutes);

        // Play sound
        if (soundsEnabled) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (boostActive && bossBar != null) {
            bossBar.addPlayer(e.getPlayer());
            playersWithBossBar.add(e.getPlayer().getUniqueId());

            e.getPlayer().sendMessage(colorize("&aUn boost piante è attualmente attivo!"));
            e.getPlayer().sendMessage(colorize("&eTempo rimanente: " + formatTime(endTimestamp - System.currentTimeMillis())));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (bossBar != null) {
            bossBar.removePlayer(e.getPlayer());
            playersWithBossBar.remove(e.getPlayer().getUniqueId());
        }
    }

    private void startBoost(String playerName, UUID playerUUID, int minutes, int speed, String boostTypeKey) {
        boostActive = true;
        boosterName = playerName;
        boosterUUID = playerUUID;
        currentBoostType = boostTypeKey;
        totalDurationMillis = minutes * 60L * 1000L;
        startTimestamp = System.currentTimeMillis();
        endTimestamp = startTimestamp + totalDurationMillis;

        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "gamerule randomTickSpeed " + speed);

        FileConfiguration cfg = getConfig();
        String title = colorize(cfg.getString("messages.title-start.title", "Boost piante!")
                .replace("%type%", boostTypeKey));
        String subtitle = colorize(cfg.getString("messages.title-start.subtitle", "Attivato da %player%")
                .replace("%player%", playerName)
                .replace("%duration%", String.valueOf(minutes)));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle, 10, 60, 10);
            if (soundsEnabled) {
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.0f);
            }
        }

        // Create boss bar
        bossBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID, BarFlag.CREATE_FOG);
        for (Player p : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(p);
            playersWithBossBar.add(p.getUniqueId());
        }

        scheduleBossBarUpdates();
        scheduleParticles();

        announceTask = Bukkit.getScheduler().runTaskTimer(this, this::announceRemaining,
                announceInterval * 60 * 20L, announceInterval * 60 * 20L);

        endTask = Bukkit.getScheduler().runTaskLater(this, this::endBoost, minutes * 60 * 20L);
    }

    private void extendBoost(long additionalMillis) {
        endTimestamp += additionalMillis;
        totalDurationMillis += additionalMillis;

        if (endTask != null) {
            endTask.cancel();
        }

        long newDurationTicks = (endTimestamp - System.currentTimeMillis()) / 50;
        endTask = Bukkit.getScheduler().runTaskLater(this, this::endBoost, newDurationTicks);

        Bukkit.broadcastMessage(colorize("&eIl boost è stato esteso! Nuovo tempo totale: " +
                formatTime(endTimestamp - System.currentTimeMillis())));
    }

    private void scheduleBossBarUpdates() {
        if (bossBarTask != null) bossBarTask.cancel();
        bossBarTask = Bukkit.getScheduler().runTaskTimer(this, this::updateBossBar, 0L, 20L);
    }

    private void scheduleParticles() {
        if (!particlesEnabled) return;

        if (particleTask != null) particleTask.cancel();
        particleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!boostActive) {
                particleTask.cancel();
                return;
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (Math.random() < 0.3) { // 30% chance per tick
                    Location loc = p.getLocation().add(
                            Math.random() * 10 - 5,
                            Math.random() * 3,
                            Math.random() * 10 - 5
                    );
                    p.spawnParticle(Particle.FLAME, loc, 1);
                }
            }
        }, 0L, 10L);
    }

    private void updateBossBar() {
        if (!boostActive || bossBar == null) {
            if (bossBarTask != null) bossBarTask.cancel();
            return;
        }

        long now = System.currentTimeMillis();
        long remaining = endTimestamp - now;
        if (remaining < 0) remaining = 0;

        double progress = (double) remaining / totalDurationMillis;
        bossBar.setProgress(Math.max(0, Math.min(1, progress)));

        // Change color based on remaining time
        if (progress > 0.5) {
            bossBar.setColor(BarColor.GREEN);
        } else if (progress > 0.25) {
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setColor(BarColor.RED);
        }

        String msg = colorize(getConfig().getString("messages.bossbar", "Boost piante attivo da %player% | Tempo: %time%")
                .replace("%player%", boosterName)
                .replace("%time%", formatTime(remaining))
                .replace("%type%", currentBoostType));
        bossBar.setTitle(msg);
    }

    private void announceRemaining() {
        long remainingMillis = endTimestamp - System.currentTimeMillis();
        if (remainingMillis <= 0) return;

        String msg = colorize(getConfig().getString("messages.announce", "%time% rimanenti di boost!")
                .replace("%time%", formatTime(remainingMillis))
                .replace("%player%", boosterName));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(msg);
            if (soundsEnabled && remainingMillis < 60000) { // Last minute warning
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.0f);
            }
        }
    }

    private void endBoost() {
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "gamerule randomTickSpeed 3");

        String titleEnd = colorize(getConfig().getString("messages.title-end.title", "Boost piante finito!"));
        String subEnd = colorize(getConfig().getString("messages.title-end.subtitle", "Grazie a %player%!")
                .replace("%player%", boosterName));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(titleEnd, subEnd, 10, 60, 10);
            if (soundsEnabled) {
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.0f);
            }
        }

        cleanupBoost();
    }

    private void cleanupBoost() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        if (bossBarTask != null) bossBarTask.cancel();
        if (announceTask != null) announceTask.cancel();
        if (endTask != null) endTask.cancel();
        if (particleTask != null) particleTask.cancel();

        boostActive = false;
        boosterName = null;
        boosterUUID = null;
        currentBoostType = null;
        playersWithBossBar.clear();

        getConfig().set("active-boost", null);
        saveConfig();
    }

    private void saveActiveBoost() {
        if (!boostActive) return;

        FileConfiguration cfg = getConfig();
        cfg.set("active-boost.player-name", boosterName);
        cfg.set("active-boost.player-uuid", boosterUUID.toString());
        cfg.set("active-boost.end-timestamp", endTimestamp);
        cfg.set("active-boost.start-timestamp", startTimestamp);
        cfg.set("active-boost.total-duration", totalDurationMillis);
        cfg.set("active-boost.type", currentBoostType);
        saveConfig();
    }

    private void recoverActiveBoost() {
        FileConfiguration cfg = getConfig();
        long endTime = cfg.getLong("active-boost.end-timestamp");

        if (System.currentTimeMillis() >= endTime) {
            // Boost expired during downtime
            endBoost();
            return;
        }

        boosterName = cfg.getString("active-boost.player-name");
        boosterUUID = UUID.fromString(cfg.getString("active-boost.player-uuid"));
        endTimestamp = endTime;
        startTimestamp = cfg.getLong("active-boost.start-timestamp");
        totalDurationMillis = cfg.getLong("active-boost.total-duration");
        currentBoostType = cfg.getString("active-boost.type");
        boostActive = true;

        // Re-apply boost speed
        BoostType type = boostTypes.get(currentBoostType);
        if (type != null) {
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(),
                    "gamerule randomTickSpeed " + type.tickSpeed);
        }

        // Recreate boss bar
        bossBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID, BarFlag.CREATE_FOG);

        // Schedule tasks
        scheduleBossBarUpdates();
        scheduleParticles();

        long remainingTicks = (endTimestamp - System.currentTimeMillis()) / 50;
        endTask = Bukkit.getScheduler().runTaskLater(this, this::endBoost, remainingTicks);

        long nextAnnounce = announceInterval * 60 * 20L -
                ((System.currentTimeMillis() - startTimestamp) / 50) % (announceInterval * 60 * 20L);
        announceTask = Bukkit.getScheduler().runTaskTimer(this, this::announceRemaining,
                nextAnnounce, announceInterval * 60 * 20L);

        getLogger().info("Boost attivo recuperato! Tempo rimanente: " +
                formatTime(endTimestamp - System.currentTimeMillis()));
    }

    private void updatePlayerStats(UUID playerUUID, String boostType, int duration) {
        BoostStatistics stats = playerStats.computeIfAbsent(playerUUID, k -> new BoostStatistics());
        stats.totalBoostsUsed++;
        stats.totalBoostTime += duration * 60L * 1000L;
        stats.lastBoostType = boostType;
        stats.lastBoostTimestamp = System.currentTimeMillis();
    }

    private void loadStatistics() {
        if (!saveStats) return;
        // Implementa caricamento da file se necessario
    }

    private void saveStatistics() {
        if (!saveStats) return;
        // Implementa salvataggio su file se necessario
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "0 secondi";

        long hours = millis / (1000 * 60 * 60);
        long minutes = (millis % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (millis % (1000 * 60)) / 1000;

        StringBuilder result = new StringBuilder();
        if (hours > 0) result.append(hours).append("h ");
        if (minutes > 0) result.append(minutes).append("m ");
        if (seconds > 0 || result.length() == 0) result.append(seconds).append("s");

        return result.toString().trim();
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static class BoostType {
        public final int durationMinutes;
        public final int tickSpeed;
        public final Material material;
        public final List<String> lore;
        public final String displayName;
        public final boolean glowing;
        public final List<String> allowedWorlds;

        public BoostType(int duration, int speed, Material mat, List<String> lore,
                         String displayName, boolean glowing, List<String> worlds) {
            this.durationMinutes = duration;
            this.tickSpeed = speed;
            this.material = mat;
            this.lore = lore != null ? lore : new ArrayList<>();
            this.displayName = displayName;
            this.glowing = glowing;
            this.allowedWorlds = worlds != null ? worlds : new ArrayList<>();
        }
    }

    public static class BoostStatistics {
        public int totalBoostsUsed = 0;
        public long totalBoostTime = 0;
        public String lastBoostType = null;
        public long lastBoostTimestamp = 0;
    }
}