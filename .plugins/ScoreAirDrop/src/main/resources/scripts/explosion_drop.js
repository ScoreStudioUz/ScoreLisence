var Material = Packages.org.bukkit.Material;
var ItemStack = Packages.org.bukkit.inventory.ItemStack;
var Location = Packages.org.bukkit.Location;
var Vector = Packages.org.bukkit.util.Vector;
var BukkitRunnable = Packages.org.bukkit.scheduler.BukkitRunnable;
var Sound = Packages.org.bukkit.Sound;
var Particle = Packages.org.bukkit.Particle;
var DustOptions = Packages.org.bukkit.Particle.DustOptions;
var Color = Packages.org.bukkit.Color;

/*
 * explosion_drop.js
 *
 * Enhanced explosion script:
 * - Stronger item scattering with variable radius (close, mid, far)
 * - Stack splitting (e.g. 37 apples -> 10+10+10+7)
 * - Gradual item ejection over time (not all at once)
 * - Orange fire-like particle trail on flying items
 *
 * Parameters:
 *   airdrop  - the AirDrop object
 *
 * Usage in listeners.yml:
 *   explosion-throw:
 *     description: '&cThrows all items from the airdrop in random directions'
 *     event: 'UNLOCK_EVENT'
 *     commands:
 *       - '[RUN_JS=explosion_drop.js] param(airdrop=airdrop)-scheduler'
 */

var loc = airdrop.getAirDropLocation();

