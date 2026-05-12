package com.scorepvp.task;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runs every N minutes and unlocks arenas that are BUSY but have no active match.
 * Prevents permanent arena lockout from crashed/timed-out matches.
 */
public class ArenaWatchdogTask extends BukkitRunnable {

    private final ScorePvP plugin;

    public ArenaWatchdogTask(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long maxMinutes = plugin.getConfig().getLong("arena.match-timeout-minutes", 30);
        int freed = 0, forcedEnd = 0;

        // Bug #4a: end matches that have exceeded their timeout
        for (var match : plugin.getMatchManager().getAllMatches()) {
            if (match.isExpired(maxMinutes) && match.getState() != com.scorepvp.model.Match.State.FINISHED) {
                plugin.getLogger().warning("[ScorePvP] Watchdog: force-ending timed-out match "
                        + match.getMatchId().toString().substring(0, 8));
                plugin.getMatchManager().endMatch(match, true);
                forcedEnd++;
            }
        }

        // Bug #4b: unlock arenas that are busy but have no match
        for (Arena arena : plugin.getArenaManager().getAllArenas()) {
            if (!arena.isBusy()) continue;
            boolean hasMatch = plugin.getMatchManager().getAllMatches().stream()
                    .anyMatch(m -> m.getArena().getNumber() == arena.getNumber()
                            && m.getState() != com.scorepvp.model.Match.State.FINISHED);
            if (!hasMatch) {
                arena.setFree();
                plugin.getLogger().warning("[ScorePvP] Watchdog: force-unlocked arena #"
                        + arena.getNumber() + " (" + arena.getName() + ") — no active match.");
                freed++;
            }
        }

        if (freed > 0 || forcedEnd > 0) {
            plugin.getLogger().info("[ScorePvP] Watchdog: freed " + freed
                    + " arena(s), ended " + forcedEnd + " timed-out match(es).");
        }
    }
}
