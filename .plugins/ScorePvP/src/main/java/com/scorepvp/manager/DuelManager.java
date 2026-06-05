package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.DuelRequest;
import com.scorepvp.model.PvPMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DuelManager {

    private final ScorePvP plugin;

    private final Map<UUID, DuelRequest> pending = new ConcurrentHashMap<>();
    private final Map<String, Queue<UUID>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, String> inQueue = new ConcurrentHashMap<>();
    // Players currently inside a startDuelWithTimeout retry loop (arena search).
    // Prevents a player from being committed to a second match in another mode
    // while still waiting for a free arena — the root of the duplicate-match /
    // repeated "opponent left" spam bug.
    private final Set<UUID> pendingMatch = ConcurrentHashMap.newKeySet();

    public DuelManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Duel Request ──────────────────────────────────────────────────────────

    public void sendDuel(Player sender, Player target, String modeName, int rounds) {
        ConfigManager cfg = plugin.getConfigManager();

        // FIX (sections 3+8): block duel while in match or party
        if (isBusy(sender.getUniqueId())) {
            sender.sendMessage(cfg.msg("duel.you-in-match"));
            return;
        }
        if (isBusy(target.getUniqueId())) {
            sender.sendMessage(cfg.msg("duel.target-in-match", "{player}", target.getName()));
            return;
        }
        // Section 8: block duel to player in party
        if (plugin.getPartyManager().isInParty(target.getUniqueId())) {
            sender.sendMessage(cfg.msg("duel.target-in-party", "{player}", target.getName()));
            return;
        }

        if (inQueue.containsKey(sender.getUniqueId())) {
            sender.sendMessage(cfg.msg("queue.already-in"));
            return;
        }
        if (inQueue.containsKey(target.getUniqueId())) {
            sender.sendMessage(cfg.msg("duel.target-in-queue", "{player}", target.getName()));
            return;
        }

        int expireSec = 60;

        var targetSettings = plugin.getSettingsManager().get(target.getUniqueId());
        if (targetSettings.getDuelRequests() == com.scorepvp.model.PlayerSettings.Visibility.ONLY_FRIENDS) {
            if (!plugin.getFriendsManager().areFriends(sender.getUniqueId(), target.getUniqueId())) {
                sender.sendMessage(cfg.msg("duel.only-friends", "{player}", target.getName()));
                return;
            }
        }

        PvPMode modeForCheck = plugin.getConfigManager().getMode(modeName);
        if (modeForCheck != null) {
            var cvm = plugin.getClientVersionManager();
            if (!cvm.canPlay(sender, modeForCheck)) {
                sender.sendMessage(plugin.getConfigManager().msg("messages.version-too-low",
                        "{mode}", modeForCheck.getDisplayName(),
                        "{min_version}", modeForCheck.getMinClientVersion(),
                        "{player_version}", cvm.getClientVersion(sender)));
                return;
            }
            if (!cvm.canPlay(target, modeForCheck)) {
                String tv = cvm.getClientVersion(target);
                sender.sendMessage(plugin.getConfigManager().msg("messages.version-too-low-duel",
                        "{mode}", modeForCheck.getDisplayName(),
                        "{player}", target.getName(), "{version}", tv));
                return;
            }
        }

        if (pending.containsKey(target.getUniqueId())) {
            sender.sendMessage(cfg.msg("duel.already-pending", "{player}", target.getName()));
            return;
        }

        DuelRequest req = new DuelRequest(sender.getUniqueId(), target.getUniqueId(), modeName, rounds);
        pending.put(target.getUniqueId(), req);

        PvPMode mode = cfg.getMode(modeName);
        String modeDisplay = mode != null ? mode.getDisplayName() : modeName;

        // Send multi-line duel sent block to sender
        cfg.sendBlockMessage(sender, "duel-sent-block",
                "{player}", target.getName(),
                "{mode}", modeDisplay,
                "{rounds}", String.valueOf(rounds));

        // Send multi-line duel invite
        java.util.List<String> inviteLines = cfg.msgLines("duel-invite",
                "{player}", sender.getName(),
                "{mode}", modeDisplay,
                "{rounds}", String.valueOf(rounds));
        for (String line : inviteLines) {
            target.sendMessage(line);
        }

        // Clickable accept/decline buttons
        String acceptText = cfg.rawMsg("duel-invite-accept");
        String declineText = cfg.rawMsg("duel-invite-decline");
        String acceptHover = cfg.rawStr("duel-invite-accept-hover");
        String declineHover = cfg.rawStr("duel-invite-decline-hover");

        Component acceptBtn = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(acceptText)
                .clickEvent(ClickEvent.runCommand("/duel accept " + sender.getName()));
        if (!acceptHover.isEmpty()) {
            acceptBtn = acceptBtn.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(acceptHover)));
        }

        Component declineBtn = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(declineText)
                .clickEvent(ClickEvent.runCommand("/duel decline " + sender.getName()));
        if (!declineHover.isEmpty()) {
            declineBtn = declineBtn.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(declineHover)));
        }

        target.sendMessage(acceptBtn.append(Component.text(" ")).append(declineBtn));

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                pending.remove(target.getUniqueId(), req), expireSec * 20L);
    }

    public boolean acceptDuel(Player acceptor) {
        // FIX (section 3): block accept while in active match
        if (isBusy(acceptor.getUniqueId())) {
            acceptor.sendMessage(plugin.getConfigManager().msg("duel.you-in-match"));
            return false;
        }
        if (inQueue.containsKey(acceptor.getUniqueId())) {
            acceptor.sendMessage(plugin.getConfigManager().msg("queue.already-in"));
            return false;
        }
        DuelRequest req = pending.remove(acceptor.getUniqueId());
        if (req == null || req.isExpired(60)) {
            acceptor.sendMessage(plugin.getConfigManager().msg("duel.no-request"));
            return false;
        }
        Player sender = Bukkit.getPlayer(req.getSender());
        if (sender == null) {
            acceptor.sendMessage(plugin.getConfigManager().msg("duel.sender-offline"));
            return false;
        }
        // Section 8: cancel all other pending duel requests targeting the same acceptor
        pending.entrySet().removeIf(e -> e.getValue().getTarget().equals(acceptor.getUniqueId()));
        startDuel(sender, acceptor, req.getModeName(), req.getRounds());
        return true;
    }

    public void declineDuel(Player player) {
        DuelRequest req = pending.remove(player.getUniqueId());
        if (req == null) {
            player.sendMessage(plugin.getConfigManager().msg("duel.no-request"));
            return;
        }
        plugin.getConfigManager().sendBlockMessage(player, "duel-declined-block");
        Player sender = Bukkit.getPlayer(req.getSender());
        if (sender != null && sender.isOnline()) {
            plugin.getConfigManager().sendBlockMessage(sender, "duel-declined-notify-block",
                    "{player}", player.getName());
        }
    }

    public boolean acceptDuelFrom(Player acceptor, String senderName) {
        // FIX (section 3): block accept while in active match
        if (isBusy(acceptor.getUniqueId())) {
            acceptor.sendMessage(plugin.getConfigManager().msg("duel.you-in-match"));
            return false;
        }
        if (inQueue.containsKey(acceptor.getUniqueId())) {
            acceptor.sendMessage(plugin.getConfigManager().msg("queue.already-in"));
            return false;
        }
        for (DuelRequest req : pending.values()) {
            if (!req.getTarget().equals(acceptor.getUniqueId())) continue;
            Player sender = Bukkit.getPlayer(req.getSender());
            if (sender == null || !sender.getName().equalsIgnoreCase(senderName)) continue;
            pending.remove(acceptor.getUniqueId());
            if (req.isExpired(60)) {
                acceptor.sendMessage(plugin.getConfigManager().msg("duel.expired"));
                return false;
            }
            // Section 8: cancel all other pending duel requests for acceptor
            pending.entrySet().removeIf(e -> e.getValue().getTarget().equals(acceptor.getUniqueId()));
            startDuel(sender, acceptor, req.getModeName(), req.getRounds());
            return true;
        }
        acceptor.sendMessage(plugin.getConfigManager().msg("duel.no-request"));
        return false;
    }

    private void startDuel(Player p1, Player p2, String modeName, int rounds) {
        PvPMode mode = plugin.getConfigManager().getMode(modeName);
        String modeDisplay = mode != null ? mode.getDisplayName() : modeName;
        // Многострочное сообщение о принятии дуэли обоим игрокам
        plugin.getConfigManager().sendBlockMessage(p1, "duel-accepted-block",
                "{player}", p2.getName(), "{mode}", modeDisplay, "{rounds}", String.valueOf(rounds));
        plugin.getConfigManager().sendBlockMessage(p2, "duel-accepted-block",
                "{player}", p1.getName(), "{mode}", modeDisplay, "{rounds}", String.valueOf(rounds));
        startDuelWithTimeout(p1, p2, modeName, rounds, 0);
    }

    private void startDuelWithTimeout(Player p1, Player p2, String modeName, int rounds, int attemptNo) {
        UUID u1 = p1.getUniqueId(), u2 = p2.getUniqueId();

        // On the first attempt, commit both players to THIS pending match so they
        // can't be pulled into another mode's match while we search for an arena.
        if (attemptNo == 0) {
            pendingMatch.add(u1);
            pendingMatch.add(u2);
        }

        int retryTicks  = plugin.getConfig().getInt("arena.retry-search-ticks", 100);
        int maxAttempts = plugin.getConfig().getInt("arena.search-timeout-seconds", 60)
                / Math.max(1, retryTicks / 20);

        // Abort the whole search if either player went offline or got committed to
        // an actual match in the meantime — otherwise we'd create a duplicate.
        if (p1 == null || !p1.isOnline() || p2 == null || !p2.isOnline()
                || plugin.getMatchManager().isInMatch(u1)
                || plugin.getMatchManager().isInMatch(u2)) {
            clearPending(u1, u2);
            return;
        }

        if (attemptNo >= maxAttempts) {
            String msg = plugin.getConfigManager().msg("duel.arena-not-found-timeout");
            if (p1.isOnline()) p1.sendMessage(msg);
            if (p2.isOnline()) p2.sendMessage(msg);
            clearPending(u1, u2);
            return;
        }

        PvPMode mode = plugin.getConfigManager().getMode(modeName);
        if (mode == null) {
            if (p1.isOnline())
                p1.sendMessage(plugin.getConfigManager().msg("duel.mode-not-found", "{mode}", modeName));
            clearPending(u1, u2);
            return;
        }

        var opt = plugin.getArenaManager().findFreeArena(mode);
        if (opt.isEmpty()) {
            if (attemptNo == 0) {
                if (p1.isOnline())
                    plugin.getConfigManager().sendBlockMessage(p1, "duel-no-arena-block", "{mode}", mode.getDisplayName());
                if (p2.isOnline())
                    plugin.getConfigManager().sendBlockMessage(p2, "duel-no-arena-block", "{mode}", mode.getDisplayName());
            }
            int next = attemptNo + 1;
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> startDuelWithTimeout(p1, p2, modeName, rounds, next), retryTicks);
            return;
        }

        clearPending(u1, u2);
        // createMatch returns null if either player is somehow already in byPlayer
        // (the duplicate-match guard) or the arena couldn't host the match. Either
        // way, tell both players instead of silently dropping the duel.
        if (plugin.getMatchManager().createMatch(opt.get(), mode, rounds, p1, p2) == null) {
            String msg = plugin.getConfigManager().msg("duel.arena-not-found-timeout");
            if (p1.isOnline()) p1.sendMessage(msg);
            if (p2.isOnline()) p2.sendMessage(msg);
        }
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    public void joinQueue(Player player, String modeName) {
        if (inQueue.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("queue.already-in"));
            return;
        }
        PvPMode qMode = plugin.getConfigManager().getMode(modeName);
        if (qMode != null && !plugin.getClientVersionManager().canPlay(player, qMode)) {
            String pv = plugin.getClientVersionManager().getClientVersion(player);
            player.sendMessage(plugin.getConfigManager().msg("messages.version-too-low",
                    "{mode}", qMode.getDisplayName(),
                    "{min_version}", qMode.getMinClientVersion(),
                    "{player_version}", pv));
            return;
        }
        if (isBusy(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("queue.in-match"));
            return;
        }

        queues.computeIfAbsent(modeName, k -> new LinkedList<>()).add(player.getUniqueId());
        inQueue.put(player.getUniqueId(), modeName);
        PvPMode queueMode = plugin.getConfigManager().getMode(modeName);
        String queueDisplay = queueMode != null ? queueMode.getDisplayName() : modeName;
        plugin.getConfigManager().sendBlockMessage(player, "queue-joined-block", "{mode}", queueDisplay);

        PvPMode mode = plugin.getConfigManager().getMode(modeName);
        String displayName = mode != null ? mode.getDisplayName() : modeName;
        plugin.getHotbarManager().showQueueMode(player, displayName);

        tryMatchFromQueue(modeName);
    }

    public void leaveQueue(Player player) {
        String mode = inQueue.remove(player.getUniqueId());
        if (mode == null) return;
        Queue<UUID> q = queues.get(mode);
        if (q != null) q.remove(player.getUniqueId());
        plugin.getHotbarManager().showNormalMode(player);
        player.sendMessage(plugin.getConfigManager().msg("queue.left", "{mode}", mode));
    }

    private void tryMatchFromQueue(String modeName) {
        Queue<UUID> q = queues.get(modeName);
        if (q == null || q.size() < 2) return;

        UUID uuid1 = q.poll();
        UUID uuid2 = q.poll();
        inQueue.remove(uuid1);
        inQueue.remove(uuid2);

        Player p1 = Bukkit.getPlayer(uuid1);
        Player p2 = Bukkit.getPlayer(uuid2);

        if (p1 == null || !p1.isOnline()) { if (p2 != null && p2.isOnline()) joinQueue(p2, modeName); return; }
        if (p2 == null || !p2.isOnline()) { joinQueue(p1, modeName); return; }

        plugin.getHotbarManager().showNormalMode(p1);
        plugin.getHotbarManager().showNormalMode(p2);

        int rounds = 3;
        startDuelWithTimeout(p1, p2, modeName, rounds, 0);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isInQueue(UUID uuid)    { return inQueue.containsKey(uuid); }
    public String  getQueueMode(UUID uuid) { return inQueue.get(uuid); }

    public void handleDisconnect(UUID uuid) {
        String mode = inQueue.remove(uuid);
        if (mode != null) {
            Queue<UUID> q = queues.get(mode);
            if (q != null) q.remove(uuid);
        }
        pending.entrySet().removeIf(e ->
                e.getValue().getSender().equals(uuid) || e.getValue().getTarget().equals(uuid));
        pendingMatch.remove(uuid);
    }

    private void clearPending(UUID a, UUID b) {
        pendingMatch.remove(a);
        pendingMatch.remove(b);
    }

    /**
     * True if the player is busy in a way that should block a new duel/queue:
     * already in a match OR in the middle of an arena-search for a pending match.
     */
    public boolean isBusy(UUID uuid) {
        return plugin.getMatchManager().isInMatch(uuid) || pendingMatch.contains(uuid);
    }
}
