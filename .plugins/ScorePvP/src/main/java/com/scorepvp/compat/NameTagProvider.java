package com.scorepvp.compat;

import com.scorepvp.model.Match;
import org.bukkit.entity.Player;

/**
 * Strategy interface for nametag display during matches.
 * Implementations can use Bukkit Scoreboard teams, TAB API, etc.
 */
public interface NameTagProvider {

    /**
     * Sets up nametags for all participants in a match.
     * Called when a round starts (before countdown ends).
     */
    void setupMatchNametags(Match match);

    /**
     * Clears match nametags and restores defaults for all participants.
     * Called when a match ends.
     */
    void clearMatchNametags(Match match);

    /**
     * Ensures health display is up-to-date for the given player.
     * Re-injects health objective if it was lost due to scoreboard changes.
     */
    void updateHealth(Player player);

    /**
     * Called when the underlying nametag plugin (e.g. TAB) is reloaded.
     * Implementations should re-apply all active match nametags.
     */
    default void onExternalReload() {}

    /**
     * Clears match nametag/team state for a SINGLE player (e.g. a spectator
     * leaving mid-match) without tearing down the rest of the match's
     * scoreboard. Implementations should restore that player's scoreboard,
     * list name, and team membership to lobby defaults.
     */
    default void clearMatchNametagsForPlayer(Match match, Player player) {}
}
