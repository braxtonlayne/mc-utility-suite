package com.utilitysuite.core.api;

/** Why a lease stopped being active. */
public enum LeaseEndReason {
    RELEASED,
    PREEMPTED,
    TIMED_OUT,
    MANUAL_OVERRIDE,
    STOP_ALL
}
