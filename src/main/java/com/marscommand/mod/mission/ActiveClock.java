package com.marscommand.mod.mission;

public final class ActiveClock {
    private long lastNanos;

    public ActiveClock(long nowNanos) {
        lastNanos = nowNanos;
    }

    public long sample(long nowNanos) {
        long millis = (nowNanos - lastNanos) / 1000000;
        if (millis > 0) {
            lastNanos += millis * 1000000;
            return millis;
        }
        return 0;
    }
}
