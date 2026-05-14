package org.by1337.bairdrop.vulcan;

import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.by1337.bairdrop.AirDrop;
import org.by1337.bairdrop.BAirDrop;
import org.by1337.bairdrop.util.Message;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class VulcanThrower {

    private static final int MAX_SPLIT_SIZE = 10;
    private static final int EJECT_INTERVAL_TICKS = 4;
    private static final int ITEMS_PER_WAVE = 2;
    private static final int MAX_TRAIL_TICKS = 100;

    public static void throwItems(AirDrop airdrop) {
        Location loc = airdrop.getAirDropLocation();
        if (loc == null) return;

        World world = loc.getWorld();
        if (world == null) return;

        Inventory inventory = airdrop.getInventory();
        int vulcanRadius = airdrop.getVulcanRadius();
        boolean vulcanAnimation = airdrop.isVulcanAnimation();
        boolean protectCheats = airdrop.isProtectCheatsEnabled();
        List<String> cheatsMaterials = airdrop.getProtectCheatsMaterials();
        HashMap<Integer, ItemStack> originalItems = airdrop.getOriginalItems();
        boolean showHologram = airdrop.isVulcanItemHologramEnabled();
        Map<String, String> itemDisplayNames = airdrop.getVulcanItemDisplayNames();

        double centerX = loc.getX() + 0.5;
        double centerY = loc.getY() + 1.2;
        double centerZ = loc.getZ() + 0.5;

        // Eruption effects
        Location dropLoc = new Location(world, centerX, centerY, centerZ);
        world.playSound(dropLoc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.6f);
        world.spawnParticle(Particle.LAVA, dropLoc, 40, 0.5, 1.0, 0.5, 0.0);
        world.spawnParticle(Particle.FLAME, dropLoc, 80, 0.3, 0.8, 0.3, 0.15);
        world.spawnParticle(Particle.SMOKE_LARGE, dropLoc, 50, 0.5, 1.0, 0.5, 0.1);
        world.playSound(dropLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.3f);

        // Collect real items from inventory (use originalItems if protect-cheats)
        List<ItemStack> realSplitItems = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item;
            if (protectCheats && originalItems.containsKey(i)) {
                item = originalItems.get(i);
            } else {
                item = inventory.getItem(i);
            }
            if (item == null || item.getType() == Material.AIR) continue;

            int totalAmount = item.getAmount();
            while (totalAmount > 0) {
                int splitAmount;
                if (totalAmount <= MAX_SPLIT_SIZE) {
                    splitAmount = totalAmount;
                } else {
                    splitAmount = 3 + ThreadLocalRandom.current().nextInt(MAX_SPLIT_SIZE - 2);
                    if (splitAmount > totalAmount) splitAmount = totalAmount;
                }
                ItemStack splitItem = item.clone();
                splitItem.setAmount(splitAmount);
                realSplitItems.add(splitItem);
                totalAmount -= splitAmount;
            }
            inventory.setItem(i, null);
        }
        originalItems.clear();

        Collections.shuffle(realSplitItems);

        // Build fake items + real mapping
        List<ItemStack> throwItems = new ArrayList<>();
        List<ItemStack> realItems = new ArrayList<>();

        for (ItemStack realItem : realSplitItems) {
            if (protectCheats && !cheatsMaterials.isEmpty()) {
                ItemStack fakeItem = createFakeItem(realItem, cheatsMaterials);
                throwItems.add(fakeItem);
                realItems.add(realItem);
            } else {
                throwItems.add(realItem);
                realItems.add(realItem);
            }
        }

        int totalWaves = (int) Math.ceil((double) throwItems.size() / ITEMS_PER_WAVE);

        Particle.DustOptions orangeDust = new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1.2f);
        Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(255, 60, 0), 0.8f);
        Particle.DustOptions yellowDust = new Particle.DustOptions(Color.fromRGB(255, 200, 0), 1.0f);

        // Get the pickup listener to register fake→real mapping
        VulcanPickupListener pickupListener = airdrop.getVulcanPickupListener();

        new BukkitRunnable() {
            int waveIndex = 0;

            @Override
            public void run() {
                if (waveIndex >= totalWaves) {
                    Location finalLoc = new Location(world, centerX, centerY + 2.0, centerZ);
                    world.spawnParticle(Particle.LAVA, finalLoc, 20, 0.5, 0.5, 0.5, 0.0);
                    world.spawnParticle(Particle.FLAME, finalLoc, 40, 0.3, 0.5, 0.3, 0.1);
                    world.playSound(finalLoc, Sound.BLOCK_LAVA_EXTINGUISH, 1.0f, 0.8f);
                    cancel();
                    return;
                }

                int startIdx = waveIndex * ITEMS_PER_WAVE;
                int endIdx = Math.min(startIdx + ITEMS_PER_WAVE, throwItems.size());

                for (int idx = startIdx; idx < endIdx; idx++) {
                    ItemStack throwItem = throwItems.get(idx);
                    ItemStack realItem = realItems.get(idx);

                    double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
                    double horizontalSpeed = calculateHorizontalSpeed(vulcanRadius);
                    double vx = Math.cos(angle) * horizontalSpeed;
                    double vz = Math.sin(angle) * horizontalSpeed;
                    double vy = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.5;

                    Location spawnLoc = new Location(world, centerX, centerY, centerZ);
                    Item droppedItem = world.dropItem(spawnLoc, throwItem);
                    droppedItem.setVelocity(new Vector(vx, vy, vz));
                    droppedItem.setPickupDelay(32767);

                    // Register in pickup listener for protect-cheats swap
                    if (protectCheats && pickupListener != null) {
                        pickupListener.registerFakeItem(droppedItem.getUniqueId(), realItem);
                    }

                    world.spawnParticle(Particle.FLAME, spawnLoc, 6, 0.1, 0.2, 0.1, 0.05);
                    world.spawnParticle(Particle.LAVA, spawnLoc, 3, 0.1, 0.1, 0.1, 0.0);

                    // Determine hologram display name for this item
                    String holoName = null;
                    if (showHologram && itemDisplayNames != null) {
                        // Check by real item material name (config key)
                        String matKey = realItem.getType().name();
                        if (itemDisplayNames.containsKey(matKey)) {
                            holoName = Message.messageBuilder(itemDisplayNames.get(matKey));
                        } else if (itemDisplayNames.containsKey("default")) {
                            holoName = Message.messageBuilder(itemDisplayNames.get("default"));
                        }
                    }

                    scheduleGroundCheck(droppedItem, world, vulcanAnimation,
                            orangeDust, redDust, yellowDust, showHologram, holoName);
                }

                Location waveLoc = new Location(world, centerX, centerY, centerZ);
                world.playSound(waveLoc, Sound.ENTITY_BLAZE_SHOOT, 0.5f, 0.7f);
                world.spawnParticle(Particle.FLAME, waveLoc, 10, 0.2, 0.5, 0.2, 0.08);

                waveIndex++;
            }
        }.runTaskTimer(BAirDrop.getInstance(), 0, EJECT_INTERVAL_TICKS);
    }

    private static double calculateHorizontalSpeed(int vulcanRadius) {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < 0.15) {
            return 0.02 + ThreadLocalRandom.current().nextDouble() * 0.1;
        } else if (roll < 0.40) {
            return 0.1 + ThreadLocalRandom.current().nextDouble() * (vulcanRadius * 0.08);
        } else if (roll < 0.70) {
            return vulcanRadius * 0.08 + ThreadLocalRandom.current().nextDouble() * (vulcanRadius * 0.12);
        } else {
            return vulcanRadius * 0.12 + ThreadLocalRandom.current().nextDouble() * (vulcanRadius * 0.15);
        }
    }

    private static void scheduleGroundCheck(Item droppedItem, World world, boolean doAnim,
                                             Particle.DustOptions orangeDust, Particle.DustOptions redDust,
                                             Particle.DustOptions yellowDust, boolean showHologram,
                                             String holoName) {
        new BukkitRunnable() {
            int trailTick = 0;
            ArmorStand holoStand = null;

            @Override
            public void run() {
                trailTick++;
                if (trailTick > MAX_TRAIL_TICKS || droppedItem.isDead()) {
                    if (!droppedItem.isDead()) {
                        droppedItem.setPickupDelay(0);
                    }
                    removeHolo();
                    cancel();
                    return;
                }

                if (droppedItem.isOnGround()) {
                    droppedItem.setPickupDelay(0);
                    if (doAnim) {
                        Location landLoc = droppedItem.getLocation();
                        world.spawnParticle(Particle.FLAME, landLoc, 8, 0.2, 0.1, 0.2, 0.03);
                        world.spawnParticle(Particle.SMOKE_NORMAL, landLoc, 5, 0.15, 0.1, 0.15, 0.02);
                    }
                    // Keep hologram following on ground, remove when item picked up
                    startGroundHoloTracker();
                    cancel();
                    return;
                }

                // Hologram follows item while in air
                if (showHologram && holoName != null) {
                    updateHolo(droppedItem.getLocation().add(0, 0.8, 0));
                }

                // Fire trail particles
                if (doAnim) {
                    Location itemLoc = droppedItem.getLocation();
                    world.spawnParticle(Particle.FLAME, itemLoc, 2, 0.05, 0.05, 0.05, 0.02);
                    world.spawnParticle(Particle.REDSTONE, itemLoc, 3, 0.08, 0.08, 0.08, 0.0, orangeDust);
                    world.spawnParticle(Particle.REDSTONE, itemLoc, 2, 0.06, 0.06, 0.06, 0.0, redDust);
                    if (trailTick % 3 == 0) {
                        world.spawnParticle(Particle.REDSTONE, itemLoc, 1, 0.03, 0.03, 0.03, 0.0, yellowDust);
                    }
                }
            }

            private void updateHolo(Location loc) {
                if (holoStand == null) {
                    holoStand = world.spawn(loc, ArmorStand.class, stand -> {
                        stand.setVisible(false);
                        stand.setGravity(false);
                        stand.setSmall(true);
                        stand.setMarker(true);
                        stand.setCustomName(holoName);
                        stand.setCustomNameVisible(true);
                    });
                } else {
                    holoStand.teleport(loc);
                }
            }

            private void removeHolo() {
                if (holoStand != null) {
                    holoStand.remove();
                    holoStand = null;
                }
            }

            private void startGroundHoloTracker() {
                if (!showHologram || holoName == null) {
                    removeHolo();
                    return;
                }
                // Keep hologram alive on ground, teleport every tick, remove when item dies
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (droppedItem.isDead()) {
                            removeHolo();
                            cancel();
                            return;
                        }
                        updateHolo(droppedItem.getLocation().add(0, 0.8, 0));
                    }
                }.runTaskTimer(BAirDrop.getInstance(), 0, 1);
            }
        }.runTaskTimer(BAirDrop.getInstance(), 1, 1); // every tick for smooth hologram
    }

    private static ItemStack createFakeItem(ItemStack realItem, List<String> cheatsMaterials) {
        String matName = cheatsMaterials.get(ThreadLocalRandom.current().nextInt(cheatsMaterials.size()));
        try {
            Material fakeMat = Material.valueOf(matName);
            return new ItemStack(fakeMat, realItem.getAmount());
        } catch (IllegalArgumentException e) {
            return realItem.clone();
        }
    }
}
