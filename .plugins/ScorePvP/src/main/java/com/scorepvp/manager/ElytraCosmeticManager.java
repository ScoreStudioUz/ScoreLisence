package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The lobby elytra + wind-charge cosmetic.
 *
 * <p>When enabled, a player standing in the lobby is given an unbreakable elytra
 * in the chestplate slot and a single, never-depleting wind-charge in the
 * off-hand — so they can hop around spawn. The cosmetic is purely lobby-side:
 * {@link com.scorepvp.manager.HotbarManager} only calls {@link #apply(Player)}
 * from the lobby loadout path, and any match/queue/party loadout wipes the
 * inventory first, so it can never leak into a duel or another hotbar.
 *
 * <p>The chosen state lives in {@link PlayerSettings#isElytraCosmetic()} and
 * persists in player_settings.elytra_cos.
 */
public class ElytraCosmeticManager {

    private final ScorePvP plugin;

    public ElytraCosmeticManager(ScorePvP plugin) { this.plugin = plugin; }

    /** Flips the toggle, persists it, and (re)applies the lobby loadout. Returns the new state. */
    public boolean toggle(Player p) {
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        if (st == null) return false;
        boolean now = !st.isElytraCosmetic();
        st.setElytraCosmetic(now);
        plugin.getSettingsManager().persist(p.getUniqueId());
        if (now) apply(p);
        else remove(p);
        return now;
    }

    /** True if the player has the cosmetic enabled and may use it. */
    public boolean isEnabled(Player p) {
        if (!p.hasPermission(com.scorepvp.gui.CosmeticsGUI.PERM_ELYTRA)) return false;
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        return st != null && st.isElytraCosmetic();
    }

    /** True only when the player is in the lobby (no match / no queue / no party). */
    public boolean inLobby(Player p) {
        if (plugin.getMatchManager() != null && plugin.getMatchManager().isInMatch(p.getUniqueId())) return false;
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInQueue(p.getUniqueId())) return false;
        if (plugin.getPartyManager() != null && plugin.getPartyManager().isInParty(p.getUniqueId())) return false;
        return true;
    }

    /**
     * Applies the elytra + wind-charge to a lobby player. No-op if the cosmetic
     * is off or the player isn't in the lobby.
     */
    public void apply(Player p) {
        if (!isEnabled(p) || !inLobby(p)) return;
        p.getInventory().setChestplate(elytra());
        p.getInventory().setItemInOffHand(windCharge());
        p.updateInventory();
    }

    /** Removes the cosmetic (chestplate + offhand wind-charge). */
    public void remove(Player p) {
        if (p.getInventory().getChestplate() != null
                && p.getInventory().getChestplate().getType() == Material.ELYTRA) {
            p.getInventory().setChestplate(null);
        }
        if (p.getInventory().getItemInOffHand().getType() == Material.WIND_CHARGE) {
            p.getInventory().setItemInOffHand(null);
        }
        p.updateInventory();
    }

    /** Refills the off-hand back to exactly one wind-charge (called after a throw). */
    public void refillWindCharge(Player p) {
        if (!isEnabled(p) || !inLobby(p)) return;
        p.getInventory().setItemInOffHand(windCharge());
        p.updateInventory();
    }

    // ── Items ────────────────────────────────────────────────────────────────

    private ItemStack elytra() {
        ItemStack it = new ItemStack(Material.ELYTRA);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setUnbreakable(true);
            m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
            String name = plugin.getConfigManager().cosStr("elytra.item-name");
            if (name != null && !name.isEmpty())
                m.displayName(com.scorepvp.util.ColorUtil.component(name));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack windCharge() {
        ItemStack it = new ItemStack(Material.WIND_CHARGE, 1);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            String name = plugin.getConfigManager().cosStr("elytra.windcharge-name");
            if (name != null && !name.isEmpty())
                m.displayName(com.scorepvp.util.ColorUtil.component(name));
            it.setItemMeta(m);
        }
        return it;
    }
}
