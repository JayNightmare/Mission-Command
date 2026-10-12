package com.marscommand.mod.client;

import java.util.ArrayDeque;

public final class Typewriter {
    private record Message(String text, boolean typed, boolean error) {}
    private final ArrayDeque<Message> queue = new ArrayDeque<>();
    private Message current;
    private int revealed;
    private int total;
    private int hold;

    public void offer(String text, boolean typed, boolean error) {
        if (text == null || text.length() > 4096) {
            throw new IllegalArgumentException("Transmission text exceeds the display limit");
        }
        queue.addLast(new Message(text, typed, error));
    }

    public void tick() {
        if (current == null) {
            current = queue.pollFirst();
            if (current == null) {
                return;
            }
            total = current.text.codePointCount(0, current.text.length());
            revealed = current.typed ? 0 : total;
            hold = current.typed ? 100 : 60;
        }
        if (revealed < total) {
            revealed = Math.min(total, revealed + 2);
        } else if (--hold <= 0) {
            current = null;
        }
    }

    public boolean visible() {
        return current != null;
    }

    public String text() {
        return current == null ? "" : current.text.substring(0, current.text.offsetByCodePoints(0, revealed));
    }

    public boolean typing() {
        return current != null && revealed < total;
    }

    public boolean error() {
        return current != null && current.error;
    }

    public void clear() {
        queue.clear();
        current = null;
    }
}
