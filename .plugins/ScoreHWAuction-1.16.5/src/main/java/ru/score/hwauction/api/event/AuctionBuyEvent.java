package ru.score.hwauction.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import ru.score.hwauction.model.AuctionItem;

public final class AuctionBuyEvent extends AuctionEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player buyer;
    private int amount;
    private double totalPrice;
    private boolean cancelled;

    public AuctionBuyEvent(Player buyer, AuctionItem item, int amount, double totalPrice) {
        super(item);
        this.buyer = buyer;
        this.amount = amount;
        this.totalPrice = totalPrice;
    }

    public Player getBuyer() { return buyer; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public double getTotalPrice() { return totalPrice; }
    public void setTotalPrice(double price) { this.totalPrice = price; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
