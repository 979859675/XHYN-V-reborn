// SPDX-License-Identifier: MIT
package com.xhynv.online;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Blocking JSON client for the lobby server. Never call it on the UI thread. */
final class Api {
    private final String base;

    Api(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    Map<String, Object> call(String method, String path, Object body, String token) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "/api" + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = connection.getOutputStream();
                try { out.write(bytes); } finally { out.close(); }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            Map<String, Object> data;
            try {
                data = Json.object(read(in));
            } catch (IllegalArgumentException e) {
                // Not our JSON: a proxy error page, a captive portal, or the wrong server.
                throw new ApiException(status >= 400 ? status : 502, "联机中心返回了无法识别的内容（" + status + "）");
            }
            if (status >= 200 && status < 300) return data;
            String message = Json.string(Json.map(data, "error"), "message");
            throw new ApiException(status, message.isEmpty() ? "联机中心出错（" + status + "）" : message);
        } catch (IOException e) {
            throw new ApiException(0, "无法连接联机中心，请检查网络");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > (1 << 20)) throw new IOException("response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    static Map<String, Object> empty() { return new LinkedHashMap<String, Object>(); }
}
