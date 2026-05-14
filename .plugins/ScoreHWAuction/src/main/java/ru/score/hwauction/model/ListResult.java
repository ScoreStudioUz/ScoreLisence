package ru.score.hwauction.model;

public enum ListResult {
    SUCCESS,
    NO_ITEM_IN_HAND,
    BLACKLISTED,
    LIMIT_REACHED,
    PRICE_TOO_LOW,
    PRICE_TOO_HIGH,
    INVALID_DURATION,
    NO_PERMISSION,
    ERROR
}
