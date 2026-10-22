package ru.score.hwauction.manager;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.PlayerSession;

public final class SoundService {

    private final ScoreHWAuction plugin;

    public SoundService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /** Played when the player successfully buys an item */
    public void playPurchase(Player player) {
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
    }

    /** Played when the seller's item is sold */
    public void playSold(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
    }

    /** Played when a bid is placed successfully */
    public void playBid(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    /** Played when a player is outbid by someone else */
    public void playBidOutbid(Player player) {
        play(player, Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
    }

    /** Played when the player wins a bid auction */
    public void playBidWin(Player player) {
        play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.0f);
    }

    /** Played when an action fails (error state) */
    public void playError(Player player) {
        play(player, Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
    }

    // playOpen intentionally removed — opening the GUI should be silent (no spam)

    private void play(Player player, Sound sound, float volume, float pitch) {
        if (!plugin.getPluginConfig().isEnableSounds()) return;
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        if (!session.isSoundEnabled()) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}
