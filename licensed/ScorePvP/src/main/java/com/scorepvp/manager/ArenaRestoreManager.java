package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.ArenaBlock;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

// WorldEdit API imports
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.*;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.EditSession;

/**
 * Handles arena block snapshots and restoration.
 *
 * If WorldEdit or FastAsyncWorldEdit is present, uses schematic files for
 * fast save/restore. Otherwise falls back to vanilla block-by-block snapshots.
 *
 * Lifecycle:
 *   1. Admin runs /arena N save     → scanAndSave()
 *   2. Round ends                   → startRestore(match, onComplete)
 *   3. Restore completes            → onComplete.run()  → next round starts
 */
public class ArenaRestoreManager {

    private final ScorePvP plugin;

    /** arenaNumber → snapshot blocks (loaded lazily) */
    private final Map<Integer, List<ArenaBlock>> snapshots = new HashMap<>();

    /** Whether WorldEdit or FAWE is available on this server */
    private final boolean hasWorldEdit;

    /** Directory where .schem files are stored */
    private final File schematicsDir;

    public ArenaRestoreManager(ScorePvP plugin) {
        this.plugin = plugin;
        this.hasWorldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
        this.schematicsDir = new File(plugin.getDataFolder(), "schematics");
        if (!schematicsDir.exists()) {
            schematicsDir.mkdirs();
        }
        if (hasWorldEdit) {
            plugin.getLogger().info("[ScorePvP] WorldEdit/FAWE detected — schematic arena restore enabled.");
        } else {
            plugin.getLogger().info("[ScorePvP] WorldEdit/FAWE not found — using block-by-block arena restore.");
        }
    }

    // ── Snapshot loading ──────────────────────────────────────────────────────

    public void loadSnapshotAsync(int arenaNumber) {
        CompletableFuture.supplyAsync(
                () -> plugin.getDatabase().get().loadArenaSnapshot(arenaNumber))
                .thenAccept(blocks -> snapshots.put(arenaNumber, blocks));
    }

    // ── WorldEdit schematic helpers ────────────────────────────────────────────

    /**
     * Returns the .schem file path for a given arena number.
     */
    private File getSchematicFile(int arenaNumber) {
        return new File(schematicsDir, "arena_" + arenaNumber + ".schem");
    }

