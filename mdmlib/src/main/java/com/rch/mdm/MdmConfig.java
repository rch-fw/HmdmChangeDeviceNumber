package com.rch.mdm;

/**
 * Connection settings for a Headwind MDM server.
 *
 * <p>Mirrors the {@code BASE_URL / USERNAME / PASSWORD} constants of the original
 * {@code change_dev_number.py} script.</p>
 */
public final class MdmConfig {

    private final String baseUrl;
    private final String username;
    private final String password;
    private int timeoutMillis = 15_000;

    public MdmConfig(String baseUrl, String username, String password) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        // equivalent of Python's base_url.rstrip('/')
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        this.username = username;
        this.password = password;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    /** Connect/read timeout applied to every request. Defaults to 15s. */
    public MdmConfig setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }
}
