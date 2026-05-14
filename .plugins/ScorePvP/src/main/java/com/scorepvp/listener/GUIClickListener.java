package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.*;
import com.scorepvp.model.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.*;

public class GUIClickListener implements Listener {

    private final ScorePvP plugin;

    public GUIClickListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        String title = e.getView().getTitle();

        if (title.equals(plugin.getMainMenuGUI().getTitle())) {
            e.setCancelled(true); handleMainMenu(player, e.getRawSlot()); return;
        }
        if (title.equals(plugin.getSettingsGUI().getTitle())) {
            e.setCancelled(true); plugin.getSettingsGUI().handleClick(player, e.getRawSlot()); return;
        }
        // IMPORTANT: Check DuelRoundsGUI BEFORE DuelModeGUI because
        // DuelModeGUI.isThisGUI() uses startsWith("Дуэль: ") which also matches
        // the rounds GUI title "Дуэль: раунды"
        if (plugin.getDuelRoundsGUI().isThisGUI(title)) {
            e.setCancelled(true); handleDuelRounds(player, e.getRawSlot()); return;
        }
        if (plugin.getDuelModeGUI().isThisGUI(title)) {
            e.setCancelled(true); handleDuelMode(player, e.getRawSlot()); return;
        }
        if (plugin.getPartyFfaGUI().isThisGUI(title)) {
            e.setCancelled(true); handlePartyFfa(player, e.getRawSlot()); return;
        }
        if (plugin.getProfileGUI().isThisGUI(title)) {
            e.setCancelled(true);
            return;
        }
        // Dynamic menus — players can take items (template restores on reopen)
        if (plugin.getMenuManager().isMenuTitle(title)) {
            return; // Allow interaction
        }
        // Dynamic menu editor — allow admin full access (save on close)
        if (plugin.getMenuManager().isEditorTitle(title)) {
            return; // Allow interaction
        }
    }

    // ── Main PvP menu ─────────────────────────────────────────────────────────

    private void handleMainMenu(Player player, int slot) {
        // Party check
        if (plugin.getPartyManager().isInParty(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("party.pvp-blocked"));
            player.closeInventory(); return;
        }

        // Player-aware slot resolution — returns null if version incompatible or glass
        String modeId = plugin.getMainMenuGUI().getModeAtSlot(player, slot);
        if (modeId == null) {
            // Show version error if it's a valid but incompatible slot
            String rawId = MainMenuGUI.getModeAtSlot(slot, plugin.getConfigManager().getModes());
            if (rawId != null) {
                PvPMode mode = plugin.getConfigManager().getMode(rawId);
                if (mode != null && !plugin.getClientVersionManager().canPlay(player, mode)) {
                    String pv = plugin.getClientVersionManager().getClientVersion(player);
                    player.sendMessage(plugin.getConfigManager().msg("messages.version-too-low",
                            "{mode}", mode.getDisplayName(),
                            "{min_version}", mode.getMinClientVersion(),
                            "{player_version}", pv));
                }
            }
            return;
        }

        PvPMode mode = plugin.getConfigManager().getMode(modeId);
        if (mode == null || !mode.isEnabled()) return;
        player.closeInventory();
        plugin.getDuelManager().joinQueue(player, modeId);
    }

    // ── Duel mode selection ───────────────────────────────────────────────────

    private void handleDuelMode(Player player, int slot) {
        String modeId = plugin.getDuelModeGUI().getModeAtSlot(slot);
        if (modeId == null) return;
        UUID targetUuid = plugin.getDuelModeGUI().getTarget(player.getUniqueId());
        if (targetUuid == null) { player.closeInventory(); return; }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", ""));
            player.closeInventory();
            plugin.getDuelModeGUI().clearTarget(player.getUniqueId()); return;
        }
        plugin.getDuelRoundsGUI().open(player, target, modeId);
    }

    // ── Duel rounds selection ────────────────────────────────────────────────

    private void handleDuelRounds(Player player, int slot) {
        DuelRoundsGUI.RoundSelection sel = plugin.getDuelRoundsGUI().handleClick(player, slot);
        if (sel == null) return;
        plugin.getDuelManager().sendDuel(player, sel.target(), sel.modeName(), sel.rounds());
        plugin.getDuelModeGUI().clearTarget(player.getUniqueId());
        player.closeInventory();
    }

    // ── Party FFA ─────────────────────────────────────────────────────────────

    private void handlePartyFfa(Player player, int slot) {
        if (!plugin.getPartyManager().isLeader(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("party.not-leader"));
            player.closeInventory(); return;
        }

        String modeId = plugin.getPartyFfaGUI().getModeAtSlot(slot);
        if (modeId == null) return;

        PvPMode mode = plugin.getConfigManager().getMode(modeId);
        if (mode == null) return;

        Party party = plugin.getPartyManager().getParty(player.getUniqueId());
        if (party == null) return;

        // Minimum online members check
        int minMembers = plugin.getConfig().getInt("party.ffa-min-members", 2);
        List<Player> onlineMembers = party.getMembers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
        if (onlineMembers.size() < minMembers) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("ffa.min-members",
                    "{min}", String.valueOf(minMembers)));
            player.closeInventory(); return;
        }

        // Version check for every party member
        var cvm = plugin.getClientVersionManager();
        List<String> incompatible = new ArrayList<>();
        for (Player m : onlineMembers) {
            if (!cvm.canPlay(m, mode))
                incompatible.add(m.getName() + " (&c" + cvm.getClientVersion(m) + "&7)");
        }
        if (!incompatible.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("ffa.incompatible-versions",
                    "{version}", mode.getMinClientVersion()));
            incompatible.forEach(s -> player.sendMessage(com.scorepvp.util.ColorUtil.color(
                    plugin.getConfigManager().rawStr("ffa.incompatible-entry").replace("{entry}", s))));
            player.closeInventory(); return;
        }

        // Find arena — cancel immediately if none found
        var arenaOpt = plugin.getArenaManager().findFreeArenaForFfa(mode);
        if (arenaOpt.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().msg("ffa.no-arena",
                    "{mode}", mode.getDisplayName()));
            player.closeInventory(); return;
        }
        Arena arena = arenaOpt.get();

        // Re-check online members at teleport time
        List<Player> teleportMembers = party.getMembers().stream()
                .map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        if (teleportMembers.size() < minMembers) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("ffa.not-enough-members"));
            player.closeInventory(); return;
        }

        player.closeInventory();

        // Use MatchManager to properly create FFA match (handles teleport, kits, countdown, nametags, scoreboard)
        Match match = plugin.getMatchManager().createFfaMatch(arena, mode, teleportMembers);
        if (match == null) {
            // Arena became busy between check and creation
            player.sendMessage(plugin.getConfigManager().msg("ffa.no-arena",
                    "{mode}", mode.getDisplayName()));
            return;
        }

        for (Player member : teleportMembers) {
            member.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("ffa.started",
                    "{mode}", mode.getDisplayName()));
        }
    }
}
