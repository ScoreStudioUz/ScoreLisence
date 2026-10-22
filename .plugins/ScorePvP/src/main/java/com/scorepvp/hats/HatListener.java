package com.scorepvp.hats;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the hats menu clicks, resource-pack send/enforcement on join, and
 * re-applying the chosen lobby hat once the RP is confirmed. Freely removable —
 * no hat-lock behaviour (per requirement, players wear hats like any helmet).
 */
public class HatListener implements Listener {

    private final ScorePvP plugin;
    private final java.util.Set<UUID> rpLoaded = ConcurrentHashMap.newKeySet();

    public HatListener(ScorePvP plugin) { this.plugin = plugin; }

    public boolean hasLoadedRp(UUID uuid) { return rpLoaded.contains(uuid); }

    // ── Menu clicks ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        HatGUI gui = plugin.getHatGUI();
        boolean list    = gui.isListScreen(p.getUniqueId());
        boolean confirm = gui.isConfirmScreen(p.getUniqueId());
        if (!list && !confirm) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        boolean right = e.isRightClick();
        if (list) handleList(p, slot, right);
        else      handleConfirm(p, slot);
    }

    private void handleList(Player p, int slot, boolean right) {
        HatGUI gui = plugin.getHatGUI();
        HatManager hats = plugin.getHatManager();
        UUID uid = p.getUniqueId();
        int page = gui.page(uid);

        if (slot == hats.shopExitSlot()) { p.closeInventory(); return; }
        if (slot == hats.shopPrevSlot() && page > 0) { gui.openList(p, page - 1); return; }
        if (slot == hats.shopNextSlot() && gui.hasNextPage(uid)) { gui.openList(p, page + 1); return; }

        // Category button?
        String catId = gui.categoryAt(uid, slot);
        if (catId != null) {
            if (!catId.equals(gui.viewingCategory(uid))) gui.viewCategory(p, catId);
            return;
        }

        // Hat icon?
        String id = gui.hatIdAt(uid, slot);
        HatDefinition hat = hats.getHat(id);
        if (hat == null) return;

        if (hats.owns(p, hat)) {
            String selected = hats.getSelected(p);
            if (right) {
                // RMB → unset (only meaningful if this hat is the equipped one).
                if (id.equals(selected)) {
                    hats.setSelected(p, null);
                    hats.forceRemoveLobbyHat(p);
                    tell(p, hats.msgRemoved());
                    gui.openList(p, page);
                }
                return;
            }
            // LMB → equip (idempotent; owned hats stay on until RMB-unset).
            if (!id.equals(selected)) {
                hats.setSelected(p, id);
                hats.applyLobbyHat(p);
                tell(p, hats.shopEquipped().replace("{hat}", hat.getDisplayName()));
                gui.openList(p, page);
            }
        } else if (!right) {
            // Not owned + LMB → purchase confirm. (RMB on a locked hat: nothing.)
            gui.openConfirm(p, id);
        }
    }

    private void handleConfirm(Player p, int slot) {
        HatGUI gui = plugin.getHatGUI();
        HatManager hats = plugin.getHatManager();
        UUID uid = p.getUniqueId();
        int page = gui.page(uid);
        String id = gui.confirmingId(uid);

        if (slot == HatGUI.CONFIRM_CANCEL_SLOT) { gui.openList(p, page); return; }
        if (slot != HatGUI.CONFIRM_ACCEPT_SLOT) return;

        HatDefinition hat = id != null ? hats.getHat(id) : null;
        if (hat == null) { gui.openList(p, page); return; }
        if (hats.owns(p, hat)) { gui.openList(p, page); return; } // already owned

        int cost = hats.priceOf(hat);
        if (!plugin.getSilverManager().trySpend(uid, cost)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            tell(p, hats.shopNotEnough().replace("{price}", String.valueOf(cost)));
            gui.openList(p, page);
            return;
        }
        hats.grant(uid, id);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        tell(p, hats.shopBought().replace("{hat}", hat.getDisplayName()));
        gui.openList(p, page);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.getHatGUI().isOpen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getHatGUI().clear(p.getUniqueId());
    }

    // ── Resource pack ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        HatManager hats = plugin.getHatManager();
        if (!hats.rpEnabled()) {
            // No RP gate — the owned-hats load callback applies the hat once
            // ownership is known; this delayed pass covers the case where the
            // settings load (which holds the selected hat id) lands last.
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> { if (e.getPlayer().isOnline()) hats.applyLobbyHat(e.getPlayer()); }, 40L);
            return;
        }
        // Send a few ticks after join. Sending inside the join event sometimes
        // races the client's connection setup, so the pack prompt never shows and
        // the player just walks in without it. A short delay lets the connection
        // settle so the request reliably reaches the client.
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !rpLoaded.contains(p.getUniqueId())) sendResourcePack(p);
        }, 20L);
    }

    private void sendResourcePack(Player p) {
        HatManager hats = plugin.getHatManager();
        String url = hats.rpUrl();
        if (url == null || url.isBlank() || url.contains("example.com")) {
            plugin.getLogger().warning("[ScorePvP] hats resource-pack.url not configured; RP not sent.");
            return;
        }
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException ex) { plugin.getLogger().severe("[ScorePvP] hats resource-pack.url malformed: " + url); return; }

        ResourcePackInfo.Builder info = ResourcePackInfo.resourcePackInfo()
                .id(UUID.nameUUIDFromBytes(url.getBytes())).uri(uri);
        String sha1 = hats.rpSha1();
        if (sha1 != null && !sha1.isBlank()) {
            try { info.hash(sha1.trim()); } catch (Exception ignored) { }
        }
        ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
                .packs(info.build())
                .required(hats.rpMandatory())
                .prompt(LegacyComponentSerializer.legacyAmpersand().deserialize(ColorUtil.color(hats.rpPrompt())))
                .build();
        p.sendResourcePacks(req);
    }

    @EventHandler
    public void onRpStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        HatManager hats = plugin.getHatManager();
        boolean mandatory = hats.rpMandatory();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                rpLoaded.add(p.getUniqueId());
                // RP download usually lands well after the DB loads, but guard
                // anyway: apply now and again shortly, so a slow owned/settings
                // load still results in the hat being worn.
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) hats.applyLobbyHat(p); });
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) hats.applyLobbyHat(p); }, 40L);
            }
            case DECLINED -> { if (mandatory) kick(p, hats.rpDeclineKick()); }
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL -> { if (mandatory) kick(p, hats.rpFailedKick()); }
            default -> { /* ACCEPTED / DOWNLOADED — keep waiting */ }
        }
    }

    private void kick(Player p, String raw) {
        if (!p.isOnline()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.kick(LegacyComponentSerializer.legacyAmpersand().deserialize(ColorUtil.color(raw)));
        });
    }

    private void tell(Player p, String msg) {
        p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(ColorUtil.color(msg)));
    }
}
