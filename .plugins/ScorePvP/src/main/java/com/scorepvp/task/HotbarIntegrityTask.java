package com.scorepvp.task;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.ConfigManager;
import com.scorepvp.manager.HotbarManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runs every 3 seconds and verifies that the 4 protected hotbar slots
 * are not empty for players who are NOT in the vanilla region.
 * If a slot is missing its item, it is silently restored.
 */
public class HotbarIntegrityTask extends BukkitRunnable {

    private final ScorePvP plugin;

    public HotbarIntegrityTask(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            java.util.UUID uuid = player.getUniqueId();

            // For anyone NOT in a match:
            //  1) auto-heal leftover duel nametag / below-name HP+ping (the
            //     ghost from an opponent leaving during countdown/spectate
            //     clears itself WITHOUT a relog), then
            //  2) (re)apply the lobby nametag — highest tier next to the nick,
            //     e.g. "SHOXMONEY | HT1".
            if (!plugin.getMatchManager().isInMatch(uuid)) {
                try { plugin.getNameTagProvider().clearStrayState(player); }
                catch (Throwable ignored) {}
                try { plugin.getNameTagProvider().applyLobbyNametag(player); }
                catch (Throwable ignored) {}
            }

            // Skip players whose match just ended but who haven't been teleported
            // back to spawn yet — handing them lobby items here would drop the
            // hotbar on them mid-arena before the deferred spawn teleport (Bug 5).
            if (plugin.getMatchManager().isPendingSpawnTeleport(uuid)) continue;

            // Skip players inside the vanilla region — they have a custom inventory
            if (plugin.getPlayerDataManager().isInVanillaRegion(uuid)) continue;

            // Skip players in kit editor mode — hotbar is intentionally hidden
            if (plugin.getPlayerDataManager().isKitEditorMode(uuid)) continue;

            // Skip players in a match — their inventory belongs to the kit
            if (plugin.getMatchManager().isInMatch(uuid)) continue;

            // Skip players searching in queue — slot 4 holds the Barrier cancel button
            if (plugin.getDuelManager().isInQueue(uuid)) continue;

            // Skip players in a party — they have their own party hotbar (leader or member)
            if (plugin.getPartyManager().isInParty(uuid)) {
                // If the party leader is in a match (FFA active), skip repair entirely
                // to prevent overwriting match kits during the transition window
                com.scorepvp.model.Party party = plugin.getPartyManager().getParty(uuid);
                if (party != null && plugin.getMatchManager().isInMatch(party.getLeader())) continue;
                checkAndRepairParty(player);
                continue;
            }

            // Skip players with an open GUI (would cause visual flicker)
            String openTitle = player.getOpenInventory().getTitle();
            if (openTitle.equals(plugin.getMainMenuGUI().getTitle())
             || openTitle.equals(plugin.getSettingsGUI().getTitle())) continue;

            checkAndRepairNormal(player);
        }
    }

    /** Repairs normal (non-party) lobby hotbar if any protected slot is empty. */
    private void checkAndRepairNormal(Player player) {
        var inv = player.getInventory();
        boolean needsRepair = false;

        for (int slot : ConfigManager.PROTECTED_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) {
                needsRepair = true;
                break;
            }
        }

        // A lobby player who is NOT in a queue and NOT in a party must never
        // keep a slot-4 (queue/party barrier). If one lingered — e.g. they
        // relogged out of a queue and the barrier survived the session — wipe
        // it here so the menu's middle slot doesn't stay stuck on a barrier.
        ItemStack slot4 = inv.getItem(HotbarManager.SLOT_QUEUE);
        if (slot4 != null && !slot4.getType().isAir()) {
            inv.setItem(HotbarManager.SLOT_QUEUE, null);
        }

        if (needsRepair) {
            plugin.getHotbarManager().giveHotbar(player);
        }
    }

    /** Repairs party hotbar: leader needs items at slot 0 & 8, member needs item at slot 4. */
    private void checkAndRepairParty(Player player) {
        var inv = player.getInventory();
        boolean isLeader = plugin.getPartyManager().isLeader(player.getUniqueId());

        if (isLeader) {
            ItemStack slot0 = inv.getItem(HotbarManager.SLOT_MENU);
            ItemStack slot8 = inv.getItem(HotbarManager.SLOT_WARP);
            if (slot0 == null || slot0.getType().isAir()
             || slot8 == null || slot8.getType().isAir()) {
                plugin.getHotbarManager().givePartyLeaderHotbar(player);
            }
        } else {
            ItemStack slot4 = inv.getItem(HotbarManager.SLOT_QUEUE);
            if (slot4 == null || slot4.getType().isAir()) {
                plugin.getHotbarManager().givePartyMemberHotbar(player);
            }
        }
    }
}
