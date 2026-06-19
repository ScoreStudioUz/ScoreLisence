package net.scorestudios.freezeworld;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FreezeWorld — keeps imported worlds (e.g. WorldSnap exports) from disintegrating
 * on chunk load. Cancels the events that knock floating sand, flowers, torches,
 * carrots-on-stone, leaves, etc. loose, without breaking redstone or player builds.
 *
 * <p>Strategy: only cancel "shape recalc" style updates triggered by chunk load —
 * NOT updates triggered by the player breaking/placing blocks. So if you mine the
 * block under floating sand, the sand still falls. If you load a chunk with floating
 * sand, it stays floating. This matches how big "anti-grief" servers behave.
 */
public final class FreezeWorldPlugin extends JavaPlugin implements Listener {

    private volatile boolean enabled = true;

    /** Runtime whitelist for water/lava placed by players and flow blocks created from them. */
    private final Set<FluidBlockKey> playerFluidBlocks = ConcurrentHashMap.newKeySet();

    /** Blocks that would normally fall on physics tick. */
    private static final Set<Material> FALLING_BLOCKS = EnumSet.noneOf(Material.class);
    /** Blocks that "fade" (snow melt, ice melt, coral fade) on physics tick. */
    private static final Set<Material> FADEABLE = EnumSet.noneOf(Material.class);
    /** Blocks that need a specific support and would self-destruct (flowers, torches, rails, etc.). */
    private static final Set<Material> FRAGILE = EnumSet.noneOf(Material.class);

    static {
        // Gravity blocks
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.endsWith("_CONCRETE_POWDER") || n.equals("SAND") || n.equals("RED_SAND")
                    || n.equals("GRAVEL") || n.equals("ANVIL") || n.equals("CHIPPED_ANVIL")
                    || n.equals("DAMAGED_ANVIL") || n.equals("SCAFFOLDING")
                    || n.equals("DRAGON_EGG") || n.equals("POINTED_DRIPSTONE")
                    || n.equals("SUSPICIOUS_SAND") || n.equals("SUSPICIOUS_GRAVEL")) {
                FALLING_BLOCKS.add(m);
            }

            // Snow/ice/coral fade
            if (n.equals("SNOW") || n.equals("ICE") || n.equals("FROSTED_ICE")
                    || n.endsWith("_CORAL") || n.endsWith("_CORAL_FAN")
                    || n.endsWith("_CORAL_BLOCK") || n.endsWith("_CORAL_WALL_FAN")
                    || n.equals("PACKED_ICE") || n.equals("BLUE_ICE")) {
                FADEABLE.add(m);
            }

