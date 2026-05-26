package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Duration;

// WorldEdit API imports
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;

/**
 * Restores arenas after each round.
 *
 * <h2>How restore works now</h2>
 * Everything funnels through {@link ArenaBlockTracker}. It records every
 * block change a player makes (place/break/explosion/bucket/ignite/arrow)
 * during the round, and at round end we replay the inverse in one tick.
 * That's why we don't keep a saved "baseline" anywhere — the diff is
 * authoritative.
 *
 * <h2>What's still here</h2>
 * <ul>
 *   <li>{@link #saveSchematic} / {@link #restoreFromSchematic} — kept as
 *       optional backup using WorldEdit. Admins can run {@code /arena N save}
 *       to write a {@code .schem} file, and {@link #manualRestore} can paste
 *       it back if something goes wrong. Round-end never uses this path.</li>
 *   <li>An optional countdown title — used only when there's no tracker
 *       (effectively never, but keep the niceness in case).</li>
 * </ul>
 *
 * <h2>What's gone</h2>
 * The {@code arena_snapshots} SQLite table, the block-by-block scan-and-save,
 * the per-tick block-paste loop. All of it. That table grew to 500MB on a
 * 24-arena server because every snapshot saved 20×20×20 ≈ 8000 rows. The
 * tracker handles the same job in zero stored bytes.
 */
public class ArenaRestoreManager {

    private final ScorePvP plugin;

    /** Whether WorldEdit or FAWE is available on this server */
    private final boolean hasWorldEdit;

    /** Directory where .schem files are stored — optional backup, not used at round end */
    private final File schematicsDir;

    public ArenaRestoreManager(ScorePvP plugin) {
        this.plugin = plugin;
        this.hasWorldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        this.schematicsDir = new File(plugin.getDataFolder(), "schematics");
        if (!schematicsDir.exists()) schematicsDir.mkdirs();
        if (hasWorldEdit) {
            plugin.getLogger().info("[ScorePvP] WorldEdit/FAWE detected — schematic backup available.");
        } else {
            plugin.getLogger().info("[ScorePvP] WorldEdit/FAWE not found — only tracker-based restore available.");
        }
    }

    // ── WorldEdit schematic helpers ──────────────────────────────────────────

    private File getSchematicFile(int arenaNumber) {
        return new File(schematicsDir, "arena_" + arenaNumber + ".schem");
    }

