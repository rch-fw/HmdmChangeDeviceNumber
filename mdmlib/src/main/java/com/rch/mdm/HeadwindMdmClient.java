package com.rch.mdm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Java port of {@code change_dev_number.py}.
 *
 * <p>Talks to a Headwind MDM server: authenticates (JWT), fetches a device by its
 * device number, renames it, and pushes a {@code configUpdated} notification.</p>
 *
 * <p>All methods perform blocking network I/O and therefore must <b>not</b> be called
 * on the Android main thread. Use {@link MdmService} for a callback-based wrapper,
 * or run these calls inside your own executor / coroutine / {@code WorkManager} job.</p>
 *
 * <p>Only depends on {@code org.json}, which ships with the Android platform.</p>
 */
public class HeadwindMdmClient {

    private final MdmConfig config;

    public HeadwindMdmClient(MdmConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    // ------------------------------------------------------------------
    // High-level operation
    // ------------------------------------------------------------------

    /**
     * End-to-end equivalent of the script's {@code main()}: authenticate, fetch the
     * device by {@code currentDeviceNumber}, change its number to {@code newDeviceNumber},
     * and notify the device that its configuration changed.
     *
     * @return the internal integer id of the device that was renamed
     */
    public long changeDeviceNumber(String currentDeviceNumber, String newDeviceNumber) throws MdmException {
        String token = authenticate();

        JSONObject device = fetchDevice(token, currentDeviceNumber);
        long deviceId = device.optLong("id", -1);

        updateDeviceNumber(token, device, currentDeviceNumber, newDeviceNumber);
        notifyConfigUpdated(token, newDeviceNumber);

        return deviceId;
    }

    // ------------------------------------------------------------------
    // Individual steps (public so callers can compose their own flow)
    // ------------------------------------------------------------------

    /** Headwind MDM requires the MD5 hash of the password in uppercase hex. */
    public static String md5Uppercase(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    /** Logs in and returns the JWT token. */
    public String authenticate() throws MdmException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("login", config.getUsername());
            payload.put("password", md5Uppercase(config.getPassword()));
        } catch (JSONException e) {
            throw new MdmException("Failed to build login payload", e);
        }

        JSONObject data = requestJsonObject("POST", "/rest/public/jwt/login", payload, null);

        // 1. token at root: {"id_token": "..."}
        String rootToken = data.optString("id_token", null);
        if (rootToken != null && !rootToken.isEmpty()) {
            return rootToken;
        }
        // 2. nested: {"status": "OK", "data": {"id_token": "..."}}
        if ("OK".equals(data.optString("status")) && data.optJSONObject("data") != null) {
            String nested = data.optJSONObject("data").optString("id_token", null);
            if (nested != null && !nested.isEmpty()) {
                return nested;
            }
        }
        throw new MdmException("Authentication failed: " + data);
    }

    /** Retrieves the complete device object by its current device number. */
    public JSONObject fetchDevice(String token, String deviceNumber) throws MdmException {
        JSONObject data = requestJsonObject(
                "GET", "/rest/private/devices/number/" + encodePathSegment(deviceNumber), null, token);

        // wrapped: {"status": "OK", "data": { ...device... }}
        if ("OK".equals(data.optString("status")) && data.optJSONObject("data") != null) {
            return data.optJSONObject("data");
        }
        // direct device object
        if (data.has("id") || data.has("number")) {
            return data;
        }
        throw new MdmException("Device '" + deviceNumber
                + "' not found or unexpected response structure: " + data);
    }

    /**
     * Updates the device object with the new device number via PUT.
     *
     * @param device the full device object previously returned by {@link #fetchDevice}
     *               (it is mutated in place, exactly like the Python version)
     * @return the raw JSON response from the server
     */
    public JSONObject updateDeviceNumber(String token, JSONObject device,
                                         String oldNumber, String newNumber) throws MdmException {
        try {
            device.put("number", newNumber);
            device.put("oldNumber", oldNumber);

            // 'info' must be sent back as a JSON string if the server returned it as an object
            Object info = device.opt("info");
            if (info instanceof JSONObject || info instanceof JSONArray) {
                device.put("info", info.toString());
            }
        } catch (JSONException e) {
            throw new MdmException("Failed to prepare device payload", e);
        }

        return requestJsonObject("PUT", "/rest/private/devices", device, token);
    }

    /**
     * Sends a {@code configUpdated} push so the device reloads its configuration.
     * Uses the <b>new</b> device number (server lookup is by {@code devices.number}).
     */
    public JSONObject notifyConfigUpdated(String token, String deviceNumber) throws MdmException {
        JSONObject body = new JSONObject();
        try {
            body.put("messageType", "configUpdated");     // PushMessage.TYPE_CONFIG_UPDATED
            body.put("deviceNumbers", new JSONArray().put(deviceNumber));
        } catch (JSONException e) {
            throw new MdmException("Failed to build push payload", e);
        }
        return requestJsonObject("POST", "/rest/private/push", body, token);
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private JSONObject requestJsonObject(String method, String path, JSONObject body, String token)
            throws MdmException {
        String raw = request(method, path, body == null ? null : body.toString(), token);
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            throw new MdmException("Unexpected (non-object) response from " + path + ": " + raw, e);
        }
    }

    private String request(String method, String path, String jsonBody, String token)
            throws MdmException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(config.getBaseUrl() + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(config.getTimeoutMillis());
            conn.setReadTimeout(config.getTimeoutMillis());
            conn.setRequestProperty("Accept", "application/json");
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
            }

            int status = conn.getResponseCode();
            String responseBody = readStream(
                    status >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (status >= 400) {
                throw new MdmException(
                        method + " " + path + " failed with HTTP " + status,
                        status, responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new MdmException(method + " " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static String encodePathSegment(String segment) {
        // device numbers are simple identifiers; encode spaces / reserved chars defensively
        try {
            return java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return segment;
        }
    }
}
