package uz.score.scorecheck.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import uz.score.scorecheck.ScoreCheck;
import uz.score.scorecheck.logger.CheckLogger;
import uz.score.scorecheck.utils.ColorUtil;
import uz.score.scorecheck.utils.SoundUtil;
import uz.score.scorecheck.utils.TimeUtil;

import java.util.*;

public class CheckManager {

    public enum CloseReason { PASS, BAN, TIMEOUT, DISCONNECT }

    private final ScoreCheck   plugin;
    private final CheckLogger  logger;
    private final ConfigManager cfg;

    // ---- Active data (keyed by checked player UUID) ----
    private final Map<UUID, CheckSession> sessions        = new HashMap<>();
    private final Map<UUID, BossBar>      bossBars        = new HashMap<>();
    private final Map<UUID, ItemStack[]>  savedInventories = new HashMap<>();
    private final Map<UUID, Location>     savedLocations  = new HashMap<>();

    // ---- Tasks ----
    private final Map<UUID, BukkitTask> tickTasks      = new HashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks  = new HashMap<>();
    private final Map<UUID, BukkitTask> animTasks      = new HashMap<>();
    private final Map<UUID, BukkitTask> actionBarTasks = new HashMap<>();

    public CheckManager(ScoreCheck plugin, CheckLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.cfg    = plugin.getConfigManager();
    }

