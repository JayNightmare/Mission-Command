package com.marscommand.mod.identity;

import java.net.URI;
import java.util.Locale;

public record IdentitySettings(boolean websiteLinkEnabled, String apiOrigin, String websiteOrigin) {
    public static IdentitySettings defaults() {
        return new IdentitySettings(false, "https://api.nexusgit.info", "https://mars.nexusgit.info");
    }

    public IdentitySettings validated(boolean allowLocalHttp) {
        return new IdentitySettings(websiteLinkEnabled, origin(apiOrigin, allowLocalHttp),
                origin(websiteOrigin, allowLocalHttp));
    }

    public static String validOrigin(String value, boolean allowLocalHttp) {
        return origin(value, allowLocalHttp);
    }

    private static String origin(String value, boolean allowLocalHttp) {
        if (value == null || value.length() > 512 || value.chars().anyMatch(character -> character <= 32 || character == 127)) {
            throw new IllegalArgumentException("Invalid identity service origin");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed identity service origin");
        }
        String host = uri.getHost();
        boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("[::1]") || host.equals("::1"));
        if (host == null || uri.getScheme() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                || !(uri.getScheme().equals("https") || (allowLocalHttp && local && uri.getScheme().equals("http")))
                || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Use an HTTPS origin without a path, credentials, query or fragment");
        }
        String result = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port != -1 && !((port == 443 && uri.getScheme().equals("https")) || (port == 80 && uri.getScheme().equals("http")))) {
            result += ":" + port;
        }
        return result;
    }
}
