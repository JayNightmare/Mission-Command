package com.marscommand.mod.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ActiveClockTest {
    @Test
    void restartingDoesNotCountDowntime() {
        ActiveClock clock = new ActiveClock(1000000000);
        long persisted = clock.sample(2000000000);
        ActiveClock restarted = new ActiveClock(100000000000L);
        persisted += restarted.sample(101000000000L);
        assertEquals(2000, persisted);
    }

    @Test
    void fractionalMillisecondsAreRetainedAndLagCountsAsRunningTime() {
        ActiveClock clock = new ActiveClock(0);
        assertEquals(0, clock.sample(900000));
        assertEquals(1, clock.sample(1100000));
        assertEquals(1, clock.sample(2100000));
        assertEquals(5000, clock.sample(5002100000L));
    }
}
