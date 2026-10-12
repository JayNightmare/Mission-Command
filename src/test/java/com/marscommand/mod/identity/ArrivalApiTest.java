package com.marscommand.mod.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ArrivalApiTest {
    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String KEY = "test-minecraft-token-" + "m".repeat(40);

    private static String context(boolean confirmed) {
        return "{\"minecraftUuid\":\"" + PLAYER + "\",\"callSign\":\"0427\",\"confirmed\":" + confirmed
                + ",\"accountName\":" + (confirmed ? "\"astronaut\"" : "null") + ",\"roles\":[]}";
    }

    @Test
    void clientUsesServerOnlyAuthAndValidatesTheCompleteLinkingContract() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/auth/minecraft", exchange -> {
            assertEquals("Bearer " + KEY, exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String response = path.endsWith("/arrival")
                    ? "{\"status\":\"pending\",\"context\":" + context(false) + ",\"requestId\":\"" + "a".repeat(32)
                    + "\",\"deviceCode\":\"" + "d".repeat(43) + "\",\"verificationUri\":\"https://site.example/auth/login?requestId="
                    + "a".repeat(32) + "\",\"expiresIn\":600,\"pollInterval\":5}"
                    : path.endsWith("/poll") ? "{\"status\":\"approved\",\"accountName\":\"astronaut\"}" : context(true);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (ArrivalApi api = new ArrivalApi(KEY)) {
            IdentitySettings settings = new IdentitySettings(true, "http://localhost:" + server.getAddress().getPort(), "https://site.example").validated(true);
            var challenge = api.start(settings, PLAYER, "Astronaut").get(5, TimeUnit.SECONDS);
            assertEquals("0427", challenge.context().callSign());
            assertEquals("approved", api.poll(settings, PLAYER, challenge.deviceCode()).get(5, TimeUnit.SECONDS).status());
            assertTrue(api.confirm(settings, PLAYER, challenge.deviceCode(), "astronaut").get(5, TimeUnit.SECONDS).confirmed());
            assertTrue(api.context(settings, PLAYER).get(5, TimeUnit.SECONDS).confirmed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedResponsesFailExplicitly() {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
        server.createContext("/", exchange -> {
            byte[] bytes = "x".repeat(65537).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (ArrivalApi api = new ArrivalApi(KEY)) {
            IdentitySettings settings = new IdentitySettings(true, "http://localhost:" + server.getAddress().getPort(), "https://site.example").validated(true);
            assertThrows(CompletionException.class, () -> api.context(settings, PLAYER).join());
        } finally {
            server.stop(0);
        }
    }
}