if (loc != null) {
    var world = loc.getWorld();
    var inventory = airdrop.getInventory();
    var centerX = loc.getX() + 0.5;
    var centerY = loc.getY() + 1.2;
    var centerZ = loc.getZ() + 0.5;

    // Play explosion sound and particles
    var dropLoc = new Location(world, centerX, centerY, centerZ);
    world.playSound(dropLoc, Sound.ENTITY_GENERIC_EXPLODE, 2.0, 0.8);
    world.spawnParticle(Particle.EXPLOSION_LARGE, dropLoc, 5, 1.0, 1.0, 1.0, 0.0);
    world.spawnParticle(Particle.FLAME, dropLoc, 60, 0.5, 0.5, 0.5, 0.2);
    world.spawnParticle(Particle.SMOKE_LARGE, dropLoc, 35, 0.6, 0.6, 0.6, 0.15);
    world.playSound(dropLoc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2, 0.8);

    // Collect all non-null items and split stacks
    var splitItems = [];
    var MAX_SPLIT_SIZE = 10; // Maximum items per split stack

    for (var i = 0; i < inventory.getSize(); i++) {
        var item = inventory.getItem(i);
        if (item != null && item.getType() != Material.AIR) {
            var totalAmount = item.getAmount();
            // Split large stacks into smaller ones
            while (totalAmount > 0) {
                var splitAmount;
                if (totalAmount <= MAX_SPLIT_SIZE) {
                    splitAmount = totalAmount;
                } else {
                    // Random split between 3 and MAX_SPLIT_SIZE for variety
                    splitAmount = 3 + Math.floor(Math.random() * (MAX_SPLIT_SIZE - 2));
                    if (splitAmount > totalAmount) splitAmount = totalAmount;
                }
                var splitItem = item.clone();
                splitItem.setAmount(splitAmount);
                splitItems.push(splitItem);
                totalAmount -= splitAmount;
            }
            // Remove item from inventory so it's not duplicated
            inventory.setItem(i, null);
        }
    }

    // Shuffle the split items for random ejection order
    for (var s = splitItems.length - 1; s > 0; s--) {
        var j = Math.floor(Math.random() * (s + 1));
        var temp = splitItems[s];
        splitItems[s] = splitItems[j];
        splitItems[j] = temp;
    }

    // Gradual ejection settings
    var EJECT_INTERVAL_TICKS = 3;  // Ticks between each item ejection (3 ticks = 0.15s)
    var ITEMS_PER_WAVE = 2;        // Items thrown per wave

    // Orange dust options for fire-like trail particles
    var orangeDust = new DustOptions(Color.fromRGB(255, 140, 0), 1.2);
    var redDust = new DustOptions(Color.fromRGB(255, 60, 0), 0.8);

    // Reference to plugin for scheduling
    var plugin = Packages.org.by1337.bairdrop.BAirDrop.getInstance();

    // Calculate total waves needed
    var totalWaves = Math.ceil(splitItems.length / ITEMS_PER_WAVE);

    // Create a BukkitRunnable to gradually eject items
    var waveIndex = 0;
    var itemsRef = splitItems;
    var worldRef = world;
    var cX = centerX;
    var cY = centerY;
    var cZ = centerZ;

    var task = new BukkitRunnable() {
        run: function() {
            if (waveIndex >= totalWaves) {
                // Final burst of particles when done
                var finalLoc = new Location(worldRef, cX, cY, cZ);
                worldRef.spawnParticle(Particle.FLAME, finalLoc, 30, 0.3, 0.3, 0.3, 0.1);
                worldRef.playSound(finalLoc, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8, 1.2);
                this.cancel();
                return;
            }

            var startIdx = waveIndex * ITEMS_PER_WAVE;
            var endIdx = Math.min(startIdx + ITEMS_PER_WAVE, itemsRef.length);

            for (var idx = startIdx; idx < endIdx; idx++) {
                var currentItem = itemsRef[idx];

                // Random angle in radians (0 to 2*PI)
                var angle = Math.random() * Math.PI * 2.0;

                // Variable distance: sometimes close, sometimes mid, sometimes far
                var distanceRoll = Math.random();
                var horizontalSpeed;
                var verticalSpeed;

                if (distanceRoll < 0.20) {
                    // 20% chance: very close (almost same spot)
                    horizontalSpeed = 0.05 + Math.random() * 0.15;
                    verticalSpeed = 0.3 + Math.random() * 0.2;
                } else if (distanceRoll < 0.50) {
                    // 30% chance: close range
                    horizontalSpeed = 0.3 + Math.random() * 0.5;
                    verticalSpeed = 0.4 + Math.random() * 0.3;
                } else if (distanceRoll < 0.80) {
                    // 30% chance: medium range
                    horizontalSpeed = 0.8 + Math.random() * 0.7;
                    verticalSpeed = 0.5 + Math.random() * 0.4;
                } else {
                    // 20% chance: far range (strong scatter)
                    horizontalSpeed = 1.5 + Math.random() * 1.0;
                    verticalSpeed = 0.6 + Math.random() * 0.5;
                }

                var vx = Math.cos(angle) * horizontalSpeed;
                var vz = Math.sin(angle) * horizontalSpeed;
                var vy = verticalSpeed;

                var spawnLoc = new Location(worldRef, cX, cY, cZ);
                var droppedItem = worldRef.dropItem(spawnLoc, currentItem);
                droppedItem.setVelocity(new Vector(vx, vy, vz));
                droppedItem.setPickupDelay(30); // 1.5 second pickup delay

                // Spawn orange fire-like particles at the ejection point
                worldRef.spawnParticle(Particle.FLAME, spawnLoc, 5, 0.1, 0.1, 0.1, 0.05);
                worldRef.spawnParticle(Particle.REDSTONE, spawnLoc, 8, 0.2, 0.2, 0.2, 0.0, orangeDust);
                worldRef.spawnParticle(Particle.REDSTONE, spawnLoc, 4, 0.15, 0.15, 0.15, 0.0, redDust);

                // Schedule particle trail for this item over 15 ticks
                (function(droppedRef, wRef) {
                    var trailTick = 0;
                    var trailTask = new BukkitRunnable() {
                        run: function() {
                            trailTick++;
                            if (trailTick > 15 || droppedRef.isDead() || droppedRef.isOnGround()) {
                                this.cancel();
                                return;
                            }
                            var itemLoc = droppedRef.getLocation();
                            wRef.spawnParticle(Particle.FLAME, itemLoc, 2, 0.05, 0.05, 0.05, 0.02);
                            wRef.spawnParticle(Particle.REDSTONE, itemLoc, 3, 0.1, 0.1, 0.1, 0.0, orangeDust);
                        }
                    };
                    trailTask.runTaskTimer(plugin, 1, 2);
                })(droppedItem, worldRef);
            }

            // Small sound per wave for gradual effect
            var waveLoc = new Location(worldRef, cX, cY, cZ);
            worldRef.playSound(waveLoc, Sound.ENTITY_BLAZE_SHOOT, 0.4, 1.5);

            waveIndex++;
        }
    };

    // Start the gradual ejection: first wave immediately, then every EJECT_INTERVAL_TICKS
    task.runTaskTimer(plugin, 0, EJECT_INTERVAL_TICKS);
}
