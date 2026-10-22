package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.VanillaKitGUI;
import com.scorepvp.manager.VanillaKitManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Drives the vanilla multi-kit menu:
 * <ul>
 *   <li>FLOWER_BANNER_PATTERN (empty slot) → prompt for a name in chat, then
 *       create the kit with the default loadout and select it.</li>
 *   <li>BOOK / ENCHANTED_BOOK (created kit):
 *     <ul>
 *       <li>LMB → select this kit</li>
 *       <li>RMB → edit: teleport to the vanilla room with this kit's items</li>
 *       <li>Shift+LMB → rename (chat prompt)</li>
 *       <li>Shift+RMB → reset to the default loadout</li>
 *     </ul>
 *   </li>
 *   <li>BARRIER (locked) → per-slot "no permission" message.</li>
 * </ul>
 */
public class VanillaKitListener implements Listener {

    private final ScorePvP plugin;

    public VanillaKitListener(ScorePvP plugin) { this.plugin = plugin; }

    // ── Menu clicks ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        VanillaKitGUI gui = plugin.getVanillaKitGUI();
        if (!gui.isOpen(p.getUniqueId())) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int index = gui.kitIndexAt(e.getRawSlot());
        if (index < 0) return;

        VanillaKitManager vk = plugin.getVanillaKitManager();
        if (!vk.canUseSlot(p, index)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            tell(p, "vanilla-kits.msg-locked", "{n}", String.valueOf(index + 1));
            return;
        }

        boolean has = vk.hasKit(p.getUniqueId(), index);
        if (!has) {
            // Create flow — ask for a name in chat.
            vk.setPendingCreate(p.getUniqueId(), index);
            p.closeInventory();
            tell(p, "vanilla-kits.msg-enter-name", null, null);
            return;
        }

        boolean shift = e.isShiftClick();
        boolean right = e.isRightClick();

        if (shift && !right) {            // Shift+LMB → rename
            vk.setPendingRename(p.getUniqueId(), index);
            p.closeInventory();
            tell(p, "vanilla-kits.msg-enter-rename", null, null);
        } else if (shift) {               // Shift+RMB → reset
            vk.resetKit(p.getUniqueId(), index);
            p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
            tell(p, "vanilla-kits.msg-reset", null, null);
            gui.open(p);
        } else if (right) {               // RMB → edit in the vanilla room
            editInRoom(p, index);
        } else {                          // LMB → select
            vk.selectKit(p.getUniqueId(), index);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            tell(p, "vanilla-kits.msg-selected", "{name}",
                    vk.getKit(p.getUniqueId(), index).getName());
            gui.open(p);
        }
    }

    /** Teleports to the vanilla room and hands the player this kit's items to edit. */
    private void editInRoom(Player p, int index) {
        VanillaKitManager vk = plugin.getVanillaKitManager();
        vk.setEditing(p.getUniqueId(), index);
        p.closeInventory();

        org.bukkit.Location warp = plugin.getConfigManager().getWarpLocation();
        if (warp == null) {
            tell(p, "kit.not-in-region", null, null);
            vk.clearEditing(p.getUniqueId());
            return;
        }
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        plugin.getPlayerDataManager().setInVanillaRegion(p.getUniqueId(), true);
        p.teleport(warp);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            org.bukkit.inventory.ItemStack[] items = vk.getKitItems(p.getUniqueId(), index);
            if (items != null) plugin.getKitManager().applyAllContentsPublic(p, items);
            tell(p, "vanilla-kits.msg-editing", "{name}",
                    vk.getKit(p.getUniqueId(), index) != null
                            ? vk.getKit(p.getUniqueId(), index).getName() : "");
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (plugin.getVanillaKitGUI().isOpen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getVanillaKitGUI().close(p.getUniqueId());
    }

    // ── Chat naming ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        VanillaKitManager vk = plugin.getVanillaKitManager();
        if (!vk.hasPendingChat(p.getUniqueId())) return;

        e.setCancelled(true);
        String name = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();

        // "cancel" aborts the prompt without changing anything.
        if (name.equalsIgnoreCase("cancel") || name.equalsIgnoreCase("bekor")) {
            vk.cancelPendingChat(p.getUniqueId());
            reopenLater(p);
            tell(p, "vanilla-kits.msg-cancelled", null, null);
            return;
        }

        // AsyncChatEvent fires off the main thread — hop back on for the kit-map
        // mutation, file save, and menu reopen.
        Integer createIdx = vk.consumePendingCreate(p.getUniqueId());
        if (createIdx != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return; // quit before task ran — don't repopulate cache
                vk.createKit(p.getUniqueId(), createIdx, name);
                tell(p, "vanilla-kits.msg-created", "{name}", name);
                plugin.getVanillaKitGUI().open(p);
            });
            return;
        }
        Integer renameIdx = vk.consumePendingRename(p.getUniqueId());
        if (renameIdx != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return; // quit before task ran — don't repopulate cache
                vk.renameKit(p.getUniqueId(), renameIdx, name);
                tell(p, "vanilla-kits.msg-renamed", "{name}", name);
                plugin.getVanillaKitGUI().open(p);
            });
        }
    }

    private void reopenLater(Player p) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> { if (p.isOnline()) plugin.getVanillaKitGUI().open(p); });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void tell(Player p, String key, String ph, String v) {
        String prefix = plugin.getConfigManager().rawMsg("prefix");
        // vanilla-kits.* live in config.yml; other keys (kit.*) in lang.
        String body = key.startsWith("vanilla-kits.")
                ? plugin.getConfig().getString(key, "")
                : plugin.getConfigManager().rawStr(key);
        if (body == null || body.isEmpty()) return;
        if (ph != null) body = body.replace(ph, v);
        p.sendMessage(com.scorepvp.util.ColorUtil.color(prefix + " " + body));
    }
}
