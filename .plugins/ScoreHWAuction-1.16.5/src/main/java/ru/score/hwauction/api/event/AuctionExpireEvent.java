package ru.score.hwauction.api.event;

import org.bukkit.event.HandlerList;
import ru.score.hwauction.model.AuctionItem;

public final class AuctionExpireEvent extends AuctionEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public AuctionExpireEvent(AuctionItem item) { super(item); }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
