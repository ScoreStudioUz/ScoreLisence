package uz.score.scorecheck.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import uz.score.scorecheck.ScoreCheck;
import uz.score.scorecheck.managers.CheckManager;
import uz.score.scorecheck.utils.ColorUtil;
import uz.score.scorecheck.utils.SoundUtil;

import java.util.UUID;

public class PlayerCheckListener implements Listener {

    private final ScoreCheck   plugin;
    private final CheckManager manager;

    public PlayerCheckListener(ScoreCheck plugin) {
        this.plugin  = plugin;
        this.manager = plugin.getCheckManager();
    }

    // ================================================================
    //  MOVEMENT — block XYZ change, allow head rotation
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;

        Location from = event.getFrom();
        Location to   = event.getTo();

        // Block position change, keep yaw/pitch (head look)
        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
            // Play sound feedback on escape attempt
            SoundUtil.play(player, "attempt-move");
            // Apply blindness punishment
            manager.applyEscapeBlindness(player);
        }
    }

    // ================================================================
    //  TELEPORT — block all except PLUGIN-sourced (our teleport)
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.PLUGIN
                && cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            event.setCancelled(true);
        }
    }

    // ================================================================
    //  COMMANDS — block all commands
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;

        ColorUtil.send(player, plugin.getConfigManager().getMessage("freeze-attempt"));
        event.setCancelled(true);
    }

    // ================================================================
    //  CHAT — intercept and relay to moderator only
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getScheduler().runTask(plugin, () ->
                manager.relayPlayerMessage(player, message));
    }

    // ================================================================
    //  ITEM USAGE
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  ITEM DROP / PICKUP
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  INVENTORY — block all clicks and drags
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  BLOCK BREAK / PLACE
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  COMBAT — prevent attacking
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  FOOD — freeze hunger bar
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  FLIGHT / ELYTRA
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  SWAP HAND (F key)
    // ================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!isChecked(player) || hasBypass(player)) return;
        event.setCancelled(true);
    }

    // ================================================================
    //  PLAYER JOIN — hide already-checked players from newcomer
    // ================================================================
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.onPlayerJoin(event.getPlayer());
    }

    // ================================================================
    //  PLAYER QUIT — execute ban
    // ================================================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();
        if (!manager.isInCheck(uid)) return;
        // Run on next tick so we're in the right state
        plugin.getServer().getScheduler().runTask(plugin, () ->
                manager.closeCheck(uid, CheckManager.CloseReason.DISCONNECT, null));
    }

    // ================================================================
    //  Helpers
    // ================================================================
    private boolean isChecked(Player player) {
        return manager.isInCheck(player.getUniqueId());
    }

    private boolean hasBypass(Player player) {
        return player.hasPermission("scorecheck.bypass");
    }
}
