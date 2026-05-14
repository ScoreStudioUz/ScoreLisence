package ru.score.hwauction.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import ru.score.hwauction.model.AuctionItem;

public final class AuctionBidEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player bidder;
    private double bidAmount;
    private boolean cancelled;

    public AuctionBidEvent(Player bidder, AuctionItem item, double bidAmount) {
        super(item);
        this.bidder = bidder;
        this.bidAmount = bidAmount;
    }

    public Player getBidder() { return bidder; }
    public double getBidAmount() { return bidAmount; }
    public void setBidAmount(double bidAmount) { this.bidAmount = bidAmount; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
