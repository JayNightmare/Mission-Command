package com.marscommand.mod.mission;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MissionRules(
        long schedulerMillis, long attemptWindowMillis, long confirmationMillis,
        long minimumDurationMillis, long maximumDurationMillis, int maximumQuantity,
        double readinessThreshold, double minimumHealthFraction, int minimumFood,
        double healthWeight, double hungerWeight, double equipmentWeight,
        double crossDimensionCost, Map<String, Double> dimensionScales,
        int completionPoints, int refusalPenalty, int handoverPenalty, int expiryPenalty,
        List<Rank> ranks, List<Template> templates, List<Goal> goals) {

    public enum Kind { DELIVERY, ARRIVAL }

    public record Rank(String name, int minimumScore, int maximumDifficulty,
                       double quantityMultiplier, double durationMultiplier, long cooldownMillis) {}

    public record Target(String dimension, double x, double y, double z, double radius) {}

    public record Template(String id, Kind kind, String briefing, Target target,
                           String item, int quantity, List<String> equipment,
                           int difficulty, long durationMillis) {}

    public record Requirement(String template, int completions) {}

    public record ResourceNeed(String template, int quantity) {}

    public record Phase(String id, List<ResourceNeed> resources) {}

    public record Goal(String id, List<Requirement> requirements, List<Phase> phases) {
        public Goal(String id, List<Requirement> requirements) {
            this(id, requirements, List.of());
        }
    }

    public static MissionRules defaults(double x, double y, double z) {
        Target depot = new Target("minecraft:overworld", x, y, z, 8);
        Target waypoint = new Target("minecraft:overworld", x + 96, y, z, 12);
        return new MissionRules(
                2000, 15000, 15000, 60000, 3600000, 4096,
                0.85, 0.75, 14, 0.5, 0.25, 0.25,
                4096, Map.of("minecraft:overworld", 1.0, "minecraft:the_nether", 8.0),
                10, 30, 5, 10,
                List.of(
                        new Rank("Initiate", Integer.MIN_VALUE, 1, 0.75, 1.5, 600000),
                        new Rank("Operative", 0, 1, 1, 1, 300000),
                        new Rank("Pathfinder", 50, 2, 1.5, 0.85, 180000),
                        new Rank("Commander", 150, 3, 2, 0.75, 120000)),
                List.of(
                        new Template("iron_delivery", Kind.DELIVERY,
                                "The Moon requires material reserves. Deliver the assigned supplies to the designated depot.",
                                depot, "minecraft:iron_ingot", 16, List.of(), 1, 600000),
                        new Template("survey_point", Kind.ARRIVAL,
                                "Mars Command requires a ground survey. Approach and reach the designated coordinates.",
                                waypoint, "", 1, List.of(), 1, 600000),
                        delivery("copper_delivery", depot, "minecraft:copper_ingot", 16),
                        delivery("redstone_delivery", depot, "minecraft:redstone", 32),
                        delivery("gold_delivery", depot, "minecraft:gold_ingot", 16),
                        delivery("diamond_delivery", depot, "minecraft:diamond", 8)),
                List.of(
                        new Goal("supply_network", List.of(new Requirement("iron_delivery", 3),
                                new Requirement("survey_point", 2))),
                        new Goal("survey_network", List.of(new Requirement("survey_point", 3),
                                new Requirement("iron_delivery", 2))),
                        new Goal("space_elevator", List.of(), List.of(
                                new Phase("foundations", List.of(new ResourceNeed("iron_delivery", 256),
                                        new ResourceNeed("copper_delivery", 128))),
                                new Phase("power", List.of(new ResourceNeed("redstone_delivery", 256),
                                        new ResourceNeed("gold_delivery", 64))),
                                new Phase("assembly", List.of(new ResourceNeed("diamond_delivery", 32),
                                        new ResourceNeed("iron_delivery", 512)))))));
    }

    private static Template delivery(String id, Target depot, String item, int quantity) {
        return new Template(id, Kind.DELIVERY,
                "The Moon requires material reserves. Deliver the assigned supplies to the designated depot.",
                depot, item, quantity, List.of(), 1, 600000);
    }

    public void validate() {
        require(schedulerMillis >= 100 && schedulerMillis <= 60000, "schedulerMillis must be 100..60000");
        require(attemptWindowMillis >= schedulerMillis, "attemptWindowMillis must be at least schedulerMillis");
        require(confirmationMillis >= schedulerMillis, "confirmationMillis must be at least schedulerMillis");
        require(minimumDurationMillis >= schedulerMillis * 2 && maximumDurationMillis >= minimumDurationMillis,
                "Invalid duration bounds");
        require(maximumDurationMillis <= 604800000, "maximumDurationMillis exceeds seven active days");
        require(maximumQuantity >= 1 && maximumQuantity <= 1000000, "Invalid maximumQuantity");
        require(fraction(readinessThreshold) && fraction(minimumHealthFraction), "Invalid readiness thresholds");
        require(minimumFood >= 0 && minimumFood <= 20, "minimumFood must be 0..20");
        require(finitePositive(healthWeight) && finitePositive(hungerWeight) && finitePositive(equipmentWeight),
                "Readiness weights must be finite and positive");
        require(finitePositive(crossDimensionCost), "crossDimensionCost must be finite and positive");
        require(dimensionScales != null, "dimensionScales is required");
        dimensionScales.forEach((id, scale) -> {
            identifier(id);
            require(scale != null && finitePositive(scale), "Invalid dimension scale: " + id);
        });
        require(completionPoints > 0 && handoverPenalty > 0 && expiryPenalty > 0
                        && refusalPenalty > Math.max(handoverPenalty, expiryPenalty),
                "Points must be positive; refusal must be the heaviest penalty");
        require(ranks != null && !ranks.isEmpty(), "At least one rank is required");
        require(ranks.getFirst() != null && ranks.getFirst().minimumScore() == Integer.MIN_VALUE,
                "First rank must cover all negative scores");
        int previous = Integer.MIN_VALUE;
        Set<String> names = new HashSet<>();
        for (int index = 0; index < ranks.size(); index++) {
            Rank rank = ranks.get(index);
            require(rank != null && rank.name() != null && !rank.name().isBlank() && rank.name().length() <= 64,
                    "Invalid rank name");
            require(names.add(rank.name()), "Duplicate rank name");
            require(index == 0 || rank.minimumScore() > previous, "Ranks must have increasing thresholds");
            previous = rank.minimumScore();
            require(rank.maximumDifficulty() >= 1 && finitePositive(rank.quantityMultiplier())
                            && finitePositive(rank.durationMultiplier()) && rank.cooldownMillis() >= schedulerMillis,
                    "Invalid rank policy: " + rank.name());
        }
        require(templates != null && !templates.isEmpty() && templates.size() <= 256, "Require 1..256 templates");
        Set<String> ids = new HashSet<>();
        for (Template template : templates) {
            require(template != null, "Null mission template");
            key(template.id());
            require(ids.add(template.id()), "Duplicate mission template: " + template.id());
            require(template.kind() != null && template.briefing() != null
                            && !template.briefing().isBlank() && template.briefing().length() <= 2048,
                    "Invalid mission kind/briefing: " + template.id());
            validateTarget(template.target());
            require(template.quantity() >= 1 && template.quantity() <= maximumQuantity
                            && template.difficulty() >= 1
                            && template.durationMillis() >= minimumDurationMillis
                            && template.durationMillis() <= maximumDurationMillis,
                    "Invalid quantity/difficulty/duration: " + template.id());
            require(template.equipment() != null && template.equipment().size() <= 32, "Invalid equipment list");
            template.equipment().forEach(MissionRules::itemIdentifier);
            require(template.item() != null, "Missing item field");
            if (template.kind() == Kind.DELIVERY) {
                itemIdentifier(template.item());
            }
        }
        require(goals != null && !goals.isEmpty() && goals.size() <= 128, "Require 1..128 goals");
        Set<String> goalIds = new HashSet<>();
        for (Goal goal : goals) {
            require(goal != null, "Null goal");
            key(goal.id());
            require(goalIds.add(goal.id()), "Duplicate goal: " + goal.id());
            List<Phase> phases = goal.phases() == null ? List.of() : goal.phases();
            require(goal.requirements() != null && (!goal.requirements().isEmpty() || !phases.isEmpty()),
                    "Empty goal: " + goal.id());
            require(phases.size() <= 32, "Too many phases: " + goal.id());
            Set<String> phaseIds = new HashSet<>();
            for (Phase phase : phases) {
                require(phase != null, "Null phase");
                key(phase.id());
                require(phaseIds.add(phase.id()), "Duplicate phase: " + phase.id());
                require(phase.resources() != null && !phase.resources().isEmpty() && phase.resources().size() <= 32,
                        "Phase needs 1..32 resources: " + phase.id());
                Set<String> items = new HashSet<>();
                for (ResourceNeed need : phase.resources()) {
                    require(need != null && need.template() != null && ids.contains(need.template())
                                    && need.quantity() >= 1 && need.quantity() <= 1000000,
                            "Invalid resource need in phase: " + phase.id());
                    Template source = template(need.template());
                    require(source.kind() == Kind.DELIVERY && items.add(source.item()),
                            "Resource needs must be distinct delivery templates: " + phase.id());
                }
            }
            Set<String> requirements = new HashSet<>();
            for (Requirement requirement : goal.requirements()) {
                require(requirement != null && ids.contains(requirement.template())
                                && requirements.add(requirement.template()) && requirement.completions() >= 1
                                && requirement.completions() <= 10000,
                        "Invalid goal requirement: " + goal.id());
            }
        }
    }

    public Rank rank(int score) {
        Rank result = ranks.getFirst();
        for (Rank candidate : ranks) {
            if (score >= candidate.minimumScore()) {
                result = candidate;
            }
        }
        return result;
    }

    public Template template(String id) {
        return templates.stream().filter(template -> template.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing persisted template: " + id));
    }

    public Goal goal(String id) {
        return goals.stream().filter(goal -> goal.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing persisted goal: " + id));
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void validateTarget(Target target) {
        require(target != null, "Missing mission target");
        identifier(target.dimension());
        require(Double.isFinite(target.x()) && Double.isFinite(target.y()) && Double.isFinite(target.z())
                        && Math.abs(target.x()) <= 29999984 && Math.abs(target.z()) <= 29999984
                        && target.radius() >= 1 && target.radius() <= 128, "Invalid mission target");
    }

    private static boolean fraction(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static void key(String value) {
        require(value != null && value.matches("[a-z0-9_]{1,64}"), "Invalid catalogue identifier: " + value);
    }

    private static void identifier(String value) {
        require(value != null && value.length() <= 512 && value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"),
                "Invalid/oversized registry identifier: " + value);
    }

    private static void itemIdentifier(String value) {
        require(value != null && value.length() <= 512, "Null/oversized item identifier");
        identifier(value.startsWith("#") ? value.substring(1) : value);
    }
}
