package com.marscommand.mod.mission;

import java.util.Map;
import java.util.Optional;

/** Chooses the next supply mission; every proposal is revalidated by the server before use. */
public interface MissionPlanner {
    /** A null or blank briefing means the template briefing is used. */
    record Proposal(String templateId, int quantity, String briefing) {}

    record Context(String phase, int readyPlayers, Map<String, Long> shortfalls) {}

    Optional<Proposal> propose(MissionRules rules, Context context);

    /** True while an asynchronous proposal is outstanding and the caller should wait. */
    default boolean pending() {
        return false;
    }

    /** Deterministic baseline and fallback: largest shortfall first. */
    final class Heuristic implements MissionPlanner {
        @Override
        public Optional<Proposal> propose(MissionRules rules, Context context) {
            Map<String, Long> shortfalls = context.shortfalls();
            String best = null;
            for (Map.Entry<String, Long> entry : shortfalls.entrySet()) {
                if (best == null || entry.getValue() > shortfalls.get(best)
                        || entry.getValue().equals(shortfalls.get(best)) && entry.getKey().compareTo(best) < 0) {
                    best = entry.getKey();
                }
            }
            if (best == null) {
                return Optional.empty();
            }
            long quantity = Math.min(Math.min(shortfalls.get(best), rules.template(best).quantity()),
                    rules.maximumQuantity());
            return Optional.of(new Proposal(best, (int) quantity, null));
        }
    }
}
