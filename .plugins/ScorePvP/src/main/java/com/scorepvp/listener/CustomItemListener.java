package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.item.CustomItemRegistry;
import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golden Head — eaten like a golden apple (with eat animation), at kelp-like
 * speed. The item carries a food component (see {@link CustomItemRegistry}),
 * so vanilla drives the eat animation and fires {@link PlayerItemConsumeEvent}
 * when it finishes; we apply the configured effects there.
 *
 * Config-driven values from custom-items.golden-head in config.yml:
 *   cooldown-seconds, effects.regeneration.amplifier/duration-seconds,
 *   effects.absorption.amplifier/duration-seconds
 */
public class CustomItemListener implements Listener {

    private final ScorePvP plugin;

    /** player → running cooldown action-bar timer task (one at a time). */
    private final Map<UUID, BukkitTask> cooldownTasks = new ConcurrentHashMap<>();

    public CustomItemListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    /**
     * The golden head is now a HONEY_BOTTLE (see {@link CustomItemRegistry}),
     * which is NOT a placeable block — so the whole "head places on the ground"
     * bug class is gone at the root: there is nothing to place. We only ever want
     * eating, whether the player is looking at a block or at air. Denying block
     * activation keeps a right-click against e.g. a chest from opening it instead
     * of eating, while ALLOWing item-use drives vanilla's eat animation +
     * {@link PlayerItemConsumeEvent}.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        // Check BOTH hands (no HAND-only early return): e.getItem() is this hand's
        // item, so a per-hand check covers main-hand AND off-hand.
        ItemStack item = e.getItem();
        if (!CustomItemRegistry.isCustomItem(item, CustomItemRegistry.GOLDEN_HEAD_ID)) return;

        // Looking at a block: suppress block activation so we don't open a chest
        // etc., but still ALLOW the eat.
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
        e.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
    }

    /** Applies the configured regen/absorption + starts the cooldown & actionbar. */
    private void applyGoldenHeadEffects(Player player) {
        var cfg = plugin.getConfig();
        int regenAmp    = cfg.getInt("custom-items.golden-head.effects.regeneration.amplifier", 2);
        int regenSec    = cfg.getInt("custom-items.golden-head.effects.regeneration.duration-seconds", 5);
        int absAmp      = cfg.getInt("custom-items.golden-head.effects.absorption.amplifier", 0);
        int absSec      = cfg.getInt("custom-items.golden-head.effects.absorption.duration-seconds", 120);
        int cooldownSec = cfg.getInt("custom-items.golden-head.cooldown-seconds", 10);

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION, regenSec * 20, regenAmp, false, true, true));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.ABSORPTION, absSec * 20, absAmp, false, true, true));

        player.setCooldown(CustomItemRegistry.GOLDEN_HEAD_MATERIAL, cooldownSec * 20);
        startCooldownActionbar(player);

        player.sendMessage(plugin.getConfigManager().rawMsg("custom-item.golden-head-use",
                "{regen_sec}", String.valueOf(regenSec),
                "{abs_sec}", String.valueOf(absSec)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack eaten = e.getItem();
        if (!CustomItemRegistry.isCustomItem(eaten, CustomItemRegistry.GOLDEN_HEAD_ID)) return;

        Player player = e.getPlayer();

        // Cooldown gate — if still cooling down, block the consume entirely.
        if (player.hasCooldown(CustomItemRegistry.GOLDEN_HEAD_MATERIAL)) {
            e.setCancelled(true);
            return;
        }

        // Vanilla will consume one from the eaten stack (main or off hand)
        // automatically because it has a food component, so we do NOT decrement
        // it ourselves — doing so would remove two.
        applyGoldenHeadEffects(player);
    }

    /**
     * Shows a live golden-head cooldown countdown in the player's action bar,
     * refreshing every ~4 ticks until the cooldown (tracked on PLAYER_HEAD)
     * expires. Format + on/off come from config. The action bar is driven off
     * the player's REAL remaining cooldown so it stays accurate even if they eat
     * again or the cooldown is cleared elsewhere.
     */
    private void startCooldownActionbar(Player player) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("custom-items.golden-head.cooldown-actionbar.enabled", true)) return;
        String format = cfg.getString("custom-items.golden-head.cooldown-actionbar.format",
                "&6Golden Head &7cooldown: &c{seconds}s");
        if (format == null || format.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        // Replace any existing timer so we never run two at once.
        BukkitTask old = cooldownTasks.remove(uuid);
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl == null || !pl.isOnline()) { stopCooldownActionbar(uuid); return; }
            int ticksLeft = pl.getCooldown(CustomItemRegistry.GOLDEN_HEAD_MATERIAL);
            if (ticksLeft <= 0) { stopCooldownActionbar(uuid); return; }
            double secLeft = ticksLeft / 20.0;
            String line = format
                    .replace("{seconds}", Integer.toString((int) Math.ceil(secLeft)))
                    .replace("{time}", String.format(java.util.Locale.ROOT, "%.1f", secLeft));
            pl.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(ColorUtil.color(line)));
        }, 0L, 4L);
        cooldownTasks.put(uuid, task);
    }

    private void stopCooldownActionbar(UUID uuid) {
        BukkitTask t = cooldownTasks.remove(uuid);
        if (t != null) t.cancel();
    }

    /**
     * Bug 10: thrown experience bottles (crystal / smp / netherite kits) felt
     * dead in a duel — the splash XP orbs frequently failed to repair Mending
     * armor mid-fight (orbs scatter, get cleaned up by the arena drop-sweep, or
     * the thrower moves before pickup). We repair the thrower's Mending gear
     * DIRECTLY from the bottle's XP and grant the remainder to the player, so a
     * crystal-kit bottle reliably both "splashes" (vanilla still spawns the
     * visual orbs) and patches armor.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExpBottle(org.bukkit.event.entity.ExpBottleEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player player)) return;
        if (plugin.getMatchManager().getMatch(player.getUniqueId()) == null) return;

        int xp = e.getExperience();
        if (xp <= 0) return;
        // Don't double-grant: we consume the orb XP ourselves and zero out the
        // vanilla orb so the thrower can't pick the same XP up twice.
        e.setExperience(0);

        int remaining = repairMendingGear(player, xp);
        if (remaining > 0) player.giveExp(remaining);
    }

    /**
     * Spends up to {@code xp} experience repairing the player's damaged
     * Mending-enchanted equipment (2 durability per XP, vanilla rate). Returns
     * the leftover XP that no gear needed.
     */
    private int repairMendingGear(Player player, int xp) {
        org.bukkit.inventory.ItemStack[] gear = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
        for (org.bukkit.inventory.ItemStack item : gear) {
            if (xp <= 0) break;
            if (item == null || item.getType().isAir()) continue;
            if (item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.MENDING) <= 0) continue;
            if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg)) continue;
            if (!dmg.hasDamage()) continue;
            int damage = dmg.getDamage();
            int repairable = Math.min(damage, xp * 2);
            int xpSpent = (repairable + 1) / 2;
            dmg.setDamage(damage - repairable);
            item.setItemMeta(dmg);
            xp -= xpSpent;
        }
        return Math.max(0, xp);
    }
}
