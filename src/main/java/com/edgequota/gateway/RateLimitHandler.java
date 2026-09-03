package com.edgequota.gateway;

import com.edgequota.cost.CostEstimator;
import com.edgequota.cost.RequestCost;
import com.edgequota.quota.QuotaDecision;
import com.edgequota.quota.QuotaManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The actual per-request decision point. Everything upstream of this class
 * (transport, HTTP parsing) exists to get a tenant id, a path, and a body
 * here; everything downstream ({@link QuotaManager}) is pure local
 * decision-making with no request-path network calls, which is the whole
 * point of the "no coordinator on the request path" design.
 */
public final class RateLimitHandler {

    private final QuotaManager quotaManager;
    private final CostEstimator costEstimator;

    public RateLimitHandler(QuotaManager quotaManager, CostEstimator costEstimator) {
        this.quotaManager = quotaManager;
        this.costEstimator = costEstimator;
    }

    public void handle(HttpRequest request, OutputStream out) throws IOException {
        String tenantId = request.header("x-tenant-id");
        if (tenantId == null || tenantId.isEmpty()) {
            writeResponse(out, 400, "{\"error\":\"missing X-Tenant-Id header\"}");
            return;
        }

        boolean isGraphQl = request.path.startsWith("/graphql");
        RequestCost cost = costEstimator.estimate(request.body, isGraphQl);

        QuotaDecision decision = quotaManager.tryAdmit(tenantId, cost.weightedCost);

        if (decision.admitted) {
            String body = String.format(java.util.Locale.ROOT,
                    "{\"status\":\"admitted\",\"cost\":%.3f,\"tokenCount\":%.1f,\"graphQlComplexity\":%.1f,"
                            + "\"localBudgetRemaining\":%.3f,\"globalEstimate\":%.3f}",
                    cost.weightedCost, cost.tokenCount, cost.graphQlComplexity,
                    decision.localBudgetRemaining, decision.globalEstimate);
            writeResponse(out, 200, body);
        } else {
            String body = String.format(java.util.Locale.ROOT,
                    "{\"status\":\"rejected\",\"reason\":\"%s\",\"cost\":%.3f,"
                            + "\"globalEstimate\":%.3f,\"hardCap\":%.3f}",
                    decision.reason, cost.weightedCost, decision.globalEstimate, decision.hardCap);
            writeResponse(out, 429, body);
        }
    }

    private void writeResponse(OutputStream out, int statusCode, String jsonBody) throws IOException {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String statusText = statusCode == 200 ? "OK" : statusCode == 429 ? "Too Many Requests" : "Bad Request";
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}
