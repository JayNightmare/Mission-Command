package com.marscommand.mod.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TypewriterTest {
    @Test
    void missionTextAppearsIncrementallyWithoutLosingQueuedOrders() {
        Typewriter writer = new Typewriter();
        writer.offer("First mission", true, false);
        writer.offer("Second mission", true, false);
        writer.tick();
        assertEquals("Fi", writer.text());
        assertTrue(writer.typing());
        for (int index = 0; index < 6; index++) {
            writer.tick();
        }
        assertEquals("First mission", writer.text());
        assertFalse(writer.typing());
        for (int index = 0; index < 101; index++) {
            writer.tick();
        }
        assertEquals("Se", writer.text());
    }

    @Test
    void typingNeverSplitsASurrogatePair() {
        Typewriter writer = new Typewriter();
        writer.offer("A\uD83C\uDF15B", true, false);
        writer.tick();
        assertEquals("A\uD83C\uDF15", writer.text());
        writer.tick();
        assertEquals("A\uD83C\uDF15B", writer.text());
    }

    @Test
    void logoutClearsPreviousServerTransmissions() {
        Typewriter writer = new Typewriter();
        writer.offer("Secret old-world mission", true, false);
        writer.offer("Queued old-world mission", true, false);
        writer.tick();
        writer.clear();
        writer.tick();
        assertFalse(writer.visible());
        assertEquals("", writer.text());
    }

    @Test
    void errorsAreImmediateAndOversizedTextIsExplicitlyRejected() {
        Typewriter writer = new Typewriter();
        writer.offer("Cannot submit away from the target", false, true);
        writer.tick();
        assertTrue(writer.error());
        assertFalse(writer.typing());
        assertEquals("Cannot submit away from the target", writer.text());
        assertThrows(IllegalArgumentException.class, () -> writer.offer("x".repeat(4097), true, false));
    }
}
