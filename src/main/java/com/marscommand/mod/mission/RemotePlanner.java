package com.marscommand.mod.mission;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.marscommand.mod.identity.IdentitySettings;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Asks the Mars Command API for a proposal without blocking the server thread. Callers must revalidate. */
public final class RemotePlanner implements MissionPlanner {
    private static final Gson JSON = new Gson();
    private static final int MAX_BODY = 65536;
    private static final long RETRY_NANOS = 30_000_000_000L;
    private record Cached(Map<String, Long> signature, Proposal proposal) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String origin;
    private final String token;
    private volatile Cached cached;
    private volatile boolean pending;
    private volatile long retryAtNanos;

    RemotePlanner(String origin, String token) {
        this.origin = origin;
        this.token = token;
    }

    /** Empty unless MARS_PLANNER_API_ORIGIN and a valid MARS_MINECRAFT_SERVER_TOKEN are set. */
    public static Optional<RemotePlanner> fromEnvironment() {
        String origin = System.getenv("MARS_PLANNER_API_ORIGIN");
        String token = System.getenv("MARS_MINECRAFT_SERVER_TOKEN");
        if (origin == null || origin.isBlank()) {
            return Optional.empty();
        }
        try {
            MissionRules.require(token != null && token.matches("[A-Za-z0-9_-]{32,128}"),
                    "MARS_MINECRAFT_SERVER_TOKEN is missing or invalid");
            return Optional.of(new RemotePlanner(IdentitySettings.validOrigin(origin,
                    Boolean.parseBoolean(System.getenv("MARS_COMMAND_ALLOW_LOCAL_HTTP"))), token));
        } catch (IllegalArgumentException exception) {
            LogUtils.getLogger().error("Remote mission planner disabled: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Proposal> propose(MissionRules rules, Context context) {
        Cached current = cached;
        if (current != null) {
            if (current.signature().equals(context.shortfalls())) {
                return Optional.of(current.proposal());
            }
            cached = null;
        }
        if (!pending && System.nanoTime() >= retryAtNanos && !context.shortfalls().isEmpty()) {
            request(rules, context);
        }
        return Optional.empty();
    }

    @Override
    public boolean pending() {
        return pending;
    }

    private void request(MissionRules rules, Context context) {
        JsonObject body = new JsonObject();
        body.addProperty("phase", context.phase());
        body.addProperty("readyPlayers", Math.clamp(context.readyPlayers(), 0, 1000));
        body.add("recent", new JsonArray());
        JsonArray needs = new JsonArray();
        context.shortfalls().forEach((id, remaining) -> {
            MissionRules.Template template = rules.template(id);
            JsonObject need = new JsonObject();
            need.addProperty("template", id);
            need.addProperty("item", template.item());
            need.addProperty("remaining", remaining);
            need.addProperty("chunk", Math.min(template.quantity(), rules.maximumQuantity()));
            needs.add(need);
        });
        body.add("needs", needs);
        Map<String, Long> signature = Map.copyOf(context.shortfalls());
        pending = true;
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/api/auth/minecraft/plan"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body), StandardCharsets.UTF_8)).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
            try (var stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_BODY + 1);
                if (response.statusCode() != 200 || bytes.length > MAX_BODY) {
                    throw new IOException("Planner request failed (HTTP " + response.statusCode() + ")");
                }
                return parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage());
            }
        }).whenComplete((proposal, failure) -> {
            if (failure == null) {
                cached = new Cached(new LinkedHashMap<>(signature), proposal);
            } else {
                retryAtNanos = System.nanoTime() + RETRY_NANOS;
                LogUtils.getLogger().warn("Remote mission planner unavailable; using local fallback: {}",
                        failure.getMessage());
            }
            pending = false;
        });
    }

    static Proposal parse(String text) {
        try {
            var value = JsonParser.parseString(text);
            MissionRules.require(value.isJsonObject() && value.getAsJsonObject().get("proposal") != null
                    && value.getAsJsonObject().get("proposal").isJsonObject(), "Planner response must contain a proposal");
            JsonObject object = value.getAsJsonObject().getAsJsonObject("proposal");
            var template = object.get("template");
            var quantity = object.get("quantity");
            var briefing = object.get("briefing");
            MissionRules.require(template != null && template.isJsonPrimitive() && template.getAsJsonPrimitive().isString()
                    && quantity != null && quantity.isJsonPrimitive() && quantity.getAsJsonPrimitive().isNumber()
                    && quantity.getAsString().matches("[0-9]{1,9}"), "Invalid planner proposal fields");
            String text2 = null;
            if (briefing != null && !briefing.isJsonNull()) {
                MissionRules.require(briefing.isJsonPrimitive() && briefing.getAsJsonPrimitive().isString(),
                        "Invalid planner briefing");
                text2 = briefing.getAsString();
                MissionRules.require(text2.length() <= 480 && text2.chars().noneMatch(Character::isISOControl),
                        "Invalid planner briefing text");
            }
            return new Proposal(template.getAsString(), quantity.getAsInt(), text2);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Planner response is not valid JSON");
        }
    }
}
