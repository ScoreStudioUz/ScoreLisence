package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-kit ender-chest storage (27 slots) that rides along with each vanilla
 * multi-kit. Two open modes:
 *
 * <ul>
 *   <li>{@code EDIT} — opened in the kit-editor room. The player arranges the box
 *       and on close it's persisted to the kit being edited. This is the loadout
 *       their ender chest holds in matches.</li>
 *   <li>{@code PLAY} — opened during a vanilla match by right-clicking an ender
 *       chest. It's a fresh CLONE of the saved contents, so taking items out is
 *       allowed but never mutates the saved kit — every round starts from the
 *       saved state again (the per-round restore).</li>
 * </ul>
 *
 * The title carries a mode marker so the listener can tell EDIT from PLAY when
 * routing clicks / handling close.
 */
public class EnderKitGUI {

    public enum Mode { EDIT, PLAY }

    private final ScorePvP plugin;

    private String editTitle;
    private String playTitle;

    /** uuid → the kit index whose ender box is being EDITED (absent in PLAY). */
    private final Map<UUID, Integer> editingKit = new ConcurrentHashMap<>();
    /** uuid → current open mode (absent = not open). */
    private final Map<UUID, Mode> openMode = new ConcurrentHashMap<>();
    /**
     * uuid → the live PLAY inventory for the current round. Persists across
     * re-opens within a round (so it acts like a real ender chest — pulling an
     * item out and re-opening doesn't refill it), and is dropped by
     * {@link #resetPlay} at each round start so the next open rebuilds it from
     * the saved kit contents (the per-round restore).
     */
    private final Map<UUID, Inventory> playInv = new ConcurrentHashMap<>();

    public EnderKitGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File f = new File(plugin.getDataFolder(), "item_menus/ender_kit.yml");
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        editTitle = ColorUtil.color(cfg.getString("edit-title", "&8Ender Sandiq › Tahrir"));
        playTitle = ColorUtil.color(cfg.getString("play-title", "&8Ender Sandiq"));
    }

    // ── Routing ────────────────────────────────────────────────────────────────

    public String editTitle() { return editTitle; }
    public String playTitle() { return playTitle; }

    public boolean isThisGUI(String title) {
        return title.equals(editTitle) || title.equals(playTitle);
    }

    public Mode modeOf(UUID uuid) { return openMode.get(uuid); }

    // ── Open ───────────────────────────────────────────────────────────────────

    /** Opens the ender box in EDIT mode for the kit the player is editing in the room. */
    public void openEdit(Player p, int kitIndex) {
        ItemStack[] stored = plugin.getVanillaKitManager().getEnderItems(p.getUniqueId(), kitIndex);
        Inventory inv = Bukkit.createInventory(null, com.scorepvp.manager.VanillaKitManager.ENDER_SLOTS, editTitle);
        if (stored != null) {
            for (int i = 0; i < stored.length && i < inv.getSize(); i++) inv.setItem(i, stored[i]);
        }
        editingKit.put(p.getUniqueId(), kitIndex);
        openMode.put(p.getUniqueId(), Mode.EDIT);
        p.openInventory(inv);
    }

    /**
     * Opens the SELECTED kit's ender box in PLAY mode (a match). The FIRST open of
     * a round builds a live inventory seeded from the saved kit contents; later
     * opens in the SAME round reuse it, so it behaves like a real ender chest
     * (pulling an item out and re-opening does not refill it). {@link #resetPlay}
     * clears it at each round start, giving the per-round restore.
     */
    public void openPlay(Player p) {
        Inventory inv = playInv.get(p.getUniqueId());
        if (inv == null) {
            inv = Bukkit.createInventory(null, com.scorepvp.manager.VanillaKitManager.ENDER_SLOTS, playTitle);
            ItemStack[] stored = plugin.getVanillaKitManager().getSelectedEnderItems(p.getUniqueId());
            if (stored != null) {
                for (int i = 0; i < stored.length && i < inv.getSize(); i++) inv.setItem(i, stored[i]);
            }
            playInv.put(p.getUniqueId(), inv);
        }
        openMode.put(p.getUniqueId(), Mode.PLAY);
        p.openInventory(inv);
    }

    /**
     * Drops the player's live PLAY box so the next open rebuilds it from the saved
     * kit. Call at each vanilla round start (and match end) for the per-round reset.
     */
    public void resetPlay(UUID uuid) { playInv.remove(uuid); }

    // ── Close ───────────────────────────────────────────────────────────────────

    /**
     * Called on inventory close. In EDIT mode the box contents are persisted to the
     * kit being edited; in PLAY mode nothing is saved.
     */
    public void handleClose(Player p, Inventory inv) {
        Mode mode = openMode.remove(p.getUniqueId());
        if (mode == Mode.EDIT) {
            Integer idx = editingKit.remove(p.getUniqueId());
            if (idx != null) {
                ItemStack[] contents = new ItemStack[com.scorepvp.manager.VanillaKitManager.ENDER_SLOTS];
                for (int i = 0; i < contents.length && i < inv.getSize(); i++) contents[i] = inv.getItem(i);
                plugin.getVanillaKitManager().saveEnderItems(p.getUniqueId(), idx, contents);
            }
        }
    }

    /** Drops all transient state for a player (quit). */
    public void clear(UUID uuid) {
        editingKit.remove(uuid);
        openMode.remove(uuid);
        playInv.remove(uuid);
    }
}
