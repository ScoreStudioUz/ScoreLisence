package ru.score.hwauction.gui;

/**
 * Slot indices for the navigation row of the 6-row main auction GUI.
 * Items occupy slots 0-44 (rows 1-5).
 * Navigation occupies slots 45-53 (row 6).
 *
 * Order per screenshot:
 *  45 = MY_ITEMS   (Товары на продаже)
 *  46 = EXPIRED    (Просроченные товары)
 *  47 = REFRESH    (Обновить)
 *  48 = PREV_PAGE  (Предыдущая страница)
 *  49 = MODE       (Аукцион / режим)
 *  50 = NEXT_PAGE  (Следующая страница)
 *  51 = HELP       (Помощь)
 *  52 = SORT       (Сортировка)
 *  53 = CATEGORY   (Категории предметов)
 */
public final class NavSlot {
    public static final int MY_ITEMS   = 45;
    public static final int EXPIRED    = 46;
    public static final int REFRESH    = 47;
    public static final int PREV_PAGE  = 48;
    public static final int MODE       = 49;
    public static final int NEXT_PAGE  = 50;
    public static final int HELP       = 51;
    public static final int SORT       = 52;
    public static final int CATEGORY   = 53;

    private NavSlot() {}
}
