package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.PvPMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ArenaManager {

    private final ScorePvP plugin;

    /** number → Arena */
    private final Map<Integer, Arena> arenas = new ConcurrentHashMap<>();

    /** uuid → arena number (selection-tool session) */
    private final Map<UUID, Integer> selectionSession = new HashMap<>();

    /** uuid → pending region selection (for /scorepvp setregion via Golden Axe) */
    private final Map<UUID, Location[]> regionSelection = new HashMap<>();

    public ArenaManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void loadAll() {
        arenas.clear();
        plugin.getDatabase().get().loadAllArenas()
                .forEach(a -> {
                    arenas.put(a.getNumber(), a);
                    boolean hasUnresolved = a.getRawCorner1() != null || a.getRawCorner2() != null
                            || a.getRawPos1() != null || a.getRawPos2() != null || a.getRawCentre() != null;
                    plugin.getLogger().info("[ScorePvP]   Arena #" + a.getNumber()
                            + " '" + a.getName() + "' type=" + a.getType()
                            + " configured=" + a.isFullyConfigured()
                            + " ffaReady=" + a.isFfaReady()
                            + (hasUnresolved ? " (has unresolved locations — world may not be loaded yet)" : ""));
                });
        plugin.getLogger().info("[ScorePvP] Loaded " + arenas.size() + " arenas from database.");

        // Schedule a delayed re-resolution of arena locations after worlds are fully loaded
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, this::resolveUnresolvedLocations, 40L);
    }

    /** Attempts to re-parse raw location strings for arenas whose worlds weren't loaded at startup. */
    private void resolveUnresolvedLocations() {
        for (Arena arena : arenas.values()) {
            boolean resolved = false;
            if (arena.getCorner1() == null && arena.getRawCorner1() != null) {
                Location l = tryParseLocation(arena.getRawCorner1());
                if (l != null) { arena.setCorner1(l); resolved = true; }
            }
            if (arena.getCorner2() == null && arena.getRawCorner2() != null) {
                Location l = tryParseLocation(arena.getRawCorner2());
                if (l != null) { arena.setCorner2(l); resolved = true; }
            }
            if (arena.getPos1() == null && arena.getRawPos1() != null) {
                Location l = tryParseLocation(arena.getRawPos1());
                if (l != null) { arena.setPos1(l); resolved = true; }
            }
            if (arena.getPos2() == null && arena.getRawPos2() != null) {
                Location l = tryParseLocation(arena.getRawPos2());
                if (l != null) { arena.setPos2(l); resolved = true; }
            }
            if (arena.getCentre() == null && arena.getRawCentre() != null) {
                Location l = tryParseLocation(arena.getRawCentre());
                if (l != null) { arena.setCentre(l); resolved = true; }
            }
            if (resolved) {
                plugin.getLogger().info("[ScorePvP] Arena #" + arena.getNumber()
                        + " locations resolved after world load. configured=" + arena.isFullyConfigured());
            }
        }
    }

    /** Parses a serialized location string (world,x,y,z,yaw,pitch). Returns null if world is still unavailable. */
    private Location tryParseLocation(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split(",");
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w,
                    Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (Exception e) { return null; }
    }

    // ── Selection tool ────────────────────────────────────────────────────────

    public void startCreation(Player player, int number) {
        Arena arena = arenas.computeIfAbsent(number, Arena::new);
        saveArena(arena);
        selectionSession.put(player.getUniqueId(), number);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.creation-start",
                "{number}", String.valueOf(number)));
    }

    public boolean handleToolClick(Player player, Location loc, boolean leftClick) {
        UUID uuid = player.getUniqueId();
        if (!selectionSession.containsKey(uuid)) return false;

        int number = selectionSession.get(uuid);
        Arena arena = arenas.get(number);
        if (arena == null) return true;

        if (leftClick) {
            arena.setCorner1(loc);
            saveArena(arena);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.corner1-set",
                    "{loc}", formatLoc(loc)));
        } else {
            arena.setCorner2(loc);
            saveArena(arena);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.corner2-set",
                    "{loc}", formatLoc(loc)));
        }

        if (arena.getCorner1() != null && arena.getCorner2() != null) {
            selectionSession.remove(uuid);
            // Remove the Golden Axe selection tool from inventory
            removeSelectionTool(player);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.region-saved",
                    "{number}", String.valueOf(number)));
        }
        return true;
    }

    public boolean isInSelectionMode(UUID uuid) {
        return selectionSession.containsKey(uuid) || regionSelection.containsKey(uuid);
    }

    // ── Chest Region Selection (via Golden Axe) ──────────────────────────────

    public void startRegionSelection(Player player) {
        regionSelection.put(player.getUniqueId(), new Location[2]);
        // Give Golden Axe tool
        org.bukkit.inventory.ItemStack axe = new com.scorepvp.util.ItemBuilder(
                org.bukkit.Material.GOLDEN_AXE)
                .name(plugin.getConfigManager().rawStr("arena.region-tool-name"))
                .lore(plugin.getConfigManager().rawStr("arena.region-tool-lore-1"),
                      plugin.getConfigManager().rawStr("arena.region-tool-lore-2"))
                .build();
        player.getInventory().addItem(axe);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.region-select-msg"));
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.region-select-help-1")));
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.region-select-help-2")));
    }

    public boolean isInRegionSelectionMode(UUID uuid) {
        return regionSelection.containsKey(uuid);
    }

    public boolean handleRegionToolClick(Player player, Location loc, boolean leftClick) {
        UUID uuid = player.getUniqueId();
        Location[] corners = regionSelection.get(uuid);
        if (corners == null) return false;

        if (leftClick) {
            corners[0] = loc;
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.region-corner1",
                    "{loc}", formatLoc(loc)));
        } else {
            corners[1] = loc;
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.region-corner2",
                    "{loc}", formatLoc(loc)));
        }

        // Both corners set — save to config
        if (corners[0] != null && corners[1] != null) {
            Location c1 = corners[0];
            Location c2 = corners[1];
            String prefix = "chest-region.";
            plugin.getConfig().set(prefix + "world", c1.getWorld().getName());
            plugin.getConfig().set(prefix + "min-x", Math.min(c1.getBlockX(), c2.getBlockX()));
            plugin.getConfig().set(prefix + "min-y", Math.min(c1.getBlockY(), c2.getBlockY()));
            plugin.getConfig().set(prefix + "min-z", Math.min(c1.getBlockZ(), c2.getBlockZ()));
            plugin.getConfig().set(prefix + "max-x", Math.max(c1.getBlockX(), c2.getBlockX()));
            plugin.getConfig().set(prefix + "max-y", Math.max(c1.getBlockY(), c2.getBlockY()));
            plugin.getConfig().set(prefix + "max-z", Math.max(c1.getBlockZ(), c2.getBlockZ()));
            plugin.saveConfig();
            plugin.getConfigManager().reload();

            regionSelection.remove(uuid);
            // Remove the Golden Axe selection tool from inventory
            removeSelectionTool(player);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.region-chest-saved"));
        }
        return true;
    }

    // ── Position / name / type commands ──────────────────────────────────────

    public boolean setPosition(Player player, int number, String posName) {
        Arena arena = arenas.get(number);
        if (arena == null) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.not-found",
                    "{number}", String.valueOf(number)));
            return false;
        }
        Location loc = player.getLocation();
        switch (posName.toLowerCase()) {
            case "1", "pos1"                     -> arena.setPos1(loc);
            case "2", "pos2"                     -> arena.setPos2(loc);
            case "center", "centre", "c", "ffa"  -> arena.setCentre(loc);
            default -> {
                player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("arena.position-unknown"));
                return false;
            }
        }
        saveArena(arena);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.position-set",
                "{pos}", posName, "{number}", String.valueOf(number)));
        return true;
    }

    public void setName(Player player, int number, String name) {
        Arena arena = arenas.get(number);
        if (arena == null) { player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.not-found-short",
                "{number}", String.valueOf(number))); return; }
        arena.setName(name.toLowerCase());
        saveArena(arena);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.name-set",
                "{number}", String.valueOf(number), "{name}", name));
    }

    public void setType(Player player, int number, String typeName) {
        Arena arena = arenas.get(number);
        if (arena == null) { player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.not-found-short",
                "{number}", String.valueOf(number))); return; }
        Arena.ArenaType type;
        try { type = Arena.ArenaType.valueOf(typeName.toUpperCase()); }
        catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("arena.type-unknown"));
            return;
        }
        arena.setType(type);
        saveArena(arena);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.type-set",
                "{number}", String.valueOf(number), "{type}", type.name().toLowerCase()));
    }

    public void showInfo(Player player, int number) {
        Arena a = arenas.get(number);
        if (a == null) { player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("arena.not-found-short",
                "{number}", String.valueOf(number))); return; }
        String pfx = plugin.getConfigManager().rawMsg("prefix");
        player.sendMessage(com.scorepvp.util.ColorUtil.color(pfx + " " +
                plugin.getConfigManager().rawStr("arena.info-header")
                        .replace("{number}", String.valueOf(number))
                        .replace("{name}", a.getName())));
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.info-type")
                        .replace("{type}", a.getType().name().toLowerCase())));
        String statusStr = a.isFree()
                ? plugin.getConfigManager().rawStr("arena.info-status-free")
                : plugin.getConfigManager().rawStr("arena.info-status-busy");
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.info-status").replace("{status}", statusStr)));
        String configuredStr = a.isFullyConfigured()
                ? plugin.getConfigManager().rawStr("arena.info-yes")
                : plugin.getConfigManager().rawStr("arena.info-no");
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.info-configured").replace("{value}", configuredStr)));
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.info-corners")
                        .replace("{c1}", a.getCorner1() != null ? "&a\u2714" : "&c\u2718")
                        .replace("{c2}", a.getCorner2() != null ? "&a\u2714" : "&c\u2718")));
        player.sendMessage(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("arena.info-positions")
                        .replace("{p1}", a.getPos1() != null ? "&a\u2714" : "&c\u2718")
                        .replace("{p2}", a.getPos2() != null ? "&a\u2714" : "&c\u2718")
                        .replace("{centre}", a.getCentre() != null ? "&a\u2714" : "&c\u2718")));
        if (a.isVanilla()) {
            String snapVal = a.hasSnapshot()
                    ? plugin.getConfigManager().rawStr("arena.info-snapshot-yes")
                    : plugin.getConfigManager().rawStr("arena.info-snapshot-no")
                            .replace("{number}", String.valueOf(number));
            player.sendMessage(com.scorepvp.util.ColorUtil.color(
                    plugin.getConfigManager().rawStr("arena.info-snapshot").replace("{value}", snapVal)));
        }
    }

    // ── Arena search — allowed-arenas filter ──────────────────────────────────

    /**
     * Returns a free fully-configured arena for the given mode,
     * respecting the allowed-arenas list. If allowed-arenas is empty → all arenas eligible.
     * Picks randomly from matching candidates.
     */
    public Optional<Arena> findFreeArena(PvPMode mode) {
        List<String> allowed = mode.getAllowedArenas(); // empty = all

        List<Arena> candidates = arenas.values().stream()
                .filter(Arena::isFree)
                .filter(Arena::isFullyConfigured)
                .filter(a -> allowed.isEmpty() || allowed.contains(a.getName()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return Optional.empty();
        Collections.shuffle(candidates);
        return Optional.of(candidates.get(0));
    }

    /** Returns a free arena with at least a centre set (for FFA). */
    public Optional<Arena> findFreeArenaForFfa(PvPMode mode) {
        List<String> allowed = mode.getAllowedArenas();
        List<Arena> candidates = arenas.values().stream()
                .filter(Arena::isFree)
                .filter(Arena::isFfaReady)
                .filter(a -> allowed.isEmpty() || allowed.contains(a.getName()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return Optional.empty();
        Collections.shuffle(candidates);
        return Optional.of(candidates.get(0));
    }

    // ── Compatibility shims (old Optional-less API) ───────────────────────────

    public Arena getFreeArenaForDuel()            { return findFreeArena(dummyMode()).orElse(null); }
    public Arena getFreeArenaForFfa()             { return findFreeArenaForFfa(dummyMode()).orElse(null); }

    private PvPMode dummyMode() {
        return new PvPMode("","",null,false,true,"", List.of(), 3, 5, "");
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public void saveArena(Arena arena) {
        arenas.put(arena.getNumber(), arena);
        plugin.getLogger().info("[ScorePvP] Saving arena #" + arena.getNumber()
                + " '" + arena.getName() + "' to database...");
        plugin.getDatabase().saveArenaAsync(arena)
                .thenRun(() -> plugin.getLogger().info("[ScorePvP] Arena #" + arena.getNumber() + " saved successfully."))
                .exceptionally(ex -> { plugin.getLogger().severe("[ScorePvP] Failed to save arena #" + arena.getNumber() + ": " + ex.getMessage()); return null; });
    }

    public void deleteArena(int number) {
        arenas.remove(number);
        java.util.concurrent.CompletableFuture.runAsync(
                () -> plugin.getDatabase().get().deleteArena(number));
    }

    public Arena            getArena(int number)  { return arenas.get(number); }
    public Collection<Arena> getAllArenas()        { return Collections.unmodifiableCollection(arenas.values()); }

    // ── Util ─────────────────────────────────────────────────────────────────

    /** Removes all Golden Axe (selection tool) items from the player's inventory. */
    private void removeSelectionTool(Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            org.bukkit.inventory.ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == com.scorepvp.listener.ArenaToolListener.TOOL) {
                inv.setItem(i, null);
            }
        }
        player.updateInventory();
    }

    private String formatLoc(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
