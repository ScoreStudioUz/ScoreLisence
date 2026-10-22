package ru.score.hwauction.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import ru.score.hwauction.model.AuctionItem;

public final class AuctionCancelEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private boolean cancelled;

    public AuctionCancelEvent(Player player, AuctionItem item) {
        super(item);
        this.player = player;
    }

    public Player getPlayer() { return player; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
