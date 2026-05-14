package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Friendship;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class FriendsManager {

    private final ScorePvP plugin;

    /** uuid → list of their friendships (accepted + pending incoming) */
    private final Map<UUID, List<Friendship>> cache = new ConcurrentHashMap<>();

    public FriendsManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Load / Unload ─────────────────────────────────────────────────────────

    public void loadAsync(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Friendship> friends = plugin.getDatabase().get().loadFriends(uuid);
            cache.put(uuid, new ArrayList<>(friends));
        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean areFriends(UUID a, UUID b) {
        List<Friendship> list = cache.get(a);
        if (list == null) return false;
        return list.stream()
                .anyMatch(f -> f.isAccepted() && f.involves(b));
    }

    public List<UUID> getFriends(UUID uuid) {
        List<Friendship> list = cache.getOrDefault(uuid, List.of());
        return list.stream()
                .filter(Friendship::isAccepted)
                .map(f -> f.otherThan(uuid))
                .collect(Collectors.toList());
    }

    public int getFriendCount(UUID uuid) {
        return getFriends(uuid).size();
    }

    /** Returns pending INCOMING requests for uuid (others sent to uuid). */
    public List<Friendship> getPendingRequests(UUID uuid) {
        return cache.getOrDefault(uuid, List.of()).stream()
                .filter(f -> f.isPending() && f.getReceiver().equals(uuid))
                .collect(Collectors.toList());
    }

    private Optional<Friendship> findPendingFrom(UUID sender, UUID receiver) {
        return cache.getOrDefault(receiver, List.of()).stream()
                .filter(f -> f.isPending()
                        && f.getSender().equals(sender)
                        && f.getReceiver().equals(receiver))
                .findFirst();
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    public void sendRequest(Player sender, Player target) {
        var cfg = plugin.getConfigManager();
        int max = plugin.getConfig().getInt("friends.max-friends", 100);

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(cfg.msg("friends.cannot-add-self")); return;
        }
        if (areFriends(sender.getUniqueId(), target.getUniqueId())) {
            sender.sendMessage(cfg.msg("friends.already-friends", "{player}", target.getName())); return;
        }
        if (getFriendCount(sender.getUniqueId()) >= max) {
            sender.sendMessage(cfg.msg("friends.list-full")); return;
        }
        // Check target's Friend Requests setting
        var ts = plugin.getSettingsManager().get(target.getUniqueId());
        if (!ts.isFriendRequests()) {
            sender.sendMessage(cfg.msg("friends.requests-disabled", "{player}", target.getName())); return;
        }

        if (findPendingFrom(sender.getUniqueId(), target.getUniqueId()).isPresent()) {
            sender.sendMessage(cfg.msg("friends.already-sent", "{player}", target.getName())); return;
        }

        Friendship f = new Friendship(sender.getUniqueId(), target.getUniqueId());
        cache.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>()).add(f);
        cache.computeIfAbsent(sender.getUniqueId(), k -> new ArrayList<>()).add(f);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabase().get().saveFriendship(f));

        sender.sendMessage(cfg.msg("friends.request-sent", "{player}", target.getName()));

        // Clickable message to target
        int expireSec = plugin.getConfig().getInt("friends.request-expire-seconds", 120);
        Component msg = buildRequestMessage(sender.getName(), expireSec);
        target.sendMessage(msg);

        // Auto expire
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (f.isPending()) {
                removeFriendship(sender.getUniqueId(), target.getUniqueId());
            }
        }, expireSec * 20L);
    }

    public void acceptRequest(Player acceptor, String senderName) {
        var cfg = plugin.getConfigManager();
        Player sender = Bukkit.getPlayerExact(senderName);
        UUID senderUuid = sender != null ? sender.getUniqueId()
                : findUuidByName(senderName);
        if (senderUuid == null) {
            acceptor.sendMessage(cfg.msg("player-not-found", "{player}", senderName)); return;
        }

        Optional<Friendship> opt = findPendingFrom(senderUuid, acceptor.getUniqueId());
        if (opt.isEmpty()) {
            acceptor.sendMessage(cfg.msg("friends.no-request", "{player}", senderName)); return;
        }
        Friendship f = opt.get();
        f.accept();
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabase().get().saveFriendship(f));

        acceptor.sendMessage(cfg.msg("friends.accepted", "{player}", senderName));
        if (sender != null) sender.sendMessage(cfg.msg("friends.request-accepted", "{player}", acceptor.getName()));
    }

    public void denyRequest(Player denier, String senderName) {
        var cfg = plugin.getConfigManager();
        UUID senderUuid = findUuidByName(senderName);
        Player sender   = Bukkit.getPlayerExact(senderName);
        if (senderUuid == null && sender == null) {
            denier.sendMessage(cfg.msg("player-not-found", "{player}", senderName)); return;
        }
        UUID uuid = senderUuid != null ? senderUuid : sender.getUniqueId();
        removeFriendship(uuid, denier.getUniqueId());
        denier.sendMessage(cfg.msg("friends.denied", "{player}", senderName));
        if (sender != null) sender.sendMessage(cfg.msg("friends.request-denied", "{player}", denier.getName()));
    }

    public void removeFriend(Player remover, String targetName) {
        var cfg = plugin.getConfigManager();
        UUID targetUuid = findUuidByName(targetName);
        Player target = Bukkit.getPlayerExact(targetName);
        UUID uuid = target != null ? target.getUniqueId() : targetUuid;
        if (uuid == null) { remover.sendMessage(cfg.msg("player-not-found", "{player}", targetName)); return; }

        if (!areFriends(remover.getUniqueId(), uuid)) {
            remover.sendMessage(cfg.msg("friends.not-friends", "{player}", targetName)); return;
        }
        removeFriendship(remover.getUniqueId(), uuid);
        remover.sendMessage(cfg.msg("friends.removed", "{player}", targetName));
        if (target != null) target.sendMessage(cfg.msg("friends.was-removed", "{player}", remover.getName()));
    }

    public void showList(Player player) {
        var cfg = plugin.getConfigManager();
        List<UUID> friends = getFriends(player.getUniqueId());
        if (friends.isEmpty()) {
            player.sendMessage(cfg.msg("friends.no-friends"));
            return;
        }
        player.sendMessage(cfg.msg("friends.list-header", "{count}", String.valueOf(friends.size())));
        friends.forEach(uuid -> {
            Player online = Bukkit.getPlayer(uuid);
            String name   = online != null ? online.getName() : uuid.toString().substring(0, 8) + "...";
            String status = online != null ? cfg.rawStr("friends.status-online") : cfg.rawStr("friends.status-offline");
            player.sendMessage(com.scorepvp.util.ColorUtil.color("  " + status + " &f" + name));
        });
    }

    public void showRequests(Player player) {
        List<Friendship> reqs = getPendingRequests(player.getUniqueId());
        if (reqs.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().msg("friends.no-incoming"));
            return;
        }
        player.sendMessage(plugin.getConfigManager().msg("friends.incoming-header"));
        reqs.forEach(f -> {
            Player sender = Bukkit.getPlayer(f.getSender());
            String name   = sender != null ? sender.getName() : f.getSender().toString().substring(0,8);
            player.sendMessage(buildRequestMessage(name, 0));
        });
    }

    // ── Join notification ──────────────────────────────────────────────────────

    public void notifyFriendsOnJoin(Player joiner) {
        boolean notify = plugin.getConfig().getBoolean("friends.notify-on-join", true);
        if (!notify) return;
        String fmt = plugin.getConfigManager().rawMsg("friends.join-notify")
                .replace("{player}", joiner.getName());
        getFriends(joiner.getUniqueId()).forEach(uuid -> {
            Player friend = Bukkit.getPlayer(uuid);
            if (friend == null) return;
            var settings = plugin.getSettingsManager().get(uuid);
            if (settings.isFriendJoinNotifier()) friend.sendMessage(fmt);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void removeFriendship(UUID a, UUID b) {
        for (UUID uuid : List.of(a, b)) {
            List<Friendship> list = cache.get(uuid);
            if (list != null) list.removeIf(f -> f.involves(a) && f.involves(b));
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.getDatabase().get().deleteFriendship(a, b));
    }

    private UUID findUuidByName(String name) {
        return cache.entrySet().stream()
                .filter(e -> {
                    Player p = Bukkit.getPlayer(e.getKey());
                    return p != null && p.getName().equalsIgnoreCase(name);
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> {
                    var op = Bukkit.getOfflinePlayerIfCached(name);
                    return op != null ? op.getUniqueId() : null;
                });
    }

    private Component buildRequestMessage(String senderName, int expireSec) {
        var cfg = plugin.getConfigManager();
        String acceptColor = plugin.getConfig().getString("friends.button-accept-color", "&a");
        String denyColor   = plugin.getConfig().getString("friends.button-deny-color",   "&c");

        Component header = comp(cfg.prefix() + cfg.rawMsg("friends.request-header", "{player}", senderName));
        Component acceptBtn = comp(cfg.rawStr("friends.accept-btn"))
                .clickEvent(ClickEvent.runCommand("/friends accept " + senderName))
                .hoverEvent(HoverEvent.showText(comp(cfg.rawStr("friends.accept-hover"))));
        Component denyBtn = comp(cfg.rawStr("friends.deny-btn"))
                .clickEvent(ClickEvent.runCommand("/friends deny " + senderName))
                .hoverEvent(HoverEvent.showText(comp(cfg.rawStr("friends.deny-hover"))));

        return header.appendNewline().append(acceptBtn).append(denyBtn);
    }

    private Component comp(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