    // =================================================================
    //  START CHECK
    // =================================================================
    public boolean startCheck(Player moderator, Player target) {
        if (isInCheck(target.getUniqueId())) return false;

        int waitTime = cfg.getWaitingTime();
        Location returnLoc = cfg.isReturnLocationEnabled()
                ? cfg.getReturnLocation()
                : target.getLocation().clone(); // return to where they were

        CheckSession session = new CheckSession(
                target.getUniqueId(), moderator.getUniqueId(),
                target.getName(), moderator.getName(),
                waitTime, returnLoc
        );
        sessions.put(target.getUniqueId(), session);

        // Save original location for restoration
        savedLocations.put(target.getUniqueId(), target.getLocation().clone());

        // Save inventory
        savedInventories.put(target.getUniqueId(), target.getInventory().getContents().clone());

        // Teleport to check zone
        target.teleport(cfg.getTeleportLocation());

        // Apply freeze effects
        applyFreezeEffects(target);

        // Vanish from other players
        applyVanish(target);

        // -- Messages to player --
        String timer = TimeUtil.format(waitTime);
        sendTitle(target,
                cfg.getMessage("check-start-title"),
                apply(cfg.getMessage("check-start-subtitle"), target.getName(), moderator.getName(), timer, null, null));
        ColorUtil.sendLines(target,
                apply(cfg.getMessage("check-start-chat"), target.getName(), moderator.getName(), timer, null, null));

        // Sound
        SoundUtil.play(target, "check-start");
        SoundUtil.play(moderator, "check-start");

        // -- Message to moderator --
        ColorUtil.send(moderator,
                apply(cfg.getMessage("check-start-mod"), target.getName(), moderator.getName(), timer, null, null));

        // Boss bar Phase 1
        spawnBossBar(target, session, 1);

        // Action bar task
        startActionBarTask(target, session);

        // Main ticker (1 second)
        BukkitTask tick = new BukkitRunnable() {
            @Override
            public void run() {
                CheckSession s = sessions.get(target.getUniqueId());
                if (s == null) { cancel(); return; }
                if (!s.isPhase(CheckSession.Phase.WAITING)) return;

                s.tickSecond();
                updateBossBarProgress(target, s);

                if (s.isTimedOut()) {
                    cancel();
                    plugin.getServer().getScheduler().runTask(plugin, () -> handleTimeout(target, s));
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20L, 20L);
        tickTasks.put(target.getUniqueId(), tick);

        // Reminder task
        startReminderTask(target, session);

        logger.logStart(target.getName(), moderator.getName());
        return true;
    }

    // =================================================================
    //  CLOSE CHECK
    // =================================================================
    public boolean closeCheck(UUID playerUUID, CloseReason reason, Player closedBy) {
        CheckSession session = sessions.get(playerUUID);
        if (session == null) return false;

        Player target    = Bukkit.getPlayer(playerUUID);
        Player moderator = Bukkit.getPlayer(session.getModeratorUUID());

        // Cancel all tasks
        cancelAllTasks(playerUUID);

        // Remove boss bar
        destroyBossBar(playerUUID, target);

        // Remove from sessions
        sessions.remove(playerUUID);

        // Restore vanish
        restoreVanish(target);

        // Remove freeze effects
        removeFreezeEffects(target);

        switch (reason) {
            case PASS -> {
                if (target != null && target.isOnline()) {
                    restoreInventory(target);
                    // Teleport back
                    Location ret = session.getReturnLocation();
                    if (ret != null) target.teleport(ret);

                    ColorUtil.sendLines(target, cfg.getMessage("check-pass-player"));
                    sendTitle(target, cfg.getMessage("check-pass-title"), cfg.getMessage("check-pass-subtitle"));
                    SoundUtil.play(target, "check-pass");
                }
                if (moderator != null) {
                    ColorUtil.send(moderator,
                            apply(cfg.getMessage("check-pass-mod"), session.getPlayerName(), session.getModeratorName(), null, null, null));
                    SoundUtil.play(moderator, "check-pass");
                }
                logger.logPass(session.getPlayerName(), session.getModeratorName());
            }
            case BAN -> {
                executeBan(session, "manual");
                if (moderator != null) {
                    ColorUtil.send(moderator,
                            apply(cfg.getMessage("check-ban-mod"), session.getPlayerName(), session.getModeratorName(), null, null, null));
                    SoundUtil.play(moderator, "check-ban");
                }
                logger.logBan(session.getPlayerName(), session.getModeratorName(), "Manual ban by moderator");
            }
            case TIMEOUT -> {
                if (target != null && target.isOnline()) {
                    ColorUtil.send(target, cfg.getMessage("timeout-player"));
                }
                executeBan(session, "timeout");
                if (moderator != null) {
                    ColorUtil.send(moderator,
                            apply(cfg.getMessage("timeout-mod"), session.getPlayerName(), session.getModeratorName(), null, null, null));
                }
                logger.logTimeout(session.getPlayerName(), session.getModeratorName());
            }
            case DISCONNECT -> {
                executeBan(session, "disconnect");
                if (moderator != null) {
                    ColorUtil.send(moderator,
                            apply(cfg.getMessage("disconnect-mod"), session.getPlayerName(), session.getModeratorName(), null, null, null));
                }
                logger.logDisconnect(session.getPlayerName(), session.getModeratorName());
            }
        }

        savedInventories.remove(playerUUID);
        savedLocations.remove(playerUUID);
        return true;
    }

    // =================================================================
    //  NEXT PHASE
    // =================================================================
    public boolean nextPhase(UUID playerUUID, Player moderator) {
        CheckSession session = sessions.get(playerUUID);
        if (session == null) return false;
        if (session.isPhase(CheckSession.Phase.CHECKING)) return false; // already phase 2

        // Cancel tick task (timer stops)
        BukkitTask tick = tickTasks.remove(playerUUID);
        if (tick != null) tick.cancel();

        session.setPhase(CheckSession.Phase.CHECKING);

        Player target = Bukkit.getPlayer(playerUUID);

        // Swap boss bar
        destroyBossBar(playerUUID, target);
        if (target != null) {
            spawnBossBar(target, session, 2);

            String timer = TimeUtil.format(session.getRemainingSeconds());
            ColorUtil.sendLines(target,
                    apply(cfg.getMessage("phase2-chat"), session.getPlayerName(), session.getModeratorName(), timer, null, null));
            sendTitle(target,
                    cfg.getMessage("phase2-title"),
                    cfg.getMessage("phase2-subtitle"));
            SoundUtil.play(target, "phase2");
        }

        ColorUtil.send(moderator,
                apply(cfg.getMessage("phase2-mod"), session.getPlayerName(), session.getModeratorName(), null, null, null));
        SoundUtil.play(moderator, "phase2");

        logger.logPhase2(session.getPlayerName(), session.getModeratorName());
        return true;
    }

    // =================================================================
    //  SEND MESSAGE  moderator → player
    // =================================================================
    public boolean sendMessage(Player moderator, UUID playerUUID, String message) {
        CheckSession session = sessions.get(playerUUID);
        if (session == null) return false;
        Player target = Bukkit.getPlayer(playerUUID);
        if (target == null) return false;

        ColorUtil.send(target,
                apply(cfg.getMessage("msg-to-player"), session.getPlayerName(), moderator.getName(), null, message, null));
        return true;
    }

    // =================================================================
    //  RELAY PLAYER CHAT → MODERATOR  (called by listener)
    // =================================================================
    public void relayPlayerMessage(Player player, String message) {
        CheckSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        Player moderator = Bukkit.getPlayer(session.getModeratorUUID());
        if (moderator == null) return;
        ColorUtil.send(moderator,
                apply(cfg.getMessage("msg-to-mod"), player.getName(), session.getModeratorName(), null, message, null));
    }

    // =================================================================
    //  TIME MANIPULATION
    // =================================================================
    public boolean addTime(UUID uid, int seconds, Player moderator) {
        CheckSession s = sessions.get(uid);
        if (s == null || !s.isPhase(CheckSession.Phase.WAITING)) return false;
        s.addSeconds(seconds);
        updateBossBarProgress(Bukkit.getPlayer(uid), s);

        String timer = TimeUtil.format(seconds);
        String newTime = TimeUtil.format(s.getRemainingSeconds());
        ColorUtil.send(moderator,
                applyTimerMsg(cfg.getMessage("time-added"), s.getPlayerName(), timer, newTime));
        return true;
    }

    public boolean removeTime(UUID uid, int seconds, Player moderator) {
        CheckSession s = sessions.get(uid);
        if (s == null || !s.isPhase(CheckSession.Phase.WAITING)) return false;
        s.removeSeconds(seconds);
        updateBossBarProgress(Bukkit.getPlayer(uid), s);

        String timer = TimeUtil.format(seconds);
        String newTime = TimeUtil.format(s.getRemainingSeconds());
        ColorUtil.send(moderator,
                applyTimerMsg(cfg.getMessage("time-removed"), s.getPlayerName(), timer, newTime));
        return true;
    }

    public boolean setTime(UUID uid, int seconds, Player moderator) {
        CheckSession s = sessions.get(uid);
        if (s == null || !s.isPhase(CheckSession.Phase.WAITING)) return false;
        s.setRemainingSeconds(seconds);
        updateBossBarProgress(Bukkit.getPlayer(uid), s);

        String newTime = TimeUtil.format(s.getRemainingSeconds());
        ColorUtil.send(moderator,
                applyTimerMsg(cfg.getMessage("time-set"), s.getPlayerName(), null, newTime));
        return true;
    }

    // =================================================================
    //  FREEZE HELPERS
    // =================================================================
    private void applyFreezeEffects(Player player) {
        if (!cfg.isEffectsEnabled()) return;
        int level = cfg.getSlownessLevel();
        // Integer.MAX_VALUE duration = permanent until removed
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, level, false, false, false));
    }

