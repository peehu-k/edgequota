package com.edgequota.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Deliberately minimal HTTP/1.1 request-line + headers + fixed-length-body
 * parser. No chunked transfer encoding, no keep-alive pipelining, no HTTP/2
 * -- EdgeQuota's gateway only needs enough of the protocol to read a
 * tenant-identifying header, the request path, and a body to cost-estimate
 * against; a production deployment would sit this logic behind a real edge
 * proxy (Envoy/nginx) or, if used standalone, swap this parser for a
 * battle-tested one (e.g. Netty's HttpObjectAggregator) without touching
 * any of the rate-limiting logic downstream, since the coupling point is
 * just the plain {@link HttpRequest} value object.
 */
final class HttpRequestParser {

    private HttpRequestParser() {
    }

    static HttpRequest parse(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        HttpRequest req = new HttpRequest();

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length >= 2) {
            req.method = parts[0];
            req.path = parts[1];
        }

        String line;
        int contentLength = 0;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String name = line.substring(0, idx).trim().toLowerCase(java.util.Locale.ROOT);
                String value = line.substring(idx + 1).trim();
                req.headers.put(name, value);
                if (name.equals("content-length")) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }

        if (contentLength > 0) {
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            req.body = new String(buf, 0, read);
        }

        return req;
    }
}
