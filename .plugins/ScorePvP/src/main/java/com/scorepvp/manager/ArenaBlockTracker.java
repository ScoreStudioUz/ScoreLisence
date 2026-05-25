package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every block changed inside an active vanilla arena so we can restore
 * it in a single tick at the end of the round — no schematic paste, no
 * "blocks-per-tick" timer loop, no chunk-network spam.
 * <p>
 * Why this exists: the previous restore implementation re-applied the entire
 * snapshot (every block of the cuboid) at 500 blocks/tick. For a 20×20×20
 * arena that's 8000 setBlockData() calls spread over 16 ticks — chunk
 * packets explode, players' pings spike, the server stutters. By recording
 * only the diff, we usually have to revert &lt;50 blocks per round, which is
 * trivial to flush in one synchronous burst.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>On BlockPlace/Break inside an arena cuboid of a live match — record
 *       the original BlockData at that coord <em>once</em> (first change wins,
 *       subsequent changes are ignored because we already know the original).</li>
 *   <li>On {@link #restore(Match)} — iterate the diff, call
 *       {@code setBlockData(orig, false)} which skips physics + neighbour
 *       light updates, then clear the diff.</li>
 *   <li>Falls back to {@link ArenaRestoreManager} only if no diff exists
 *       (e.g. server restart during a match) — the schematic paste still
 *       guarantees correctness.</li>
 * </ul>
 */
public class ArenaBlockTracker implements Listener {

    private final ScorePvP plugin;

    /** matchId → ordered map of (x,y,z packed long) → original BlockData. */
    private final Map<UUID, Map<Long, BlockData>> diffs = new ConcurrentHashMap<>();

    public ArenaBlockTracker(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Listener ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        recordIfTracked(e.getPlayer(), e.getBlock(), /* place */ false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        // Use BlockReplacedState which holds the data BEFORE the place.
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        Match match = matchFor(p);
        if (match == null) return;
        Arena arena = match.getArena();
        if (arena == null || !arena.isVanilla()) return;
        if (!isInside(arena, b.getLocation())) return;

        long key = packKey(b.getX(), b.getY(), b.getZ());
        diffs.computeIfAbsent(match.getMatchId(), k -> new LinkedHashMap<>())
                .putIfAbsent(key, e.getBlockReplacedState().getBlockData().clone());
    }

    private void recordIfTracked(Player p, Block b, boolean place) {
        Match match = matchFor(p);
        if (match == null) return;
        Arena arena = match.getArena();
        if (arena == null || !arena.isVanilla()) return;
        if (!isInside(arena, b.getLocation())) return;

        long key = packKey(b.getX(), b.getY(), b.getZ());
        diffs.computeIfAbsent(match.getMatchId(), k -> new LinkedHashMap<>())
                .putIfAbsent(key, b.getBlockData().clone());
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Reverts every block change made during {@code match} in a single tick.
     * Returns the number of blocks actually reverted (0 when no changes
     * happened — the caller can skip the restore countdown entirely).
     */
    public int restore(Match match) {
        Map<Long, BlockData> diff = diffs.remove(match.getMatchId());
        if (diff == null || diff.isEmpty()) return 0;

        Arena arena = match.getArena();
        if (arena == null || arena.getCorner1() == null) return 0;
        World world = arena.getCorner1().getWorld();
        if (world == null) return 0;

        int restored = 0;
        for (Map.Entry<Long, BlockData> e : diff.entrySet()) {
            long k = e.getKey();
            int x = unpackX(k);
            int y = unpackY(k);
            int z = unpackZ(k);
            // applyPhysics=false → no neighbour updates, no light recalc.
            // Chunk gets one batched packet per block, but for &lt;100 blocks
            // it's invisible to the client.
            world.getBlockAt(x, y, z).setBlockData(e.getValue(), false);
            restored++;
        }
        return restored;
    }

    /** Clears any diff for this match without applying it. */
    public void discard(UUID matchId) {
        diffs.remove(matchId);
    }

    public boolean hasChanges(UUID matchId) {
        Map<Long, BlockData> d = diffs.get(matchId);
        return d != null && !d.isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Match matchFor(Player p) {
        return plugin.getMatchManager().getMatch(p.getUniqueId());
    }

    private boolean isInside(Arena arena, Location loc) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null) return false;
        if (loc.getWorld() == null || !loc.getWorld().equals(c1.getWorld())) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    // Pack three signed 21-bit coords into a long. Range ±1_048_575 — way more
    // than vanilla world coords need.
    private long packKey(int x, int y, int z) {
        long lx = ((long) x) & 0x1FFFFFL;
        long ly = ((long) y) & 0x1FFFFFL;
        long lz = ((long) z) & 0x1FFFFFL;
        return (lx << 42) | (ly << 21) | lz;
    }
    private int unpackX(long k) { return signExtend21((int) ((k >> 42) & 0x1FFFFFL)); }
    private int unpackY(long k) { return signExtend21((int) ((k >> 21) & 0x1FFFFFL)); }
    private int unpackZ(long k) { return signExtend21((int) ( k        & 0x1FFFFFL)); }
    private int signExtend21(int v) { return (v & 0x100000) != 0 ? v | 0xFFE00000 : v; }

    /** Test hook — unused at runtime, kept so we don't have to import Bukkit elsewhere. */
    @SuppressWarnings("unused")
    private static void unused() { Bukkit.getServer(); }
}
