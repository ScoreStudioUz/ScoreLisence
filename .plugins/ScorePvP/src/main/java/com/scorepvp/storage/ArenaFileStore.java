package com.scorepvp.storage;

import com.scorepvp.model.Arena;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reads and writes arenas as YAML files under {@code data-arena/}.
 *
 * <p>One file per arena, named {@code <number>.yml}. Format:
 * <pre>
 * number: 1
 * name: sword1
 * type: DEFAULT
 * corner1: "sword_arena,-3875.0,130.0,2469.0,0.0,0.0"
 * corner2: "sword_arena,-3770.0,50.0,2331.0,0.0,0.0"
 * pos1:    "sword_arena,-3824.57,64.0,2377.36,0.15,-2.55"
 * pos2:    "sword_arena,-3824.52,64.0,2423.89,-179.55,-2.40"
 * centre:  "sword_arena,-3825.09,64.0,2399.36,-178.95,0.75"
 * </pre>
 *
 * Locations are stored as the same {@code world,x,y,z,yaw,pitch} string that
 * Arena used to write to the old SQLite DB — we keep the format identical so
 * an admin can hand-edit either side.
 *
 * <h2>Why YAML, not JSON</h2>
 * Bukkit already ships SnakeYAML; arenas are tiny (sub-1KB each) and edited by
 * humans often. JSON would just mean another dependency for zero gain.
 */
public class ArenaFileStore {

    private final Logger log;

    public ArenaFileStore(Logger log) {
        this.log = log;
    }

    /** Reads every {@code <n>.yml} in {@code dir} and returns the parsed arenas. */
    public List<Arena> loadAll(File dir) {
        List<Arena> out = new ArrayList<>();
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                log.warning("[ScorePvP] data-arena/ could not be created at " + dir.getAbsolutePath());
            }
            return out;
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) return out;

        for (File f : files) {
            Arena a = readOne(f);
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Writes a single arena to {@code <dir>/<number>.yml}. Atomic via temp + rename. */
    public void save(Arena arena, File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            log.warning("[ScorePvP] data-arena/ could not be created for save");
            return;
        }
        File target = new File(dir, arena.getNumber() + ".yml");
        File tmp    = new File(dir, arena.getNumber() + ".yml.tmp");

        YamlConfiguration y = new YamlConfiguration();
        y.set("number", arena.getNumber());
        y.set("name",   arena.getName());
        y.set("type",   arena.getType().name());
        y.set("corner1", chooseRaw(arena.getCorner1(), arena.getRawCorner1()));
        y.set("corner2", chooseRaw(arena.getCorner2(), arena.getRawCorner2()));
        y.set("pos1",    chooseRaw(arena.getPos1(),    arena.getRawPos1()));
        y.set("pos2",    chooseRaw(arena.getPos2(),    arena.getRawPos2()));
        y.set("centre",  chooseRaw(arena.getCentre(),  arena.getRawCentre()));

        try {
            y.save(tmp);
            if (target.exists() && !target.delete()) {
                log.warning("[ScorePvP] save arena #" + arena.getNumber() + ": couldn't replace existing file");
            }
            if (!tmp.renameTo(target)) {
                log.warning("[ScorePvP] save arena #" + arena.getNumber() + ": rename failed, falling back to direct write");
                y.save(target);
                tmp.delete();
            }
        } catch (IOException e) {
            log.warning("[ScorePvP] save arena #" + arena.getNumber() + " failed: " + e.getMessage());
        }
    }

    /** Deletes {@code <dir>/<number>.yml}. No-op if missing. */
    public void delete(int number, File dir) {
        File f = new File(dir, number + ".yml");
        if (f.exists() && !f.delete()) {
            log.warning("[ScorePvP] delete arena #" + number + ": file could not be removed");
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Arena readOne(File f) {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            int number = y.getInt("number", -1);
            if (number < 0) {
                // Fall back to filename if the file forgot to set the number.
                String base = f.getName().replace(".yml", "");
                try { number = Integer.parseInt(base); }
                catch (NumberFormatException e) {
                    log.warning("[ScorePvP] arena file " + f.getName() + " has no usable number, skipping");
                    return null;
                }
            }

            Arena a = new Arena(number);
            a.setName(y.getString("name", ""));
            String type = y.getString("type", "DEFAULT");
            try { a.setType(Arena.ArenaType.valueOf(type.toUpperCase())); }
            catch (IllegalArgumentException e) { a.setType(Arena.ArenaType.DEFAULT); }

            applyLocation(a, "corner1", y.getString("corner1"));
            applyLocation(a, "corner2", y.getString("corner2"));
            applyLocation(a, "pos1",    y.getString("pos1"));
            applyLocation(a, "pos2",    y.getString("pos2"));
            applyLocation(a, "centre",  y.getString("centre"));
            return a;
        } catch (Exception e) {
            log.warning("[ScorePvP] arena file " + f.getName() + " load failed: " + e.getMessage());
            return null;
        }
    }

    private void applyLocation(Arena a, String which, String raw) {
        if (raw == null || raw.isEmpty()) return;
        Location parsed = parse(raw);
        switch (which) {
            case "corner1" -> { a.setRawCorner1(raw); if (parsed != null) a.setCorner1(parsed); }
            case "corner2" -> { a.setRawCorner2(raw); if (parsed != null) a.setCorner2(parsed); }
            case "pos1"    -> { a.setRawPos1(raw);    if (parsed != null) a.setPos1(parsed); }
            case "pos2"    -> { a.setRawPos2(raw);    if (parsed != null) a.setPos2(parsed); }
            case "centre"  -> { a.setRawCentre(raw);  if (parsed != null) a.setCentre(parsed); }
        }
    }

    /**
     * Returns the same {@code world,x,y,z,yaw,pitch} string that Arena.setX
     * would have stamped — preferring whatever the live Location says, falling
     * back to the raw string preserved across world-unloads.
     */
    private static String chooseRaw(Location live, String rawFallback) {
        if (live != null && live.getWorld() != null) {
            return live.getWorld().getName() + ","
                    + live.getX() + "," + live.getY() + "," + live.getZ() + ","
                    + live.getYaw() + "," + live.getPitch();
        }
        return rawFallback;
    }

    private static Location parse(String s) {
        try {
            String[] p = s.split(",");
            if (p.length < 6) return null;
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w,
                    Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]),
                    Float.parseFloat(p[5]));
        } catch (Exception e) {
            return null;
        }
    }
}