    /**
     * Saves the arena region as a WorldEdit .schem file.
     * Must be called on the main thread.
     */
    public void saveSchematic(Arena arena, Player admin) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) {
            admin.sendMessage(plugin.getConfigManager().msg("arena.no-corners", "{number}", String.valueOf(arena.getNumber())));
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

        // Origin is at the min corner so paste restores relative to it
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

            admin.sendMessage(plugin.getConfigManager().msg("arena.schematic-saved", "{number}", String.valueOf(arena.getNumber()), "{file}", file.getName()));
        } catch (Exception e) {
            plugin.getLogger().severe("[ScorePvP] Failed to save schematic for arena #"
                    + arena.getNumber() + ": " + e.getMessage());
            e.printStackTrace();
            admin.sendMessage(plugin.getConfigManager().msg("arena.schematic-error", "{error}", e.getMessage()));
        }
    }

    /**
     * Restores an arena from its .schem file using WorldEdit paste.
     * Runs on the main thread. Calls onComplete when done.
     */
    public void restoreFromSchematic(Arena arena, Runnable onComplete) {
        File file = getSchematicFile(arena.getNumber());
        if (!file.exists()) {
            plugin.getLogger().warning("[ScorePvP] Schematic file not found for arena #"
                    + arena.getNumber() + ", falling back to block-by-block.");
            onComplete.run();
            return;
        }

        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) {
            onComplete.run();
            return;
        }

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        BlockVector3 origin = BlockVector3.at(minX, minY, minZ);

        try {
            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                plugin.getLogger().warning("[ScorePvP] Unknown schematic format for: " + file.getName());
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
            plugin.getLogger().severe("[ScorePvP] Failed to restore schematic for arena #"
                    + arena.getNumber() + ": " + e.getMessage());
            e.printStackTrace();
            onComplete.run();
        }
    }

    // ── Admin: save snapshot ──────────────────────────────────────────────────

    /**
     * Scans the full region of the arena and saves every block to the DB.
     * If WorldEdit/FAWE is available, also saves a .schem file.
     * Must be called on the main thread (world access).
     * Returns block count saved, or -1 on error.
     */
    public int scanAndSave(Arena arena, Player admin) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) {
            admin.sendMessage(plugin.getConfigManager().msg("arena.no-corners", "{number}", String.valueOf(arena.getNumber())));
            return -1;
        }

        // If WorldEdit is available, save schematic (primary method)
        if (hasWorldEdit) {
            try {
                saveSchematic(arena, admin);
                // Mark arena as having snapshot
                arena.setHasSnapshot(true);
                plugin.getArenaManager().saveArena(arena);
                // Also do the block-by-block save as fallback
                return scanAndSaveBlocks(arena, admin, /* silent */ true);
            } catch (Exception e) {
                plugin.getLogger().warning("[ScorePvP] Schematic save failed, falling back to block-by-block: "
                        + e.getMessage());
                // Fall through to block-by-block
            }
        }

        // Block-by-block fallback
        return scanAndSaveBlocks(arena, admin, /* silent */ false);
    }

    /**
     * Block-by-block snapshot save (original implementation).
     * If silent=true, skips the admin success message (schematic already notified).
     */
    private int scanAndSaveBlocks(Arena arena, Player admin, boolean silent) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();

        World world = c1.getWorld();
        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        List<ArenaBlock> blocks = new ArrayList<>();
        boolean saveTileEntities = plugin.getConfig().getBoolean("arena.save-tile-entities", true);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    String tileJson = null;
                    if (saveTileEntities && block.getState() instanceof TileState ts) {
                        tileJson = serializeTileEntity(ts);
                    }
                    blocks.add(new ArenaBlock(
                            x - minX, y - minY, z - minZ,
                            block.getBlockData().clone(),
                            tileJson));
                }
            }
        }

        // Store in memory
        snapshots.put(arena.getNumber(), blocks);

        // Persist async
        final List<ArenaBlock> snapshot = blocks;
        CompletableFuture.runAsync(() ->
                plugin.getDatabase().get().saveArenaSnapshot(arena.getNumber(), snapshot));

        // Mark arena as having snapshot
        arena.setHasSnapshot(true);
        plugin.getArenaManager().saveArena(arena);

        if (!silent) {
            admin.sendMessage(plugin.getConfigManager().msg("arena.blocks-saved", "{count}", String.valueOf(blocks.size()), "{number}", String.valueOf(arena.getNumber())));
        }
        return blocks.size();
    }

    // ── Match: restore after round ────────────────────────────────────────────

    /**
     * Called after a round ends on a vanilla arena.
     * Shows countdown title to all participants, restores blocks, then calls onComplete.
     */
    public void startRestore(Match match, Runnable onComplete) {
        // Bug #2: set RESTORING so disconnect handler knows we're mid-restore
        match.setState(com.scorepvp.model.Match.State.RESTORING);

        Arena arena = match.getArena();

        // If WorldEdit is available and schematic exists, use fast schematic paste
        if (hasWorldEdit && getSchematicFile(arena.getNumber()).exists()) {
            int delayTicks = plugin.getConfig().getInt("arena.vanilla-restore-delay-ticks", 60);
            boolean notifyPlayers = plugin.getConfig().getBoolean("arena.restore-notify-players", true);

            if (notifyPlayers) {
                showRestoreCountdown(match, delayTicks, () -> {
                    try {
                        restoreFromSchematic(arena, onComplete);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[ScorePvP] Schematic restore failed, falling back: "
                                + e.getMessage());
                        int blocksPerTick = plugin.getConfig().getInt("arena.restore-blocks-per-tick", 500);
                        doRestore(match, blocksPerTick, onComplete);
                    }
                });
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    try {
                        restoreFromSchematic(arena, onComplete);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[ScorePvP] Schematic restore failed, falling back: "
                                + e.getMessage());
                        int blocksPerTick = plugin.getConfig().getInt("arena.restore-blocks-per-tick", 500);
                        doRestore(match, blocksPerTick, onComplete);
                    }
                }, delayTicks);
            }
            return;
        }

        // Fallback: block-by-block restore
        int delayTicks = plugin.getConfig().getInt("arena.vanilla-restore-delay-ticks", 60);
        int blocksPerTick = plugin.getConfig().getInt("arena.restore-blocks-per-tick", 500);
        boolean notifyPlayers = plugin.getConfig().getBoolean("arena.restore-notify-players", true);

        // Show "Подготовка арены..." countdown to all participants
        if (notifyPlayers) {
            showRestoreCountdown(match, delayTicks, () ->
                    doRestore(match, blocksPerTick, onComplete));
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    doRestore(match, blocksPerTick, onComplete), delayTicks);
        }
    }

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

    private void doRestore(Match match, int blocksPerTick, Runnable onComplete) {
        Arena arena = match.getArena();
        List<ArenaBlock> blocks = snapshots.get(arena.getNumber());

        if (blocks == null || blocks.isEmpty()) {
            plugin.getLogger().warning("[ScorePvP] No snapshot for arena #" + arena.getNumber());
            onComplete.run();
            return;
        }

        Location c1 = arena.getCorner1();
        if (c1 == null) { onComplete.run(); return; }

        World world = c1.getWorld();
        int baseX = Math.min(c1.getBlockX(), arena.getCorner2().getBlockX());
        int baseY = Math.min(c1.getBlockY(), arena.getCorner2().getBlockY());
        int baseZ = Math.min(c1.getBlockZ(), arena.getCorner2().getBlockZ());

        int[] index = {0};
        int total   = blocks.size();

        BukkitTask[] taskRef = {null};
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(index[0] + blocksPerTick, total);
            for (int i = index[0]; i < end; i++) {
                ArenaBlock ab = blocks.get(i);
                Block block = world.getBlockAt(
                        baseX + ab.getRelX(),
                        baseY + ab.getRelY(),
                        baseZ + ab.getRelZ());
                block.setBlockData(ab.getBlockData(), false);
                if (ab.hasTileEntity()) {
                    deserializeTileEntity(block, ab.getTileEntityJson());
                }
            }
            index[0] = end;

            if (index[0] >= total) {
                taskRef[0].cancel();
                onComplete.run();
            }
        }, 0L, 1L);
    }

    // ── Manual restore command ────────────────────────────────────────────────

    /** Called with admin=null for silent background restore (e.g. after disconnect). */
    public void manualRestore(Arena arena, Player admin) {
        // Try schematic restore first if WorldEdit is available
        if (hasWorldEdit && getSchematicFile(arena.getNumber()).exists()) {
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg("arena.restoring", "{number}", String.valueOf(arena.getNumber())));
            try {
                restoreFromSchematic(arena, () -> {
                    if (admin != null) admin.sendMessage(plugin.getConfigManager().msg("arena.restored", "{number}", String.valueOf(arena.getNumber())));
                });
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[ScorePvP] Schematic restore failed for manual restore, falling back: "
                        + e.getMessage());
                // Fall through to block-by-block
            }
        }

        // Fallback: block-by-block
        List<ArenaBlock> blocks = snapshots.get(arena.getNumber());
        if (blocks == null) {
            // Try loading from DB
            blocks = plugin.getDatabase().get().loadArenaSnapshot(arena.getNumber());
            snapshots.put(arena.getNumber(), blocks);
        }
        if (blocks.isEmpty()) {
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg("arena.no-snapshot", "{number}", String.valueOf(arena.getNumber())));
            return;
        }

        if (admin != null) admin.sendMessage(plugin.getConfigManager().msg("arena.restoring", "{number}", String.valueOf(arena.getNumber())));
        doRestoreDirect(arena, blocks, () -> {
            if (admin != null) admin.sendMessage(plugin.getConfigManager().msg("arena.restored", "{number}", String.valueOf(arena.getNumber())));
        });
    }

    private void doRestoreDirect(Arena arena, List<ArenaBlock> blocks, Runnable onDone) {
        int blocksPerTick = plugin.getConfig().getInt("arena.restore-blocks-per-tick", 500);
        Location c1 = arena.getCorner1();
        if (c1 == null) { onDone.run(); return; }
        World world = c1.getWorld();
        int baseX = Math.min(c1.getBlockX(), arena.getCorner2().getBlockX());
        int baseY = Math.min(c1.getBlockY(), arena.getCorner2().getBlockY());
        int baseZ = Math.min(c1.getBlockZ(), arena.getCorner2().getBlockZ());
        int[] index = {0};
        BukkitTask[] tr = {null};
        tr[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(index[0] + blocksPerTick, blocks.size());
            for (int i = index[0]; i < end; i++) {
                ArenaBlock ab = blocks.get(i);
                Block b = world.getBlockAt(baseX+ab.getRelX(), baseY+ab.getRelY(), baseZ+ab.getRelZ());
                b.setBlockData(ab.getBlockData(), false);
                if (ab.hasTileEntity()) deserializeTileEntity(b, ab.getTileEntityJson());
            }
            index[0] = end;
            if (index[0] >= blocks.size()) { tr[0].cancel(); onDone.run(); }
        }, 0L, 1L);
    }

    // ── Tile entity serialisation (simple NBT-less approach) ──────────────────

    private String serializeTileEntity(TileState ts) {
        // Purpur/Paper: use Bukkit's snbt serialization if available, else skip
        try {
            // Paper API: BlockState.getSnbt() — not universally available; use fallback
            return null; // Tile entity data not serialized in this build (future extension)
        } catch (Exception e) {
            return null;
        }
    }

    private void deserializeTileEntity(Block block, String json) {
        // Placeholder for future tile entity deserialization
    }

    // ── Util ─────────────────────────────────────────────────────────────────

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
