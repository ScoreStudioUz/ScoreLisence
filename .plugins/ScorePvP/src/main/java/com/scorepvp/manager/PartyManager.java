package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.Party;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

public class PartyManager {

    private final ScorePvP plugin;

    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public PartyManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isInParty(UUID uuid)      { return parties.containsKey(uuid); }
    public Party getParty(UUID uuid)         { return parties.get(uuid); }
    public boolean isLeader(UUID uuid) {
        Party p = getParty(uuid);
        return p != null && p.isLeader(uuid);
    }

    // ── Party size cap (permission tiers) ──────────────────────────────────────

    /**
     * The maximum party size (members incl. the leader) for a leader, based on
     * their highest {@code scorepvp.party.tierN} permission. Falls back to
     * {@code party.max-size.default-max} when no tier is held.
     */
    public int maxSizeFor(Player leader) {
        var cfg = plugin.getConfig();
        int def = cfg.getInt("party.max-size.default-max", 4);
        if (leader.hasPermission("scorepvp.party.tier3")) return cfg.getInt("party.max-size.tier3", 10);
        if (leader.hasPermission("scorepvp.party.tier2")) return cfg.getInt("party.max-size.tier2", 6);
        if (leader.hasPermission("scorepvp.party.tier1")) return cfg.getInt("party.max-size.tier1", 4);
        return def;
    }

    /** Convenience overload resolving the leader by their online player, else default. */
    public int maxSizeFor(UUID leaderUuid) {
        Player leader = Bukkit.getPlayer(leaderUuid);
        if (leader != null) return maxSizeFor(leader);
        return plugin.getConfig().getInt("party.max-size.default-max", 4);
    }

    /** Number of outstanding invites sent by this leader (pendingInvites: target → leader). */
    private int pendingInvitesTo(UUID leaderUuid) {
        int n = 0;
        for (UUID inviter : pendingInvites.values()) if (leaderUuid.equals(inviter)) n++;
        return n;
    }

    // ── Active match guard ────────────────────────────────────────────────────

    /** Returns true if the player is currently in an active FFA match. */
    private boolean isInActiveMatch(UUID uuid) {
        Match m = plugin.getMatchManager().getMatch(uuid);
        return m != null && m.getState() == Match.State.ACTIVE;
    }

    // ── Create Party ──────────────────────────────────────────────────────────

