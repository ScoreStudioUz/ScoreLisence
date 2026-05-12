package com.scoretpa.placeholder;

import com.scoretpa.ScoreTpa;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class TpaPlaceholderExpansion extends PlaceholderExpansion {

    private final ScoreTpa plugin;

    public TpaPlaceholderExpansion(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "scoretpa";
    }

    @Override
    public String getAuthor() {
        return "ScoreStudio";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";

        if (params.equals("time_left")) {
            return String.valueOf(plugin.getTeleportManager().getTimeLeft(player.getUniqueId()));
        }

        if (params.equals("is_teleporting")) {
            return plugin.getTeleportManager().isTeleporting(player.getUniqueId()) ? "true" : "false";
        }

        return null;
    }
}
