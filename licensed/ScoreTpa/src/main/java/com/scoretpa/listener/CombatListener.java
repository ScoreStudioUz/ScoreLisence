package com.scoretpa.listener;

import com.scoretpa.ScoreTpa;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public class CombatListener implements Listener {

    private final ScoreTpa plugin;

    public CombatListener(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfigManager().isCancelOnCombat()) return;
        if (event.getFinalDamage() <= 0) return;

        Player attacker = getAttacker(event);
        if (attacker == null) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player victim = (Player) event.getEntity();
        if (attacker.equals(victim)) return;

        if (plugin.getTeleportManager().isTeleporting(attacker.getUniqueId())) {
            plugin.getTeleportManager().cancelTeleport(attacker.getUniqueId(), "teleport-cancelled-combat");
        }

        if (plugin.getTeleportManager().isTeleporting(victim.getUniqueId())) {
            plugin.getTeleportManager().cancelTeleport(victim.getUniqueId(), "teleport-cancelled-combat");
        }
    }

    private Player getAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            return (Player) event.getDamager();
        }
        if (event.getDamager() instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) event.getDamager()).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }
}