    public void createParty(Player leader) {
        ConfigManager cfg = plugin.getConfigManager();
        if (isInParty(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.already-in-party"));
            return;
        }
        // FIX: block party creation during match
        if (plugin.getMatchManager().isInMatch(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.cannot-create-in-match"));
            return;
        }

        Party party = new Party(leader.getUniqueId());
        parties.put(leader.getUniqueId(), party);

        leader.sendMessage(cfg.msg("party.created"));

        leader.getInventory().clear();
        leader.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        leader.getInventory().setItemInOffHand(null);
        plugin.getHotbarManager().givePartyLeaderHotbar(leader);
        leader.updateInventory();
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    public void invite(Player leader, Player target) {
        ConfigManager cfg = plugin.getConfigManager();

        // FIX (section 3): block invite while in active match
        if (plugin.getMatchManager().isInMatch(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.cannot-invite-in-match"));
            return;
        }
        if (plugin.getMatchManager().isInMatch(target.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.target-in-match", "{player}", target.getName()));
            return;
        }

        if (isInParty(leader.getUniqueId())) {
            Party party = getParty(leader.getUniqueId());
            if (!party.isLeader(leader.getUniqueId())) {
                leader.sendMessage(cfg.msg("party.not-leader"));
                return;
            }
        } else {
            createParty(leader);
        }

        if (isInParty(target.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.target-in-party", "{player}", target.getName()));
            return;
        }
        // Party size cap (leader's permission tier). Count current members plus
        // outstanding invites so a leader can't blow past the cap by inviting
        // many players at once before any of them accept.
        int max = maxSizeFor(leader);
        Party lp = getParty(leader.getUniqueId());
        int projected = (lp != null ? lp.size() : 1) + pendingInvitesTo(leader.getUniqueId());
        if (projected >= max) {
            leader.sendMessage(cfg.msg("party.full", "{max}", String.valueOf(max)));
            return;
        }
        if (pendingInvites.containsKey(target.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.already-invited", "{player}", target.getName()));
            return;
        }

        var ts = plugin.getSettingsManager().get(target.getUniqueId());
        if (ts.getPartyInvites() == com.scorepvp.model.PlayerSettings.Visibility.ONLY_FRIENDS) {
            if (!plugin.getFriendsManager().areFriends(leader.getUniqueId(), target.getUniqueId())) {
                leader.sendMessage(cfg.msg("party.only-friends-invite", "{player}", target.getName()));
                return;
            }
        }

        pendingInvites.put(target.getUniqueId(), leader.getUniqueId());
        leader.sendMessage(cfg.msg("party.invite-sent", "{player}", target.getName()));

        net.kyori.adventure.text.Component msg = buildInviteMessage(leader.getName());
        target.sendMessage(msg);

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                pendingInvites.remove(target.getUniqueId(), leader.getUniqueId()), 1200L);
    }

    // ── Accept ────────────────────────────────────────────────────────────────

    public void accept(Player joiner, String leaderName) {
        ConfigManager cfg = plugin.getConfigManager();

        // FIX (section 3): block accept while in active match
        if (plugin.getMatchManager().isInMatch(joiner.getUniqueId())) {
            joiner.sendMessage(cfg.msg("party.cannot-accept-in-match"));
            return;
        }

        Player leader = Bukkit.getPlayerExact(leaderName);
        if (leader == null) {
            joiner.sendMessage(cfg.msg("player-not-found", "{player}", leaderName));
            return;
        }

        UUID leaderUUID = leader.getUniqueId();
        if (!leaderUUID.equals(pendingInvites.get(joiner.getUniqueId()))) {
            joiner.sendMessage(cfg.msg("party.no-invite", "{player}", leaderName));
            return;
        }

        pendingInvites.remove(joiner.getUniqueId());

        // The inviter must still be the leader of a live party. If they
        // disbanded (or someone else now leads), the invite is stale — refuse
        // instead of silently spawning a brand-new party around them.
        Party party = getParty(leaderUUID);
        if (party == null) {
            if (!isInParty(leaderUUID)) {
                Party fresh = new Party(leaderUUID);
                parties.put(leaderUUID, fresh);
                if (leader.isOnline()) plugin.getHotbarManager().givePartyLeaderHotbar(leader);
                party = fresh;
            } else {
                party = getParty(leaderUUID);
            }
        }
        if (party == null || !party.isLeader(leaderUUID)) {
            joiner.sendMessage(cfg.msg("party.no-invite", "{player}", leaderName));
            return;
        }

        // Re-check the cap at accept time — the party may have filled up while
        // this invite was outstanding.
        int max = maxSizeFor(leaderUUID);
        if (party.size() >= max) {
            joiner.sendMessage(cfg.msg("party.full", "{max}", String.valueOf(max)));
            return;
        }

        party.addMember(joiner.getUniqueId());
        parties.put(joiner.getUniqueId(), party);

        joiner.sendMessage(cfg.msg("party.joined", "{player}", leader.getName()));
        broadcast(party, cfg.msg("party.member-joined", "{player}", joiner.getName()), joiner.getUniqueId());

        joiner.getInventory().clear();
        joiner.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        joiner.getInventory().setItemInOffHand(null);
        plugin.getHotbarManager().givePartyMemberHotbar(joiner);
        joiner.updateInventory();
    }

    // ── Deny ────────────────────────────────────────────────────────────────

    public void denyInvite(Player player, String leaderName) {
        ConfigManager cfg = plugin.getConfigManager();
        UUID leaderUUID = null;
        var leader = Bukkit.getPlayerExact(leaderName);
        if (leader != null) leaderUUID = leader.getUniqueId();
        if (leaderUUID == null || !leaderUUID.equals(pendingInvites.get(player.getUniqueId()))) {
            player.sendMessage(cfg.msg("party.no-invite", "{player}", leaderName));
            return;
        }
        pendingInvites.remove(player.getUniqueId());
        player.sendMessage(cfg.msg("party.invite-denied", "{player}", leaderName));
        if (leader != null) leader.sendMessage(cfg.msg("party.invite-denied-notify", "{player}", player.getName()));
    }

    // ── Leave ────────────────────────────────────────────────────────────────

    public void leave(Player player) {
        ConfigManager cfg = plugin.getConfigManager();

        Party party = getParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(cfg.msg("party.not-in-party"));
            return;
        }

        // FIX (section 3): block leave during active FFA match
        if (isInActiveMatch(player.getUniqueId())) {
            player.sendMessage(cfg.msg("party.use-leave-in-match"));
            return;
        }

        if (party.isLeader(player.getUniqueId())) {
            disband(player);
            return;
        }

        removeMember(party, player.getUniqueId());
        player.sendMessage(cfg.msg("party.left"));
        broadcast(party, cfg.msg("party.member-left", "{player}", player.getName()), null);

        plugin.getHotbarManager().restoreHotbar(player);
    }

    // ── Kick ────────────────────────────────────────────────────────────────

    public void kick(Player leader, String targetName) {
        ConfigManager cfg = plugin.getConfigManager();

        Party party = getParty(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.not-leader"));
            return;
        }

        // FIX (section 3): block kick during active FFA match
        if (isInActiveMatch(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.cannot-kick-in-match"));
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !party.isMember(target.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.not-member", "{player}", targetName));
            return;
        }

        removeMember(party, target.getUniqueId());
        target.sendMessage(cfg.msg("party.kicked"));
        broadcast(party, cfg.msg("party.member-kicked", "{player}", target.getName()), null);

        plugin.getHotbarManager().restoreHotbar(target);
    }

    // ── Disband ───────────────────────────────────────────────────────────────

    public void disband(Player leader) {
        ConfigManager cfg = plugin.getConfigManager();

        Party party = getParty(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.not-leader"));
            return;
        }

        // FIX (section 3): block disband during active FFA match
        if (isInActiveMatch(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.cannot-disband-in-match"));
            return;
        }

        disbandInternal(party, cfg.msg("party.disbanded"));
    }

    public void disbandOnQuit(UUID leaderUuid) {
        Party party = parties.get(leaderUuid);
        if (party == null) return;

        if (party.isLeader(leaderUuid)) {
            ConfigManager cfg = plugin.getConfigManager();
            disbandInternal(party, cfg.msg("party.disbanded-owner-left"));
        } else {
            String name = Bukkit.getOfflinePlayer(leaderUuid).getName();
            if (name == null) name = leaderUuid.toString().substring(0, 8);
            removeMember(party, leaderUuid);
            broadcast(party, plugin.getConfigManager()
                    .msg("party.member-left", "{player}", name), null);
        }
    }

    private void disbandInternal(Party party, String message) {
        // Cancel any outstanding invites from this leader — otherwise an invited
        // player could still accept after the party is gone and silently spawn a
        // brand-new party (the "disband then accept" bug).
        UUID leaderId = party.getLeader();
        if (leaderId != null) pendingInvites.values().removeIf(leaderId::equals);

        for (UUID uuid : party.getMembers()) {
            parties.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
                plugin.getHotbarManager().restoreHotbar(p);
            }
        }
    }

    // ── Promote ───────────────────────────────────────────────────────────────

    public void promote(Player leader, String targetName) {
        ConfigManager cfg = plugin.getConfigManager();

        Party party = getParty(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.not-leader"));
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !party.isMember(target.getUniqueId())) {
            leader.sendMessage(cfg.msg("party.not-member", "{player}", targetName));
            return;
        }

        party.setLeader(target.getUniqueId());
        target.sendMessage(cfg.msg("party.promoted"));
        leader.sendMessage(cfg.msg("party.promoted-notify", "{player}", target.getName()));

        plugin.getHotbarManager().givePartyLeaderHotbar(target);
        plugin.getHotbarManager().givePartyMemberHotbar(leader);
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    public void toggleChat(Player player) {
        ConfigManager cfg = plugin.getConfigManager();
        Party party = getParty(player.getUniqueId());
        if (party == null) {
            player.sendMessage(cfg.msg("party.not-in-party"));
            return;
        }
        party.toggleChat(player.getUniqueId());
        if (party.hasChatEnabled(player.getUniqueId())) {
            player.sendMessage(cfg.msg("party.chat-enabled"));
        } else {
            player.sendMessage(cfg.msg("party.chat-disabled"));
        }
    }

    public boolean handleChat(Player player, String message) {
        Party party = getParty(player.getUniqueId());
        if (party == null || !party.hasChatEnabled(player.getUniqueId())) return false;

        // Section 7: translate color codes AFTER substituting {message} so
        // that codes inside the message body are also rendered correctly.
        String raw = plugin.getConfigManager().getLang()
                .getString("party.chat-format", "&8[&bПати&8] &e{player}&7: &f{message}");
        String formatted = com.scorepvp.util.ColorUtil.color(
                raw.replace("{player}", player.getName()).replace("{message}", message));

        for (UUID uuid : party.getMembers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(formatted);
        }
        return true;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void removeMember(Party party, UUID uuid) {
        party.removeMember(uuid);
        parties.remove(uuid);
    }

    private void broadcast(Party party, String message, UUID exclude) {
        for (UUID uuid : party.getMembers()) {
            if (uuid.equals(exclude)) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        }
    }

    private net.kyori.adventure.text.Component buildInviteMessage(String leaderName) {
        var cfg = plugin.getConfigManager();
        net.kyori.adventure.text.Component header =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(cfg.rawMsg("party.invite-header", "{player}", leaderName));
        net.kyori.adventure.text.Component accept =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(cfg.rawStr("party.invite-accept-btn"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/party accept " + leaderName))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(cfg.rawStr("party.invite-accept-hover"))));
        net.kyori.adventure.text.Component deny =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(cfg.rawStr("party.invite-decline-btn"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/party deny " + leaderName))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(cfg.rawStr("party.invite-decline-hover"))));
        return header.appendNewline().append(accept).append(deny);
    }
}