    /** Saves the arena region as a {@code .schem} file. Main thread. */
    public void saveSchematic(Arena arena, Player admin) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) {
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.no-corners", "{number}", String.valueOf(arena.getNumber())));
            return;
        }

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(c1.getWorld());
        BlockVector3 min = BlockVector3.at(minX, minY, minZ);
        BlockVector3 max = BlockVector3.at(maxX, maxY, maxZ);
        CuboidRegion region = new CuboidRegion(weWorld, min, max);
        BlockVector3 origin = min;

        try {
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(origin);

            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(
                        editSession, region, clipboard, origin);
                copy.setCopyingEntities(false);
                Operations.complete(copy);
            }

            File file = getSchematicFile(arena.getNumber());
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
                    .getWriter(new FileOutputStream(file))) {
                writer.write(clipboard);
            }

            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.schematic-saved",
                    "{number}", String.valueOf(arena.getNumber()),
                    "{file}", file.getName()));
        } catch (Exception e) {
            plugin.getLogger().severe("[ScorePvP] Failed to save schematic for arena #"
                    + arena.getNumber() + ": " + e.getMessage());
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.schematic-error", "{error}", e.getMessage()));
        }
    }

    /** Pastes the saved {@code .schem} back over the arena region. Main thread. */
    public void restoreFromSchematic(Arena arena, Runnable onComplete) {
        File file = getSchematicFile(arena.getNumber());
        if (!file.exists()) {
            plugin.getLogger().warning("[ScorePvP] No schematic file for arena #"
                    + arena.getNumber() + " — nothing to paste.");
            onComplete.run();
            return;
        }

        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) { onComplete.run(); return; }

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        BlockVector3 origin = BlockVector3.at(minX, minY, minZ);

        try {
            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                plugin.getLogger().warning("[ScorePvP] Unknown schematic format: " + file.getName());
                onComplete.run();
                return;
            }

            Clipboard clipboard;
            try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
                clipboard = reader.read();
            }

            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(c1.getWorld());
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(origin)
                        .ignoreAirBlocks(false)
                        .copyEntities(false)
                        .build();
                Operations.complete(operation);
            }
            onComplete.run();
        } catch (Exception e) {
            plugin.getLogger().severe("[ScorePvP] Failed to paste schematic for arena #"
                    + arena.getNumber() + ": " + e.getMessage());
            onComplete.run();
        }
    }

    // ── Admin save: just writes a .schem if WorldEdit is present ────────────

    /**
     * {@code /arena N save} entrypoint. Saves a schematic backup if WorldEdit
     * is available; otherwise tells the admin that round-end restore still
     * works via the tracker so they don't need to do anything.
     */
    public int scanAndSave(Arena arena, Player admin) {
        if (hasWorldEdit) {
            saveSchematic(arena, admin);
            return 1;
        }
        if (admin != null) {
            admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.tracker-only",
                    "{number}", String.valueOf(arena.getNumber())));
        }
        return 0;
    }

    // ── Round end ───────────────────────────────────────────────────────────

    /**
     * Called after a round ends. Always goes through the tracker — that's
     * the diff-based path and it finishes in a single tick.
     */
    public void startRestore(Match match, Runnable onComplete) {
        match.setState(Match.State.RESTORING);

        Arena arena = match.getArena();
        ArenaBlockTracker tracker = plugin.getArenaBlockTracker();

        if (tracker == null) {
            // Should never happen — tracker is wired in onEnable — but bail safely.
            plugin.getLogger().warning("[ScorePvP] ArenaBlockTracker missing; round restore skipped.");
            onComplete.run();
            return;
        }

        if (!tracker.hasChanges(match.getMatchId())) {
            onComplete.run();
            return;
        }

        int reverted = tracker.restore(match);
        if (reverted > 0) {
            plugin.getLogger().fine("[ScorePvP] Arena #" + arena.getNumber()
                    + " restored " + reverted + " block(s) via diff tracker.");
        }
        onComplete.run();
    }

    /** Visible only if the round-end restore path ever needs a countdown. Unused today. */
    private void showRestoreCountdown(Match match, int totalTicks, Runnable afterCountdown) {
        int[] seconds = {totalTicks / 20};
        int[] ticks   = {0};

        BukkitTask[] taskRef = {null};
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ticks[0] >= totalTicks) {
                taskRef[0].cancel();
                afterCountdown.run();
                return;
            }
            if (ticks[0] % 20 == 0 && seconds[0] > 0) {
                int s = seconds[0];
                String title = s <= 1 ? "&e1" : "&e" + s;
                String sub   = plugin.getConfigManager().rawStr("arena.restore-subtitle");
                Title t = Title.title(
                        comp(title), comp(sub),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO));
                broadcastTitle(match, t);
                seconds[0]--;
            }
            ticks[0]++;
        }, 0L, 1L);
    }

    // ── Manual /arena N restore command ─────────────────────────────────────

    /**
     * Admin command path. Tries a schematic paste first (if WorldEdit and a
     * saved {@code .schem} are present); otherwise reports that there's no
     * baseline to restore from — the tracker only persists state during a
     * live match.
     */
    public void manualRestore(Arena arena, Player admin) {
        if (hasWorldEdit && getSchematicFile(arena.getNumber()).exists()) {
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.restoring", "{number}", String.valueOf(arena.getNumber())));
            restoreFromSchematic(arena, () -> {
                if (admin != null) admin.sendMessage(plugin.getConfigManager().msg(
                        "arena.restored", "{number}", String.valueOf(arena.getNumber())));
            });
            return;
        }
        if (admin != null) {
            admin.sendMessage(plugin.getConfigManager().msg(
                    "arena.no-snapshot", "{number}", String.valueOf(arena.getNumber())));
        }
    }

    // ── Util ────────────────────────────────────────────────────────────────

    private void broadcastTitle(Match match, Title title) {
        match.getAllUuids().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.showTitle(title);
        });
    }

    private net.kyori.adventure.text.Component comp(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
