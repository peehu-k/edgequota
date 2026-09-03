package com.edgequota.gateway;

import java.util.HashMap;
import java.util.Map;

/** A minimally-parsed HTTP/1.1 request: just enough for rate-limit decisions, not a general-purpose HTTP model. */
public final class HttpRequest {
    public String method = "GET";
    public String path = "/";
    public final Map<String, String> headers = new HashMap<>();
    public String body = "";

    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
}
