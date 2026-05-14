package ru.score.hwauction.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import ru.score.hwauction.model.AuctionItem;

public final class AuctionListEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private boolean cancelled;
    private double price;

    public AuctionListEvent(Player player, AuctionItem item) {
        super(item);
        this.player = player;
        this.price = item.getPrice();
    }

    public Player getPlayer() { return player; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
