package com.homelabhub.internal.status;

/** UNKNOWN (never checked, or the check itself failed) is deliberately not the same as DOWN. */
public enum State { UP, DOWN, DEGRADED, UNKNOWN }
