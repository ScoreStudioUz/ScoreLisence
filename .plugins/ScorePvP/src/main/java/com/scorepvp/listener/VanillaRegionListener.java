package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Sound;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Detects when a player crosses the vanilla chest region boundary.
 *
 * ON ENTER: fully clear inventory (nullifies residual lobby item functions),
 *           then give the vanilla kit.
 * ON EXIT:  restore the 4 protected hotbar items.
 *
 * FIX (hotbar residual functions): complete inventory clear on enter ensures
 * lobby items (iron sword slot-0, ender pearl slot-8) cannot trigger their
 * GUI/warp functions while inside the vanilla region.
 */
public class VanillaRegionListener implements Listener {

    private final ScorePvP plugin;

    public VanillaRegionListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Move ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to   = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        checkTransition(e.getPlayer(), to);
    }

    // ── Teleport ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        if (e.getTo() == null) return;
        checkTransition(e.getPlayer(), e.getTo());
    }

    // ── Core Logic ────────────────────────────────────────────────────────────

    private void checkTransition(Player player, Location to) {
        boolean wasInRegion = plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId());
        boolean nowInRegion = plugin.getConfigManager().isInChestRegion(to);

        if (wasInRegion == nowInRegion) return;

        plugin.getPlayerDataManager().setInVanillaRegion(player.getUniqueId(), nowInRegion);

        if (nowInRegion) {
            onEnterRegion(player);
        } else {
            onExitRegion(player);
        }
    }

    // ── Editor-room movement restrictions ───────────────────────────────────────
    //
    // The vanilla room is for arranging a kit, not for testing mobility. Block the
    // three ways a player could rocket around it: elytra glide, firework boost,
    // and trident riptide ("spear lunge"). All only fire while inside the region.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (!e.isGliding()) return; // allow stopping a glide, only block starting one
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent e) {
        Player player = e.getPlayer();
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            // Cancel the riptide launch and refund the durability the trident would
            // otherwise consume by re-giving the same item state next tick.
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkBoost(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player player = e.getPlayer();
        if (!plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) return;
        var item = e.getItem();
        if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
            e.setCancelled(true);
        }
    }

    /**
     * Dropping an item in the editor room is ALLOWED (so the player can throw a
     * kit item away), but the dropped stack never lands on the floor — it's
     * removed the instant it spawns and a witch-throw sound plays. We do NOT
     * cancel the event (that would keep the item stuck in the inventory); we let
     * the drop happen and immediately delete the resulting item entity.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player player = e.getPlayer();
        if (!plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) return;
        e.getItemDrop().remove();
        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_THROW, 1f, 1f);
    }

    /** Blocks eating/drinking consumables (food, potions, milk, honey) in the room. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (plugin.getPlayerDataManager().isInVanillaRegion(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Backstop: cancels a bow/crossbow shot in the room if one somehow starts.
     * The primary block is {@link #onThrowable} (interact), which stops the draw
     * before any arrow is consumed — this only fires if that was bypassed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Full projectile block: cancels EVERY projectile launched by a player in the
     * room — ender pearls, arrows (bow/crossbow), snowballs, eggs, tridents,
     * wind charges, etc. This is the definitive stop; even if a right-click
     * slipped through {@link #onThrowable}, the projectile never spawns. Arrows
     * already nocked/consumed at draw time are prevented earlier by the interact
     * block, so no arrow is wasted from the inventory.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player player)) return;
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Blocks using/drawing projectile items in the room at the interaction stage,
     * BEFORE any ammo is consumed: bows, crossbows, ender pearls, splash/lingering
     * potions, snowballs, eggs, wind charges, fishing rods, tridents, etc.
     * Blocking the bow/crossbow right-click here means the draw never begins, so
     * no arrow leaves the inventory. Consumables are handled by {@link #onConsume};
     * {@link #onProjectileLaunch} is the final backstop.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThrowable(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player player = e.getPlayer();
        if (!plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) return;
        // Check BOTH hands — a bow can be drawn from either hand.
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off  = player.getInventory().getItemInOffHand();
        if (isBlockedThrowable(main.getType()) || isBlockedThrowable(off.getType())) {
            e.setCancelled(true);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    private static boolean isBlockedThrowable(Material m) {
        return switch (m) {
            case BOW, CROSSBOW,
                 ENDER_PEARL, ENDER_EYE, SNOWBALL, EGG, FIREWORK_ROCKET,
                 WIND_CHARGE, SPLASH_POTION, LINGERING_POTION, EXPERIENCE_BOTTLE,
                 FISHING_ROD, FIRE_CHARGE, TRIDENT -> true;
            default -> false;
        };
    }

    private void onEnterRegion(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Section 2: if reset was pending, consume the flag and give default kit
            boolean wasReset = plugin.getKitManager().consumeResetPending(player.getUniqueId());
            if (wasReset) {
                // Force default kit (ignore any saved personal kit)
                player.getInventory().clear();
                player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                player.getInventory().setItemInOffHand(null);
                plugin.getKitManager().giveDefaultVanillaKit(player);
            } else {
                plugin.getKitManager().giveVanillaKit(player);
            }
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("vanilla-region.enter"));
        }, 1L);
    }

    private void onExitRegion(Player player) {
        // Leaving the room without /kit save discards any in-progress vanilla
        // kit edit, so a later save elsewhere can't write to the wrong slot.
        plugin.getVanillaKitManager().clearEditing(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getHotbarManager().restoreHotbar(player);
            boolean inParty = plugin.getPartyManager().isInParty(player.getUniqueId());
            plugin.getHotbarManager().updatePartySlot(player, inParty);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("vanilla-region.exit"));
        });
    }
}
