package ru.score.hwauction.gui;

/**
 * All GUI window titles live here.
 * We keep a raw (colour-stripped) form used for identification in click events,
 * and a display form (with colour) used for opening inventories.
 */
public final class GuiTitle {

    // Keys in messages.yml
    public static final String KEY_MAIN    = "gui.main-title";
    public static final String KEY_MY      = "gui.my-items-title";
    public static final String KEY_EXPIRED = "gui.expired-title";
    public static final String KEY_BID     = "gui.bid-view-title";
    public static final String KEY_BUY_AMT     = "gui.buy-amount-title";
    public static final String KEY_BUY_CONFIRM = "gui.buy-confirm-title";

    private GuiTitle() {}
}
