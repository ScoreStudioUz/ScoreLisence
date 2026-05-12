package com.scoretpa.manager;

import com.scoretpa.ScoreTpa;
import com.scoretpa.util.MessageUtil;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeleportManager {

    private final ScoreTpa plugin;
    private final Map<UUID, TeleportTask> activeTeleports = new HashMap<>();

    public TeleportManager(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    public void startTeleport(Player player, Player target) {
        int delay = getDelay(player);
        ConfigManager cfg = plugin.getConfigManager();

        if (delay <= 0) {
            player.teleport(target.getLocation());
            MessageUtil.send(player, cfg.getMessage("teleport-success"));
            return;
        }

        if (activeTeleports.containsKey(player.getUniqueId())) {
            cancelTeleport(player.getUniqueId(), "teleport-cancelled");
        }

        Location startLoc = player.getLocation().clone();
        MessageUtil.send(player, cfg.getMessage("teleporting", "%time%", String.valueOf(delay)));

        BossBar bossBar = null;
        if (cfg.isBossbarEnabled()) {
            BarColor color;
            try {
                color = BarColor.valueOf(cfg.getBossbarColorName());
            } catch (IllegalArgumentException e) {
                color = BarColor.BLUE;
            }
            BarStyle style;
            try {
                style = BarStyle.valueOf(cfg.getBossbarStyleName());
            } catch (IllegalArgumentException e) {
                style = BarStyle.SOLID;
            }
            String title = MessageUtil.color(cfg.getMessage("bossbar-title", "%time%", String.valueOf(delay)));
            bossBar = Bukkit.createBossBar(title, color, style);
            bossBar.setProgress(1.0);
            bossBar.addPlayer(player);
        }

        final BossBar finalBar = bossBar;
        final int totalTicks = delay * 20;
        final UUID playerId = player.getUniqueId();
        final UUID targetId = target.getUniqueId();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                Player p = Bukkit.getPlayer(playerId);
                Player t = Bukkit.getPlayer(targetId);

                if (p == null || t == null) {
                    cancelTeleport(playerId, "teleport-cancelled");
                    return;
                }

                if (finalBar != null) {
                    double progress = 1.0 - ((double) ticks / totalTicks);
                    finalBar.setProgress(Math.max(0, progress));
                    int secondsLeft = (totalTicks - ticks) / 20;
                    finalBar.setTitle(MessageUtil.color(
                            cfg.getMessage("bossbar-title", "%time%", String.valueOf(secondsLeft))));
                }

                if (ticks >= totalTicks) {
                    removeTeleport(playerId);
                    p.teleport(t.getLocation());
                    MessageUtil.send(p, cfg.getMessage("teleport-success"));
                }
            }
        }, 1L, 1L);

        activeTeleports.put(playerId, new TeleportTask(task, finalBar, startLoc));
    }

    public void cancelTeleport(UUID playerId, String messageKey) {
        TeleportTask tp = activeTeleports.remove(playerId);
        if (tp != null) {
            tp.task.cancel();
            if (tp.bossBar != null) {
                tp.bossBar.removeAll();
            }
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                MessageUtil.send(p, plugin.getConfigManager().getMessage(messageKey));
            }
        }
    }

    public boolean isTeleporting(UUID playerId) {
        return activeTeleports.containsKey(playerId);
    }

    public Location getStartLocation(UUID playerId) {
        TeleportTask tp = activeTeleports.get(playerId);
        return tp != null ? tp.startLocation : null;
    }

    public int getTimeLeft(UUID playerId) {
        return 0;
    }

    public void cancelAll() {
        for (UUID id : new HashMap<>(activeTeleports).keySet()) {
            cancelTeleport(id, "teleport-cancelled");
        }
    }

    private void removeTeleport(UUID playerId) {
        TeleportTask tp = activeTeleports.remove(playerId);
        if (tp != null) {
            tp.task.cancel();
            if (tp.bossBar != null) {
                tp.bossBar.removeAll();
            }
        }
    }

    private int getDelay(Player player) {
        Map<String, Integer> delays = plugin.getConfigManager().getGroupDelays();

        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                LuckPerms lp = LuckPermsProvider.get();
                User user = lp.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    String primary = user.getPrimaryGroup();
                    if (delays.containsKey(primary)) {
                        return delays.get(primary);
                    }
                    for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
                        if (delays.containsKey(group.getName())) {
                            return delays.get(group.getName());
                        }
                    }
                }
            } catch (Exception e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("LuckPerms error: " + e.getMessage());
                }
            }
        }

        Integer defaultDelay = delays.get("default");
        return defaultDelay != null ? defaultDelay : 7;
    }

    private static class TeleportTask {
        final BukkitTask task;
        final BossBar bossBar;
        final Location startLocation;

        TeleportTask(BukkitTask task, BossBar bossBar, Location startLocation) {
            this.task = task;
            this.bossBar = bossBar;
            this.startLocation = startLocation;
        }
    }
}