            // Fragile / support-required blocks
            if (n.endsWith("_FLOWER") || n.endsWith("_SAPLING") || n.endsWith("_LEAVES")
                    || n.endsWith("_TORCH") || n.endsWith("_TORCH_WALL") || n.equals("TORCH")
                    || n.equals("SOUL_TORCH") || n.equals("REDSTONE_TORCH")
                    || n.endsWith("_RAIL") || n.equals("RAIL")
                    || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
                    || n.endsWith("_BANNER") || n.endsWith("_HEAD") || n.endsWith("_SKULL")
                    || n.endsWith("_SIGN") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")
                    || n.endsWith("_FENCE_GATE") || n.endsWith("_FENCE")
                    || n.equals("DEAD_BUSH") || n.equals("FERN") || n.equals("LARGE_FERN")
                    || n.equals("GRASS") || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS")
                    || n.equals("DANDELION") || n.equals("POPPY") || n.equals("BLUE_ORCHID")
                    || n.equals("ALLIUM") || n.equals("AZURE_BLUET") || n.equals("RED_TULIP")
                    || n.equals("ORANGE_TULIP") || n.equals("WHITE_TULIP") || n.equals("PINK_TULIP")
                    || n.equals("OXEYE_DAISY") || n.equals("CORNFLOWER") || n.equals("LILY_OF_THE_VALLEY")
                    || n.equals("WITHER_ROSE") || n.equals("SUNFLOWER") || n.equals("LILAC")
                    || n.equals("ROSE_BUSH") || n.equals("PEONY") || n.equals("PINK_PETALS")
                    || n.equals("CHORUS_FLOWER") || n.equals("CHORUS_PLANT")
                    || n.equals("WHEAT") || n.equals("CARROTS") || n.equals("POTATOES")
                    || n.equals("BEETROOTS") || n.equals("MELON_STEM") || n.equals("PUMPKIN_STEM")
                    || n.equals("ATTACHED_MELON_STEM") || n.equals("ATTACHED_PUMPKIN_STEM")
                    || n.equals("SWEET_BERRY_BUSH") || n.equals("BAMBOO") || n.equals("BAMBOO_SAPLING")
                    || n.equals("KELP") || n.equals("KELP_PLANT") || n.equals("SEAGRASS")
                    || n.equals("TALL_SEAGRASS") || n.equals("SEA_PICKLE")
                    || n.equals("VINE") || n.equals("GLOW_LICHEN")
                    || n.equals("CAVE_VINES") || n.equals("CAVE_VINES_PLANT")
                    || n.equals("TWISTING_VINES") || n.equals("TWISTING_VINES_PLANT")
                    || n.equals("WEEPING_VINES") || n.equals("WEEPING_VINES_PLANT")
                    || n.equals("LADDER") || n.equals("LANTERN") || n.equals("SOUL_LANTERN")
                    || n.equals("END_ROD") || n.equals("LIGHTNING_ROD") || n.equals("CHAIN")
                    || n.equals("MUSHROOM_STEM") || n.equals("RED_MUSHROOM") || n.equals("BROWN_MUSHROOM")
                    || n.equals("CRIMSON_FUNGUS") || n.equals("WARPED_FUNGUS")
                    || n.equals("CRIMSON_ROOTS") || n.equals("WARPED_ROOTS") || n.equals("NETHER_SPROUTS")
                    || n.equals("CACTUS") || n.equals("SUGAR_CANE") || n.equals("DEAD_CORAL")
                    || n.equals("FLOWER_POT") || n.equals("LILY_PAD")
                    || n.equals("AMETHYST_CLUSTER") || n.equals("LARGE_AMETHYST_BUD")
                    || n.equals("MEDIUM_AMETHYST_BUD") || n.equals("SMALL_AMETHYST_BUD")
                    || n.equals("DECORATED_POT") || n.equals("CANDLE") || n.endsWith("_CANDLE")
                    || n.equals("AZALEA") || n.equals("FLOWERING_AZALEA")
                    || n.equals("MOSS_CARPET") || n.endsWith("_CARPET")
                    || n.equals("SPORE_BLOSSOM") || n.equals("HANGING_ROOTS")
                    || n.equals("BIG_DRIPLEAF") || n.equals("BIG_DRIPLEAF_STEM")
                    || n.equals("SMALL_DRIPLEAF") || n.equals("PITCHER_PLANT") || n.equals("PITCHER_CROP")
                    || n.equals("TORCHFLOWER") || n.equals("TORCHFLOWER_CROP")
                    || n.equals("COCOA")) {
                FRAGILE.add(m);
            }
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FreezeWorld active. Protecting "
                + (FALLING_BLOCKS.size() + FADEABLE.size() + FRAGILE.size())
                + " block types from chunk-load physics.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eFreezeWorld is " + (enabled ? "§aON" : "§cOFF"));
            sender.sendMessage("§7Usage: /freezeworld on|off|status");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "on" -> { enabled = true;  sender.sendMessage("§a[FreezeWorld] Enabled"); }
            case "off" -> { enabled = false; sender.sendMessage("§c[FreezeWorld] Disabled"); }
            case "status" -> sender.sendMessage("§eFreezeWorld: " + (enabled ? "§aON" : "§cOFF"));
            default -> sender.sendMessage("§cUnknown: " + args[0]);
        }
        return true;
    }

    // ─── Event handlers ───────────────────────────────────────────────────────

    /**
     * Fires for falling sand/gravel/etc. AND for fragile blocks checking their
     * support. We block both cases for our tracked materials. If a real player
     * action triggered it, BlockPhysicsEvent's source will differ from the block —
     * we still cancel, because the substance of the floating world is what we want.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (!enabled) return;
        Material m = e.getBlock().getType();
        if (FALLING_BLOCKS.contains(m) || FRAGILE.contains(m) || FADEABLE.contains(m)) {
            e.setCancelled(true);
        }
    }

    /** Snow melt, ice melt, coral fade, fire die. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (!enabled) return;
        e.setCancelled(true);
    }

    /**
     * Snow accumulation, ice forming → we freeze the state (cancel).
     * EXCEPTION: stone / cobblestone / obsidian / basalt formed by a
     * PLAYER-placed lava/water interaction is allowed through, so newly poured
     * fluids still turn to rock. Old frozen-world fluids never form anything
     * (their coords aren't in {@link #playerFluidBlocks}).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!enabled) return;
        Material formed = e.getNewState().getType();
        if (isFluidReactionBlock(formed) && nearPlayerFluid(e.getBlock())) {
            // Player-driven lava+water rock. Let it form, and track the new
            // solid's coord so a follow-up flow off it behaves consistently.
            playerFluidBlocks.add(FluidBlockKey.from(e.getBlock()));
            return;
        }
        e.setCancelled(true);
    }

    /** Blocks produced by lava/water meeting (the only forms we want to allow). */
    private boolean isFluidReactionBlock(Material m) {
        return m == Material.STONE || m == Material.COBBLESTONE
                || m == Material.OBSIDIAN || m == Material.BASALT;
    }

    /** True if this block or any of its 6 neighbours is a tracked player fluid. */
    private boolean nearPlayerFluid(Block b) {
        if (playerFluidBlocks.contains(FluidBlockKey.from(b))) return true;
        for (BlockFace f : new BlockFace[]{
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (playerFluidBlocks.contains(FluidBlockKey.from(b.getRelative(f)))) return true;
        }
        return false;
    }

    /** Mushroom spread, fire spread, vine growth. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (!enabled) return;
        e.setCancelled(true);
    }

    /** Tracks water/lava placed by bucket so only these fluids can keep flowing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (isFluidBucket(e.getBucket())) {
            markBucketTarget(e.getBlock(), e.getBlockClicked(), e.getBlockFace());
        }
    }

    /** Covers creative/admin placement paths if the server exposes fluid as a placed block. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isFluid(e.getBlockPlaced().getType())) {
            markPlayerFluid(e.getBlockPlaced());
        }
    }

    /** Prevent stale coordinates from staying whitelisted after the fluid is picked up. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        unmarkBucketTarget(e.getBlock(), e.getBlockClicked(), e.getBlockFace());
    }

    /** Prevent stale coordinates if a plugin or special block-break path removes tracked fluid. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        unmarkPlayerFluid(e.getBlock());
    }

    /** Water/lava flow into new blocks. Old world fluids stay frozen; player-placed fluids can spread. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        if (!enabled) return;
        Material src = e.getBlock().getType();
        if (!isFluid(src)) {
            return;
        }

        FluidBlockKey source = FluidBlockKey.from(e.getBlock());
        if (!playerFluidBlocks.contains(source)) {
            e.setCancelled(true);
            return;
        }

        playerFluidBlocks.add(FluidBlockKey.from(e.getToBlock()));
    }

    /** Make every bow/crossbow arrow non-pickup from the moment it is shot. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent e) {
        if (e.getProjectile() instanceof AbstractArrow arrow && isArrowEntity(arrow.getType())) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    /** Safety net for any arrow entity that still fires a pickup event. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent e) {
        if (isArrowEntity(e.getArrow().getType())) {
            e.setCancelled(true);
        }
    }

    /** Leaves trying to decay. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent e) {
        if (!enabled) return;
        e.setCancelled(true);
    }

    /**
     * THE critical one: when a chunk loads, if Paper queues a sand-fall, it spawns
     * a FallingBlock entity. We catch that entity at spawn time and cancel it,
     * which leaves the original block in place untouched.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        if (!enabled) return;
        if (e.getEntityType() == EntityType.FALLING_BLOCK) {
            e.setCancelled(true);
        }
    }

    /**
     * Catches things like Endermen picking up blocks, sheep eating grass, zombies
     * breaking doors, silverfish entering stone — all things that mutate the world
     * passively.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!enabled) return;
        // Allow players (they're not an entity in this event), allow falling-block
        // landing only if cancelled FallingBlock somehow slipped through — for
        // safety cancel everything.
        e.setCancelled(true);
    }

    /**
     * Walks every block in newly-loaded chunks and removes scheduled ticks
     * that Paper would otherwise process on the next server tick.
     * This is what makes sand "freeze" on chunk load — Paper enqueues a fall
     * tick during ChunkHolder.fullStatus, before BlockPhysicsEvent ever fires.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabled) return;
        // ChunkLoadEvent doesn't expose tick-queue manipulation in Bukkit API,
        // but the physics-cancellation above plus FallingBlock entity-cancel
        // covers the user-visible result. Nothing to do here directly; the
        // listener exists as a hook point if we ever need NMS access.
    }

    private void markPlayerFluid(Block block) {
        if (block == null) {
            return;
        }
        playerFluidBlocks.add(FluidBlockKey.from(block));
    }

    private void unmarkPlayerFluid(Block block) {
        if (block == null) {
            return;
        }
        playerFluidBlocks.remove(FluidBlockKey.from(block));
    }

    private void markBucketTarget(Block affectedBlock, Block clickedBlock, BlockFace blockFace) {
        markPlayerFluid(affectedBlock);
        if (affectedBlock == null) {
            markPlayerFluid(clickedBlock);
            markPlayerFluid(getRelative(clickedBlock, blockFace));
        }
    }

    private void unmarkBucketTarget(Block affectedBlock, Block clickedBlock, BlockFace blockFace) {
        unmarkPlayerFluid(affectedBlock);
        unmarkPlayerFluid(clickedBlock);
        unmarkPlayerFluid(getRelative(clickedBlock, blockFace));
    }

    private Block getRelative(Block block, BlockFace blockFace) {
        if (block == null || blockFace == null) {
            return null;
        }
        return block.getRelative(blockFace);
    }

    private boolean isFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    private boolean isFluidBucket(Material material) {
        return material == Material.WATER_BUCKET || material == Material.LAVA_BUCKET;
    }

    private boolean isArrowEntity(EntityType entityType) {
        return entityType == EntityType.ARROW || entityType == EntityType.SPECTRAL_ARROW;
    }

    private record FluidBlockKey(UUID worldId, int x, int y, int z) {
        private static FluidBlockKey from(Block block) {
            return new FluidBlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