    private void removeFreezeEffects(Player player) {
        if (player == null || !player.isOnline()) return;
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    public void applyEscapeBlindness(Player player) {
        if (!cfg.isEffectsEnabled() || !cfg.isBlindnessEnabled()) return;
        int dur = cfg.getBlindnessDuration();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, dur, 1, false, false, false));
    }

    // =================================================================
    //  VANISH HELPERS
    // =================================================================
    private void applyVanish(Player target) {
        if (!cfg.isVanishEnabled()) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(target)) continue;
            if (online.hasPermission("scorecheck.seevanished")) continue;
            online.hidePlayer(plugin, target);
        }
    }

    private void restoreVanish(Player target) {
        if (target == null || !target.isOnline()) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(plugin, target);
        }
    }

    /** Called when a new player joins — show them already-vanished checked players or hide checked players from them. */
    public void onPlayerJoin(Player joining) {
        if (!cfg.isVanishEnabled()) return;
        for (UUID uid : sessions.keySet()) {
            Player checked = Bukkit.getPlayer(uid);
            if (checked == null) continue;
            if (!joining.hasPermission("scorecheck.seevanished")) {
                joining.hidePlayer(plugin, checked);
            }
        }
    }

    // =================================================================
    //  BOSS BAR
    // =================================================================
    private void spawnBossBar(Player target, CheckSession session, int phase) {
        BarColor color = cfg.getBossbarColor(phase);
        BarStyle style = cfg.getBossbarStyle(phase);

        BossBar bar = Bukkit.createBossBar("", color, style);
        bar.setProgress(phase == 1 ? session.getProgress() : 1.0);
        bar.addPlayer(target);
        bossBars.put(target.getUniqueId(), bar);

        // Update title immediately
        updateBossBarTitle(bar, session, phase);

        // Start animation
        List<String> frames = cfg.getBossbarAnimation(phase);
        if (!frames.isEmpty()) {
            int interval = cfg.getBossbarAnimInterval(phase);
            int[] idx = {0};
            BukkitTask anim = new BukkitRunnable() {
                @Override
                public void run() {
                    BossBar b = bossBars.get(target.getUniqueId());
                    CheckSession s = sessions.get(target.getUniqueId());
                    if (b == null || s == null) { cancel(); return; }

                    String frame = frames.get(idx[0] % frames.size());
                    frame = applyTimerToTitle(frame, s);
                    b.setTitle(ColorUtil.translateHex(frame));

                    if (phase == 1 && s.isPhase(CheckSession.Phase.WAITING)) {
                        b.setProgress(Math.max(0.0, Math.min(1.0, s.getProgress())));
                    }
                    idx[0]++;
                }
            }.runTaskTimer(plugin, interval, interval);
            BukkitTask old = animTasks.put(target.getUniqueId(), anim);
            if (old != null) old.cancel();
        }
    }

    private void updateBossBarTitle(BossBar bar, CheckSession session, int phase) {
        String title = applyTimerToTitle(cfg.getBossbarTitle(phase), session);
        bar.setTitle(ColorUtil.translateHex(title));
    }

    private String applyTimerToTitle(String template, CheckSession session) {
        return template
                .replace("{timer}", TimeUtil.formatPlain(session.getRemainingSeconds()))
                .replace("{player}", session.getPlayerName())
                .replace("{moderator}", session.getModeratorName());
    }

    private void updateBossBarProgress(Player target, CheckSession session) {
        if (target == null) return;
        BossBar bar = bossBars.get(target.getUniqueId());
        if (bar == null) return;
        if (session.isPhase(CheckSession.Phase.WAITING)) {
            bar.setProgress(Math.max(0.0, Math.min(1.0, session.getProgress())));
        }
    }

    private void destroyBossBar(UUID uid, Player target) {
        BukkitTask anim = animTasks.remove(uid);
        if (anim != null) anim.cancel();
        BossBar bar = bossBars.remove(uid);
        if (bar != null) bar.removeAll();
    }

    // =================================================================
    //  ACTION BAR TASK
    // =================================================================
    private void startActionBarTask(Player target, CheckSession session) {
        if (!cfg.isActionBarEnabled()) return;
        UUID uid = target.getUniqueId();

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uid);
                CheckSession s = sessions.get(uid);
                if (p == null || s == null) { cancel(); return; }

                String template = s.isPhase(CheckSession.Phase.WAITING)
                        ? cfg.getActionBarPhase1()
                        : cfg.getActionBarPhase2();
                String text = template
                        .replace("{moderator}", s.getModeratorName())
                        .replace("{timer}", TimeUtil.formatPlain(s.getRemainingSeconds()));
                p.sendActionBar(legacy(text));
            }
        }.runTaskTimer(plugin, 5L, 20L);

        BukkitTask old = actionBarTasks.put(uid, task);
        if (old != null) old.cancel();
    }

    // =================================================================
    //  REMINDER TASK
    // =================================================================
    private void startReminderTask(Player target, CheckSession session) {
        long interval = cfg.getReminderInterval() * 20L;
        UUID uid = target.getUniqueId();

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uid);
                CheckSession s = sessions.get(uid);
                if (p == null || s == null) { cancel(); return; }

                String phaseInfo;
                if (s.isPhase(CheckSession.Phase.WAITING)) {
                    phaseInfo = cfg.getMessage("reminder-phase1")
                            .replace("{timer}", TimeUtil.format(s.getRemainingSeconds()));
                } else {
                    phaseInfo = cfg.getMessage("reminder-phase2");
                }

                String msg = cfg.getMessage("reminder")
                        .replace("{moderator}", s.getModeratorName())
                        .replace("{phase_info}", phaseInfo);
                ColorUtil.sendLines(p, msg);
                SoundUtil.play(p, "reminder");
            }
        }.runTaskTimer(plugin, interval, interval);

        BukkitTask old = reminderTasks.put(uid, task);
        if (old != null) old.cancel();
    }

    // =================================================================
    //  TIMEOUT
    // =================================================================
    private void handleTimeout(Player target, CheckSession session) {
        closeCheck(session.getPlayerUUID(), CloseReason.TIMEOUT, null);
    }

    // =================================================================
    //  BAN EXECUTION
    // =================================================================
    private void executeBan(CheckSession session, String reason) {
        String cmd = cfg.getBanCommand()
                .replace("{player}", session.getPlayerName())
                .replace("{moderator}", session.getModeratorName());
        // Strip color codes so the console command is clean
        cmd = ColorUtil.stripColors(cmd);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    // =================================================================
    //  INVENTORY RESTORE
    // =================================================================
    private void restoreInventory(Player player) {
        ItemStack[] contents = savedInventories.remove(player.getUniqueId());
        if (contents != null) {
            player.getInventory().setContents(contents);
        }
    }

    // =================================================================
    //  TASK CANCELLATION
    // =================================================================
    private void cancelAllTasks(UUID uid) {
        cancelTask(tickTasks.remove(uid));
        cancelTask(reminderTasks.remove(uid));
        cancelTask(animTasks.remove(uid));
        cancelTask(actionBarTasks.remove(uid));
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    // =================================================================
    //  TITLE HELPER
    // =================================================================
    private void sendTitle(Player player, String title, String subtitle) {
        if (title == null && subtitle == null) return;
        player.showTitle(net.kyori.adventure.title.Title.title(
                legacy(title != null ? title : ""),
                legacy(subtitle != null ? subtitle : ""),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(300),
                        java.time.Duration.ofMillis(2500),
                        java.time.Duration.ofMillis(500)
                )
        ));
    }

    // =================================================================
    //  PLACEHOLDERS HELPERS
    // =================================================================
    private String apply(String template, String player, String moderator, String timer, String message, String phaseInfo) {
        if (template == null) return "";
        return cfg.applyPlaceholders(template, player, moderator, timer, message, phaseInfo, null, null, null, null);
    }

    private String applyTimerMsg(String template, String player, String time, String newTime) {
        if (template == null) return "";
        return cfg.applyPlaceholders(template, player, null, null, null, null, null, null, newTime, time);
    }

    private Component legacy(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LegacyComponentSerializer.legacy('§').deserialize(ColorUtil.translateHex(text));
    }

    // =================================================================
    //  QUERIES
    // =================================================================
    public boolean isInCheck(UUID uid)        { return sessions.containsKey(uid); }
    public CheckSession getSession(UUID uid)   { return sessions.get(uid); }

    public UUID getUUIDByName(String name) {
        for (Map.Entry<UUID, CheckSession> e : sessions.entrySet()) {
            if (e.getValue().getPlayerName().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public Collection<CheckSession> getAllSessions() { return sessions.values(); }

    // =================================================================
    //  SHUTDOWN
    // =================================================================
    public void onShutdown() {
        for (UUID uid : new HashSet<>(sessions.keySet())) {
            cancelAllTasks(uid);
            Player p = Bukkit.getPlayer(uid);
            destroyBossBar(uid, p);
            restoreVanish(p);
            removeFreezeEffects(p);
        }
        sessions.clear();
        bossBars.clear();
        savedInventories.clear();
        savedLocations.clear();
    }
}
