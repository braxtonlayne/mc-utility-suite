package com.utilitysuite.core.api;

public enum NavigationStatus {
    IDLE,
    PATHING,
    ARRIVED,
    FAILED,
    /** Baritone is not installed. */
    UNAVAILABLE
}
