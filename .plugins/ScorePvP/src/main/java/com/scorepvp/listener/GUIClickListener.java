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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

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
        if (title.equals(plugin.getKitMenuGUI().getTitle())) {
            e.setCancelled(true); handleKitMenu(player, e.getRawSlot()); return;
        }
        if (plugin.getKitEditorGUI().isThisGUI(title)) {
            handleKitEditor(player, e); return;
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

    // ── Kit editor: drag must stay inside editable top slots ──────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!plugin.getKitEditorGUI().isThisGUI(e.getView().getTitle())) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            // Any drag touching the bottom inv or a non-editable top slot is
            // rejected wholesale (prevents smearing items into decoration/buttons
            // or pulling them down into survival).
            if (raw >= topSize || !plugin.getKitEditorGUI().isEditableSlot(raw)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ── Kit editor: forget the editing context on close ───────────────────────

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (plugin.getKitEditorGUI().isThisGUI(e.getView().getTitle())) {
            // If the player closed mid-drag with a sandbox item on the cursor,
            // vanilla would drop that cursor item into their real inventory —
            // duping it out of the editor. Discard the cursor before cleanup.
            ItemStack cursor = e.getView().getCursor();
            if (cursor != null && !cursor.getType().isAir()
                    && !plugin.getKitEditorGUI().isOffhandPlaceholder(cursor)) {
                e.getView().setCursor(null);
            }
            plugin.getKitEditorGUI().cleanup(player.getUniqueId());
        }
    }

    // ── Kit menu (mode picker → editor / vanilla teleport) ────────────────────

    private void handleKitMenu(Player player, int slot) {
        // Vanilla slot → open the multi-kit selection menu.
        if (plugin.getKitMenuGUI().isVanillaSlot(slot)) {
            plugin.getVanillaKitGUI().open(player);
            return;
        }
        String modeId = plugin.getKitMenuGUI().getModeAtSlot(slot);
        if (modeId == null) return;
        PvPMode mode = plugin.getConfigManager().getMode(modeId);
        if (mode == null || !mode.isEnabled()) return;
        plugin.getKitEditorGUI().open(player, mode.getKitType());
    }

    // ── Kit editor ────────────────────────────────────────────────────────────
    //
    // The editor is a 54-slot sandbox pre-filled with the player's kit (default
    // or saved). To stay dupe/theft-safe WITHOUT needing creative mode, items
    // may only be rearranged BETWEEN editable slots inside the editor:
    //   • control buttons run their action (cancelled),
    //   • the bottom (player survival) inventory is read-only (cancelled),
    //   • shift-clicks and drop-outside are cancelled (they'd move items across
    //     inventories / into the world),
    //   • only plain clicks on editable top slots are allowed.
    // The player customises by rearranging and removing pieces of the loadout.

    private void handleKitEditor(Player player, InventoryClickEvent e) {
        int raw = e.getRawSlot();
        int topSize = e.getView().getTopInventory().getSize();
        boolean topInv = raw >= 0 && raw < topSize;

        // Drop-outside-window (raw == -999) — never allow, it leaks the item.
        if (raw < 0) { e.setCancelled(true); return; }

        // Control buttons.
        if (topInv && plugin.getKitEditorGUI().isBackSlot(raw)) {
            e.setCancelled(true);
            plugin.getKitMenuGUI().open(player);
            return;
        }
        if (topInv && plugin.getKitEditorGUI().isResetSlot(raw)) {
            e.setCancelled(true);
            plugin.getKitEditorGUI().reset(player);
            return;
        }
        if (topInv && plugin.getKitEditorGUI().isSaveSlot(raw)) {
            e.setCancelled(true);
            plugin.getKitEditorGUI().save(player, e.getView().getTopInventory());
            player.closeInventory();
            return;
        }

        // Shift-click would jump an item across inventories — block it.
        if (e.isShiftClick()) { e.setCancelled(true); return; }
        // Collect-to-cursor (double-click) can pull from both inventories — block.
        if (e.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
            e.setCancelled(true); return;
        }
        // Hotbar number keys (1-9) and F (swap offhand) move an item between the
        // clicked slot and a REAL survival hotbar/offhand slot — that leaks kit
        // items out of the editor. Block every keyboard-driven move.
        org.bukkit.event.inventory.ClickType ct = e.getClick();
        if (ct == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                || ct == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                || ct == org.bukkit.event.inventory.ClickType.DROP
                || ct == org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
            e.setCancelled(true); return;
        }

        // Bottom (player survival) inventory is read-only inside the editor.
        if (!topInv) { e.setCancelled(true); return; }

        // Offhand slot: the placeholder must act like an empty slot. We drive it
        // manually so the placeholder never lands on the cursor and is restored
        // the moment the slot is emptied.
        if (raw == plugin.getKitEditorGUI().getOffhandSlot()) {
            handleOffhandClick(player, e);
            return;
        }

        // Armor slots only accept the matching armor piece — block anything else
        // (e.g. dropping a sword into the helmet slot).
        if (plugin.getKitEditorGUI().isArmorSlot(raw)) {
            ItemStack cursor = e.getCursor();
            // A click that would DEPOSIT the cursor into the armor slot: reject
            // if the cursor item isn't valid armor for that slot. Picking items
            // up / swapping out (empty cursor) is always fine.
            boolean depositing = cursor != null && !cursor.getType().isAir();
            if (depositing && !plugin.getKitEditorGUI().isValidForArmorSlot(raw, cursor)) {
                e.setCancelled(true);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            return;
        }

        // Top inventory: allow only editable slots; cancel filler/decoration.
        if (!plugin.getKitEditorGUI().isEditableSlot(raw)) {
            e.setCancelled(true);
        }
    }

    /**
     * Manual offhand-slot handling for the kit editor. The slot shows an
     * ITEM_FRAME placeholder when empty; we drive left-clicks ourselves so the
     * placeholder behaves exactly like an empty slot:
     *   • cursor empty + placeholder in slot → nothing happens,
     *   • cursor holds an item              → it's placed (placeholder replaced),
     *   • cursor empty + real item in slot   → item picked up, placeholder restored.
     * Only plain left-clicks are processed; everything else is cancelled.
     */
    private void handleOffhandClick(Player player, InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClick() != org.bukkit.event.inventory.ClickType.LEFT) return;

        org.bukkit.inventory.Inventory top = e.getView().getTopInventory();
        int slot = plugin.getKitEditorGUI().getOffhandSlot();
        ItemStack cursor = e.getCursor();
        ItemStack current = top.getItem(slot);
        ItemStack realInSlot = (current != null && !current.getType().isAir()
                && !plugin.getKitEditorGUI().isOffhandPlaceholder(current))
                ? current.clone() : null;
        boolean cursorHasItem = cursor != null && !cursor.getType().isAir();

        if (cursorHasItem) {
            // Place the cursor item into the offhand; hand back whatever was there
            // (a real item, or nothing if it only held the placeholder).
            top.setItem(slot, cursor.clone());
            e.getView().setCursor(realInSlot);
        } else {
            // Empty cursor: pick up a real item (if any) and restore placeholder.
            e.getView().setCursor(realInSlot);
            top.setItem(slot, plugin.getKitEditorGUI().offhandPlaceholder());
        }
        player.updateInventory();
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
        // SEND pressed — the round count and the custom-hats toggle (COAL @ slot 4)
        // are both captured in the selection, so fire the duel directly.
        plugin.getDuelModeGUI().clearTarget(player.getUniqueId());
        player.closeInventory();
        Player target = sel.target();
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.getConfigManager().msg("player-not-found", "{player}", ""));
            return;
        }
        plugin.getDuelManager().sendDuel(player, target, sel.modeName(), sel.rounds(), sel.withHats());
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
