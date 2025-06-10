package com.Lino.plantBoost;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class PlantBoost extends JavaPlugin implements Listener {
    private boolean boostActive = false;
    private String boosterName;
    private BukkitTask announceTask;
    private BukkitTask bossBarTask;
    private BukkitTask endTask;
    private BossBar bossBar;
    private long endTimestamp;
    private long totalDurationMillis;

    private Map<String, BoostType> boostTypes = new HashMap<>();
    private int announceInterval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadConfigValues() {
        FileConfiguration cfg = getConfig();
        announceInterval = cfg.getInt("announce-interval", 5);
        boostTypes.clear();
        for (String key : cfg.getConfigurationSection("boosts").getKeys(false)) {
            int duration = cfg.getInt("boosts." + key + ".duration");
            int speed = cfg.getInt("boosts." + key + ".speed");
            String matName = cfg.getString("boosts." + key + ".material");
            Material mat = null;
            if (matName != null) mat = Material.matchMaterial(matName.toUpperCase());
            if (mat == null) {
                getLogger().warning("Material per boost '" + key + "' non valido o mancante: uso WHEAT_SEEDS");
                mat = Material.WHEAT_SEEDS;
            }
            boostTypes.put(key, new BoostType(duration, speed, mat));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("plantboost")) return false;
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            String key = args[1].toLowerCase();
            BoostType type = boostTypes.get(key);
            if (type == null) {
                sender.sendMessage(Component.text("Tipo boost sconosciuto: " + boostTypes.keySet(), NamedTextColor.RED));
                return true;
            }
            Player target = null;
            if (args.length == 3 && sender.hasPermission("plantboost.give")) {
                target = getServer().getPlayer(args[2]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            if (target == null) {
                sender.sendMessage(Component.text("Giocatore non trovato o specifica un giocatore.", NamedTextColor.RED));
                return true;
            }
            giveBoostItem(target, key, type);
            sender.sendMessage(Component.text("Boost '" + key + "' dato a " + target.getName(), NamedTextColor.YELLOW));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfigValues();
            sender.sendMessage(Component.text("PlantBoost configurazione ricaricata.", NamedTextColor.YELLOW));
            if (boostActive && bossBar != null) {
                // restart bar updates after reload
                scheduleBossBarUpdates();
            }
            return true;
        }
        return false;
    }

    private void giveBoostItem(Player target, String key, BoostType type) {
        ItemStack item = new ItemStack(type.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(key + " boost", NamedTextColor.GREEN));
        NamespacedKey durKey = new NamespacedKey(this, "boostDuration");
        NamespacedKey spdKey = new NamespacedKey(this, "boostSpeed");
        meta.getPersistentDataContainer().set(durKey, PersistentDataType.INTEGER, type.durationMinutes);
        meta.getPersistentDataContainer().set(spdKey, PersistentDataType.INTEGER, type.tickSpeed);
        item.setItemMeta(meta);
        target.getInventory().addItem(item);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (!e.hasItem()) return;
        ItemMeta meta = e.getItem().getItemMeta();
        NamespacedKey durKey = new NamespacedKey(this, "boostDuration");
        NamespacedKey spdKey = new NamespacedKey(this, "boostSpeed");
        if (!meta.getPersistentDataContainer().has(durKey, PersistentDataType.INTEGER)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (boostActive) {
            p.sendMessage(Component.text("Boost già attivo, aspetta che finisca", NamedTextColor.RED));
            return;
        }
        int minutes = meta.getPersistentDataContainer().get(durKey, PersistentDataType.INTEGER);
        int speed = meta.getPersistentDataContainer().get(spdKey, PersistentDataType.INTEGER);
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        startBoost(p.getName(), minutes, speed);
    }

    private void startBoost(String playerName, int minutes, int speed) {
        boostActive = true;
        boosterName = playerName;
        totalDurationMillis = minutes * 60L * 1000L;
        endTimestamp = System.currentTimeMillis() + totalDurationMillis;
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "gamerule randomTickSpeed " + speed);

        FileConfiguration cfg = getConfig();
        String title = cfg.getString("messages.title-start.title", "Boost piante!");
        String subtitle = cfg.getString("messages.title-start.subtitle", "Attivato da %player%")
                .replace("%player%", playerName);
        Title t = Title.title(
                Component.text(title),
                Component.text(subtitle),
                Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))
        );
        Bukkit.getOnlinePlayers().forEach(pl -> pl.showTitle(t));

        // create boss bar once
        bossBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID, BarFlag.PLAY_BOSS_MUSIC);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);

        // schedule frequent updates for bossbar
        scheduleBossBarUpdates();

        // schedule periodic chat announcements
        announceTask = Bukkit.getScheduler().runTaskTimer(this, this::announceRemaining, announceInterval * 60 * 20L, announceInterval * 60 * 20L);

        // schedule end of boost
        endTask = Bukkit.getScheduler().runTaskLater(this, this::endBoost, minutes * 60 * 20L);
    }

    private void scheduleBossBarUpdates() {
        // cancel old task if present
        if (bossBarTask != null) bossBarTask.cancel();
        // update every second (20 ticks)
        bossBarTask = Bukkit.getScheduler().runTaskTimer(this, this::updateBossBar, 0L, 20L);
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
        String msg = getConfig().getString("messages.bossbar", "Boost piante attivo da %player% | Tempo rimasto: %time%")
                .replace("%player%", boosterName)
                .replace("%time%", String.valueOf(remaining / 1000 / 60));
        bossBar.setTitle(msg);
    }

    private void announceRemaining() {
        long remainingMin = Math.max(0, (endTimestamp - System.currentTimeMillis()) / 1000 / 60);
        String msg = getConfig().getString("messages.announce", "%time% minuti di boost rimanenti").replace("%time%", String.valueOf(remainingMin));
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(Component.text(msg, NamedTextColor.YELLOW)));
    }

    private void endBoost() {
        // reset tick speed
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), "gamerule randomTickSpeed 3");
        // show end title
        String titleEnd = getConfig().getString("messages.title-end.title", "Boost piante finito!");
        String subEnd = getConfig().getString("messages.title-end.subtitle", "Sponsorizzato da %player%")
                .replace("%player%", boosterName);
        Title tEnd = Title.title(Component.text(titleEnd), Component.text(subEnd), Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500)));
        Bukkit.getOnlinePlayers().forEach(pl -> pl.showTitle(tEnd));
        // cleanup boss bar and tasks
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        if (bossBarTask != null) bossBarTask.cancel();
        if (announceTask != null) announceTask.cancel();
        boostActive = false;
    }

    public static class BoostType {
        public final int durationMinutes;
        public final int tickSpeed;
        public final Material material;
        public BoostType(int d, int s, Material m) {
            this.durationMinutes = d;
            this.tickSpeed = s;
            this.material = m;
        }
    }
}
