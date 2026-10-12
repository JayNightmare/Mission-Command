package com.marscommand.mod.identity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.marscommand.mod.mission.MissionRules;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class ArrivalApi implements AutoCloseable {
    public record Context(String minecraftUuid, String callSign, boolean confirmed, String accountName, List<String> roles) {
        public void validate(UUID expected) {
            MissionRules.require(expected.toString().equals(minecraftUuid) && callSign != null && callSign.matches("[0-9]{4}"),
                    "Identity service returned a mismatched player");
            MissionRules.require(roles != null && roles.size() <= 64 && roles.stream()
                    .allMatch(role -> role != null && role.length() <= 64 && role.chars().noneMatch(Character::isISOControl)),
                    "Invalid remote player roles");
            MissionRules.require(!confirmed || (accountName != null && !accountName.isBlank() && accountName.length() <= 100
                    && accountName.chars().noneMatch(Character::isISOControl)), "Missing confirmed account name");
        }
    }

    public record Start(String status, Context context, String requestId, String deviceCode,
                        String verificationUri, int expiresIn, int pollInterval) {}
    public record Poll(String status, String accountName) {}

    public static final class Failure extends IOException {
        public final int status;
        public final long retrySeconds;

        Failure(int status, long retrySeconds) {
            super("Identity request failed (HTTP " + status + ")");
            this.status = status;
            this.retrySeconds = retrySeconds;
        }
    }

    private static final Gson JSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String token;

    public ArrivalApi(String token) {
        this.token = token;
    }

    public boolean configured() {
        return token != null && token.matches("[A-Za-z0-9_-]{32,128}");
    }

    public CompletableFuture<Start> start(IdentitySettings settings, UUID uuid, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraftUuid", uuid.toString());
        body.addProperty("playerName", name);
        return request(settings, "/api/auth/minecraft/arrival", body, Start.class).thenApply(result -> {
            MissionRules.require(result != null && result.context != null, "Missing arrival context");
            result.context.validate(uuid);
            if ("pending".equals(result.status)) {
                MissionRules.require(!result.context.confirmed && result.requestId != null && result.requestId.matches("[a-f0-9]{32}")
                        && result.deviceCode != null && result.deviceCode.matches("[A-Za-z0-9_-]{32,128}")
                        && result.verificationUri != null && result.verificationUri.equals(
                        settings.websiteOrigin() + "/auth/login?requestId=" + result.requestId)
                        && result.expiresIn > 0 && result.expiresIn <= 600
                        && result.pollInterval >= 5 && result.pollInterval <= 60, "Invalid arrival challenge");
            } else {
                MissionRules.require("confirmed".equals(result.status) && result.context.confirmed, "Invalid arrival status");
            }
            return result;
        });
    }

    public CompletableFuture<Poll> poll(IdentitySettings settings, UUID uuid, String deviceCode) {
        return request(settings, "/api/auth/minecraft/poll", challenge(uuid, deviceCode), Poll.class).thenApply(result -> {
            MissionRules.require(result != null && List.of("pending", "approved", "expired", "denied").contains(result.status),
                    "Invalid arrival poll status");
            MissionRules.require(!"approved".equals(result.status) || (result.accountName != null && !result.accountName.isBlank()
                    && result.accountName.length() <= 100 && result.accountName.chars().noneMatch(Character::isISOControl)),
                    "Missing approved account name");
            return result;
        });
    }

    public CompletableFuture<Context> confirm(IdentitySettings settings, UUID uuid, String code, String accountName) {
        JsonObject body = challenge(uuid, code);
        body.addProperty("expectedAccountName", accountName);
        return request(settings, "/api/auth/minecraft/confirm", body, Context.class).thenApply(result -> validated(result, uuid, true));
    }

    public CompletableFuture<Context> context(IdentitySettings settings, UUID uuid) {
        return request(settings, "/api/auth/minecraft/context/" + uuid, null, Context.class)
                .thenApply(result -> validated(result, uuid, false));
    }

    private static Context validated(Context context, UUID uuid, boolean requireConfirmed) {
        MissionRules.require(context != null, "Missing remote context");
        context.validate(uuid);
        MissionRules.require(!requireConfirmed || context.confirmed, "Identity service did not confirm arrival");
        return context;
    }

    private static JsonObject challenge(UUID uuid, String deviceCode) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraftUuid", uuid.toString());
        body.addProperty("deviceCode", deviceCode);
        return body;
    }

    private <T> CompletableFuture<T> request(IdentitySettings settings, String path, JsonObject body, Class<T> type) {
        MissionRules.require(configured(), "Server integration credential is missing or invalid");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.apiOrigin() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body), StandardCharsets.UTF_8));
        }
        return http.sendAsync(request.build(), ignored -> new LimitedBody()).thenApply(response -> {
            if (response.statusCode() != 200) {
                String retry = response.headers().firstValue("Retry-After").orElse("30");
                long seconds = retry.matches("[0-9]{1,7}") ? Math.clamp(Long.parseLong(retry), 1, 300) : 30;
                throw new CompletionException(new Failure(response.statusCode(), seconds));
            }
            try {
                var value = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
                MissionRules.require(value.isJsonObject(), "Identity response must be an object");
                JsonObject object = value.getAsJsonObject();
                if (type == Context.class) {
                    contextShape(object);
                } else {
                    stringField(object, "status", false);
                    if (type == Start.class) {
                        MissionRules.require(object.has("context") && object.get("context").isJsonObject(), "Missing arrival context");
                        contextShape(object.getAsJsonObject("context"));
                    } else if (type == Poll.class) {
                        stringField(object, "accountName", true);
                    }
                }
                return JSON.fromJson(value, type);
            } catch (JsonParseException exception) {
                throw new CompletionException(new IOException("Identity response is not valid JSON"));
            }
        });
    }

    private static void contextShape(JsonObject object) {
        stringField(object, "minecraftUuid", false);
        stringField(object, "callSign", false);
        stringField(object, "accountName", true);
        MissionRules.require(object.has("confirmed") && object.get("confirmed").isJsonPrimitive()
                && object.get("confirmed").getAsJsonPrimitive().isBoolean(), "Invalid confirmation field type");
        MissionRules.require(object.has("roles") && object.get("roles").isJsonArray(), "Missing role list");
        object.getAsJsonArray("roles").forEach(role -> MissionRules.require(role.isJsonPrimitive()
                && role.getAsJsonPrimitive().isString(), "Invalid role field type"));
    }

    private static void stringField(JsonObject object, String name, boolean nullable) {
        if (nullable && (!object.has(name) || object.get(name).isJsonNull())) {
            return;
        }
        MissionRules.require(object.has(name) && object.get(name).isJsonPrimitive()
                && object.get(name).getAsJsonPrimitive().isString(), "Invalid identity string field type");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 65536) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Identity response exceeds 64 KiB"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
