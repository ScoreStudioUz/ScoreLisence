package ru.score.hwauction.model;

public enum SortType {
    NEWEST,         // Сначала новые  (default, first in list)
    OLDEST,         // Сначала старые
    CHEAPEST,       // Сначала дешевые
    EXPENSIVE,      // Сначала дорогие
    CHEAPEST_PER,   // Сначала дешевые за ед.
    EXPENSIVE_PER   // Сначала дорогие за ед.
}
