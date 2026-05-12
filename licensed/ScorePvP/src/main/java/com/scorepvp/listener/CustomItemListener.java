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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Golden Head — instant use on right-click.
 *
 * Config-driven values from custom-items.golden-head in config.yml:
 *   cooldown-seconds, effects.regeneration.amplifier/duration-seconds,
 *   effects.absorption.amplifier/duration-seconds
 *
 * No eating animation — effects are applied immediately on right-click.
 * Item amount decreases by 1 on use.
 */
public class CustomItemListener implements Listener {

    private final ScorePvP plugin;

    public CustomItemListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = e.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!CustomItemRegistry.isCustomItem(held, CustomItemRegistry.GOLDEN_HEAD_ID)) return;

        // Check cooldown
        if (player.hasCooldown(Material.PLAYER_HEAD)) return;

        e.setCancelled(true);

        // Read values from config
        var cfg = plugin.getConfig();
        int regenAmp    = cfg.getInt("custom-items.golden-head.effects.regeneration.amplifier", 2);
        int regenSec    = cfg.getInt("custom-items.golden-head.effects.regeneration.duration-seconds", 5);
        int absAmp      = cfg.getInt("custom-items.golden-head.effects.absorption.amplifier", 0);
        int absSec      = cfg.getInt("custom-items.golden-head.effects.absorption.duration-seconds", 120);
        int cooldownSec = cfg.getInt("custom-items.golden-head.cooldown-seconds", 10);

        // Apply effects instantly
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION, regenSec * 20, regenAmp, false, true, true));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.ABSORPTION, absSec * 20, absAmp, false, true, true));

        // Set cooldown
        player.setCooldown(Material.PLAYER_HEAD, cooldownSec * 20);

        // Consume 1 from the stack
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        player.updateInventory();

        // Feedback message
        player.sendMessage(plugin.getConfigManager().rawMsg("custom-item.golden-head-use",
                "{regen_sec}", String.valueOf(regenSec),
                "{abs_sec}", String.valueOf(absSec)));
    }
}
