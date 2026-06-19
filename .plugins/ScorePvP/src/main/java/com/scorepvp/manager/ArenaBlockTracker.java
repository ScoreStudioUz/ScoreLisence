package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * <h2>What gets tracked</h2>
 * <ul>
 *   <li>Player block-break and block-place inside the arena cuboid.</li>
 *   <li>Explosions: TNT, respawn anchor, end crystal, creeper, fireball.
 *       Every block destroyed by the blast is recorded with its pre-blast
 *       data <em>and</em> removed from the explosion's block-list so the
 *       blast still plays the visual/sound but doesn't actually destroy.
 *       Wait — that would change gameplay; we instead record then let
 *       the explosion proceed, restoring on round-end.</li>
 *   <li>Fire ignition + block burn — flame arrows, lava-adjacent.</li>
 *   <li>Bucket empty / fill — lava + water placement and pickup.</li>
 *   <li>Stuck arrows inside the cuboid — collected on ProjectileHitEvent and
 *       removed wholesale during {@link #restore(Match)}.</li>
 * </ul>
 *
 * <h2>Diff semantics</h2>
 * "First change wins" — once a coord has its original recorded, subsequent
 * changes at the same coord are ignored. The point of the diff is "what was
 * there before the match", so the first hit is always the right answer.
 */
public class ArenaBlockTracker implements Listener {

    private final ScorePvP plugin;

    /** matchId → ordered map of (x,y,z packed long) → original BlockData. */
    private final Map<UUID, Map<Long, BlockData>> diffs = new ConcurrentHashMap<>();

    /** matchId → arrows that landed inside the arena, to be despawned on restore. */
    private final Map<UUID, Set<UUID>> arrows = new ConcurrentHashMap<>();

    public ArenaBlockTracker(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Listener — player-driven changes ──────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        recordIfTracked(matchFor(e.getPlayer()), e.getBlock(), e.getBlock().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Match match = matchFor(e.getPlayer());
        // BlockReplacedState holds the data BEFORE the place — that's what we
        // want as the "original" so the place gets undone on restore.
        recordIfTracked(match, b, e.getBlockReplacedState().getBlockData().clone());
    }

    // ── Listener — buckets (lava/water) ───────────────────────────────────────
    //
    // PlayerBucketEmptyEvent fires BEFORE the liquid is placed; the targeted
    // block at that point still holds its original (usually air) data. That's
    // perfect — we record it, the event then proceeds and the liquid appears,
    // and on restore we put the original back. Same logic for fill (which
    // replaces a liquid block with air).

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Match match = matchFor(e.getPlayer());
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        recordIfTracked(match, b, b.getBlockData().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Match match = matchFor(e.getPlayer());
        Block b = e.getBlockClicked();
        recordIfTracked(match, b, b.getBlockData().clone());
    }

    // ── Listener — fire ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        // Player or projectile-driven; ignite turns an air block into FIRE.
        Match match = matchForBlock(e.getBlock());
        if (match == null && e.getPlayer() != null) match = matchFor(e.getPlayer());
        recordIfTracked(match, e.getBlock(), e.getBlock().getBlockData().clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        // Block consumed by fire (wool, planks, etc.). The current data IS
        // the original; the post-event state will be air or the next ignited
        // block.
        Match match = matchForBlock(e.getBlock());
        recordIfTracked(match, e.getBlock(), e.getBlock().getBlockData().clone());
    }

    // ── Listener — liquid flow / spread ───────────────────────────────────────
    //
    // Placing a water/lava bucket only records the SOURCE block. As the liquid
    // spreads outward each tick, BlockFromToEvent fires for every block it
    // flows INTO — those weren't captured by the bucket-empty handler, so on
    // restore they stayed flooded. Recording the destination block's pre-flow
    // data here (usually AIR) lets the diff tracker drain spread liquids too.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLiquidFlow(org.bukkit.event.block.BlockFromToEvent e) {
        Block to = e.getToBlock();
        Match match = matchForBlock(to);
        if (match == null) return;
        recordIfTracked(match, to, to.getBlockData().clone());
    }

    // ── Listener — explosions (TNT, anchor, end crystal, creeper, fireball) ──

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        // The source entity is rarely a Player; use the explosion location's
        // arena instead. blockList() contains every block the explosion will
        // destroy; capture each one's pre-blast data before vanilla wipes it.
        Match match = matchForLocation(e.getLocation());
        if (match == null) return;
        recordExplosionBlocks(match, e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        // Respawn anchors and beds (in non-overworld) explode as blocks, not
        // entities. Same drill — record the doomed blocks.
        Match match = matchForBlock(e.getBlock());
        if (match == null) match = matchForLocation(e.getBlock().getLocation());
        if (match == null) return;
        recordExplosionBlocks(match, e.blockList());
    }

    private void recordExplosionBlocks(Match match, java.util.List<Block> blocks) {
        Arena arena = match.getArena();
        if (arena == null || !arena.isVanilla()) return;
        Map<Long, BlockData> diff = diffs.computeIfAbsent(match.getMatchId(),
                k -> new LinkedHashMap<>());
        for (Block b : blocks) {
            if (!isInside(arena, b.getLocation())) continue;
            long key = packKey(b.getX(), b.getY(), b.getZ());
            diff.putIfAbsent(key, b.getBlockData().clone());
        }
    }

    // ── Listener — arrows stuck in geometry ──────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        // Arrows / tridents / spectral arrows. Hit-block != null when they
        // actually stuck in the world (vs. hitting an entity).
        if (e.getHitBlock() == null) return;
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;

        // Only care about arrows fired by a player in a vanilla match.
        Match match = null;
        if (arrow.getShooter() instanceof Player shooter) match = matchFor(shooter);
        if (match == null) match = matchForLocation(arrow.getLocation());
        if (match == null) return;
        Arena arena = match.getArena();
        if (arena == null || !arena.isVanilla()) return;
        if (!isInside(arena, arrow.getLocation())) return;

        arrows.computeIfAbsent(match.getMatchId(), k -> ConcurrentHashMap.newKeySet())
                .add(arrow.getUniqueId());
    }

    // ── Core recorder ─────────────────────────────────────────────────────────

    private void recordIfTracked(Match match, Block b, BlockData original) {
        if (match == null) return;
        Arena arena = match.getArena();
        if (arena == null || !arena.isVanilla()) return;
        if (!isInside(arena, b.getLocation())) return;
        long key = packKey(b.getX(), b.getY(), b.getZ());
        diffs.computeIfAbsent(match.getMatchId(), k -> new LinkedHashMap<>())
                .putIfAbsent(key, original);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Reverts every block change made during {@code match} in a single tick,
     * and despawns every tracked stuck arrow. Returns the number of blocks
     * actually reverted (0 when no changes — caller can skip the post-round
     * restore phase entirely).
     */
    public int restore(Match match) {
        UUID matchId = match.getMatchId();
        Map<Long, BlockData> diff = diffs.remove(matchId);
        Set<UUID> arrowIds = arrows.remove(matchId);

        // Arrows first — they're cheap and don't depend on block state.
        if (arrowIds != null && !arrowIds.isEmpty()) {
            for (UUID id : arrowIds) {
                Entity ent = Bukkit.getEntity(id);
                if (ent instanceof AbstractArrow && ent.isValid()) ent.remove();
            }
        }
        // Sweep any residual arrows that landed inside the arena but weren't
        // tracked (e.g. spawned by another plugin / dropped by a skeleton).
        sweepArenaArrows(match);

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
            world.getBlockAt(x, y, z).setBlockData(e.getValue(), false);
            restored++;
        }
        return restored;
    }

    /**
     * Safety-net pass: removes every arrow currently inside the arena cuboid.
     * Catches arrows fired by entities we didn't track or that landed after
     * our ProjectileHit listener missed (very rare).
     */
    private void sweepArenaArrows(Match match) {
        Arena arena = match.getArena();
        if (arena == null || arena.getCorner1() == null) return;
        World world = arena.getCorner1().getWorld();
        if (world == null) return;
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c2 == null) return;
        double minX = Math.min(c1.getX(), c2.getX());
        double maxX = Math.max(c1.getX(), c2.getX()) + 1;
        double minY = Math.min(c1.getY(), c2.getY());
        double maxY = Math.max(c1.getY(), c2.getY()) + 1;
        double minZ = Math.min(c1.getZ(), c2.getZ());
        double maxZ = Math.max(c1.getZ(), c2.getZ()) + 1;
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        double dx = (maxX - minX) / 2.0;
        double dy = (maxY - minY) / 2.0;
        double dz = (maxZ - minZ) / 2.0;
        for (Entity ent : world.getNearbyEntities(new Location(world, cx, cy, cz), dx, dy, dz)) {
            if (ent instanceof AbstractArrow arrow && arrow.isValid()) {
                arrow.remove();
            }
        }
    }

    /** Clears any diff for this match without applying it. */
    public void discard(UUID matchId) {
        diffs.remove(matchId);
        arrows.remove(matchId);
    }

    public boolean hasChanges(UUID matchId) {
        Map<Long, BlockData> d = diffs.get(matchId);
        Set<UUID> a = arrows.get(matchId);
        return (d != null && !d.isEmpty()) || (a != null && !a.isEmpty());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Match matchFor(Player p) {
        if (p == null) return null;
        return plugin.getMatchManager().getMatch(p.getUniqueId());
    }

    /** Looks up a match whose arena contains this block's location. */
    private Match matchForBlock(Block b) {
        return matchForLocation(b.getLocation());
    }

    /**
     * Finds the live match whose vanilla arena cuboid contains {@code loc}.
     * Iterates active matches once — at the scale of "active duels" this is
     * effectively O(1).
     */
    private Match matchForLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        for (Match m : plugin.getMatchManager().getAllMatches()) {
            Arena arena = m.getArena();
            if (arena == null || !arena.isVanilla()) continue;
            if (isInside(arena, loc)) return m;
        }
        return null;
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

    // Silence unused-warning on Material import (kept for future block-type checks).
    @SuppressWarnings("unused")
    private static void unused() { Material m = Material.AIR; }
}
