package com.marscommand.mod.client;

import com.marscommand.mod.mission.MissionJournal;
import com.marscommand.mod.mission.MissionRules;
import com.marscommand.mod.network.JournalPagePayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JournalAssembler {
    private final int generation;
    private final List<MissionJournal.Entry> entries = new ArrayList<>();
    private final Set<Long> ids = new HashSet<>();
    private int nextPage;
    private int pageCount;
    private int score;
    private String rank;

    public JournalAssembler(int generation) {
        this.generation = generation;
    }

    public boolean accept(JournalPagePayload page) {
        if (page.generation() != generation) {
            return false;
        }
        MissionRules.require(page.page() == nextPage, "Mission journal pages arrived out of order");
        if (nextPage == 0) {
            pageCount = page.pageCount();
            score = page.score();
            rank = page.rank();
        }
        MissionRules.require(pageCount == page.pageCount() && score == page.score() && rank.equals(page.rank()),
                "Mission journal changed during pagination");
        for (MissionJournal.Entry entry : page.entries()) {
            MissionRules.require(ids.add(entry.id()), "Duplicate mission in journal snapshot");
            entries.add(entry);
        }
        nextPage++;
        return true;
    }

    public boolean complete() {
        return pageCount > 0 && nextPage == pageCount;
    }

    public int nextPage() {
        return nextPage;
    }

    public List<MissionJournal.Entry> entries() {
        MissionRules.require(complete(), "Mission journal is still loading");
        return List.copyOf(entries);
    }

    public int score() {
        return score;
    }

    public String rank() {
        return rank;
    }
}
