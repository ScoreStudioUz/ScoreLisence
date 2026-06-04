package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.item.CustomItemRegistry;
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

    public CustomItemListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    /**
     * A PLAYER_HEAD is a placeable block, so right-clicking the golden head at a
     * block face would place it as a skull instead of eating it. Cancel the
     * block-interaction (placement) for the golden head; the CONSUMABLE
     * component still drives the eat animation + PlayerItemConsumeEvent.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!CustomItemRegistry.isCustomItem(item, CustomItemRegistry.GOLDEN_HEAD_ID)) return;
        // Block placement only — eating still happens via the consumable component.
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack eaten = e.getItem();
        if (!CustomItemRegistry.isCustomItem(eaten, CustomItemRegistry.GOLDEN_HEAD_ID)) return;

        Player player = e.getPlayer();

        // Cooldown gate — if still cooling down, block the consume entirely.
        if (player.hasCooldown(Material.PLAYER_HEAD)) {
            e.setCancelled(true);
            return;
        }

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

        player.setCooldown(Material.PLAYER_HEAD, cooldownSec * 20);

        // Vanilla will consume one from the eaten stack (main or off hand)
        // automatically because it has a food component, so we do NOT decrement
        // it ourselves — doing so would remove two.

        player.sendMessage(plugin.getConfigManager().rawMsg("custom-item.golden-head-use",
                "{regen_sec}", String.valueOf(regenSec),
                "{abs_sec}", String.valueOf(absSec)));
    }
}
