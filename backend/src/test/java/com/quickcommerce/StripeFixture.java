package com.quickcommerce;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** An HTTP substitute for Stripe. The production SDK still sends and parses every request. */
final class StripeFixture implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final class Checkout {
        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final String session = "cs_test_" + suffix;
        final String intent = "pi_" + suffix;
        final String charge = "ch_" + suffix;
        final String reference;
        final long amount;
        final AtomicInteger captures = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();
        final AtomicInteger voids = new AtomicInteger();
        volatile String sessionState = "open";
        volatile String intentState = "requires_payment_method";
        volatile long received;
        volatile long externalRefundedCents;
        volatile boolean intentUnavailable;
        volatile boolean wrongCurrency;
        volatile boolean loseCaptureResponse;
        volatile boolean loseRefundResponse;

        Checkout(String reference, long amount) {
            this.reference = reference;
            this.amount = amount;
        }

        synchronized void authorize() {
            if (!"open".equals(sessionState)) {
                return;
            }
            intentState = "requires_capture";
            sessionState = "complete";
        }

        ObjectNode sessionJson() {
            var data = JSON.createObjectNode().put("object", "checkout.session").put("id", session)
                    .put("livemode", false).put("mode", "payment").put("currency", wrongCurrency ? "usd" : "cad")
                    .put("amount_total", amount).put("client_reference_id", reference).put("status", sessionState)
                    .put("url", "https://checkout.stripe.com/c/pay/" + session);
            if ("complete".equals(sessionState)) {
                data.put("payment_intent", intent);
            }
            return data;
        }

        ObjectNode intentJson() {
            var data = JSON.createObjectNode().put("object", "payment_intent").put("id", intent)
                    .put("livemode", false).put("currency", "cad").put("capture_method", "manual")
                    .put("status", intentState).put("amount", amount).put("amount_received", received)
                    .put("amount_capturable", "requires_capture".equals(intentState) ? amount : 0);
            data.putObject("metadata").put("quike_payment", reference);
            if ("succeeded".equals(intentState)) {
                data.put("latest_charge", charge);
            }
            return data;
        }
    }

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService network = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Checkout> sessions = new ConcurrentHashMap<>();
    private final Map<String, Checkout> intents = new ConcurrentHashMap<>();
    private final Map<String, Checkout> charges = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> idempotent = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> refundsByIntent = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> transfersByGroup = new ConcurrentHashMap<>();
    private final java.util.Set<String> cachedFailures = ConcurrentHashMap.newKeySet();
    final AtomicInteger opens = new AtomicInteger();
    final AtomicInteger maxOpenConcurrency = new AtomicInteger();
    final AtomicInteger transfers = new AtomicInteger();
    final AtomicBoolean loseTransferResponse = new AtomicBoolean();
    private final AtomicInteger opening = new AtomicInteger();

    StripeFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(network);
        server.createContext("/v1/", this::handle);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Checkout session(String id) {
        return sessions.get(id);
    }

    private void handle(HttpExchange request) throws IOException {
        try (request) {
            String[] path = request.getRequestURI().getPath().substring(4).split("/");
            String method = request.getRequestMethod();
            Map<String, String> form = form(request);
            if ("GET".equals(method) && request.getRequestURI().getRawQuery() != null) {
                decode(request.getRequestURI().getRawQuery(), form);
            }
            String key = request.getRequestHeaders().getFirst("Idempotency-Key");
            if ("POST".equals(method) && (key == null || !key.startsWith("quike-"))) {
                error(request, 400, "Missing application idempotency key");
                return;
            }
            if ("POST".equals(method) && cachedFailures.contains(key)) {
                error(request, 500, "Cached uncertain provider response");
                return;
            }
            if ("POST".equals(method) && idempotent.containsKey(key)) {
                reply(request, 200, idempotent.get(key));
                return;
            }
            switch (path[0]) {
                case "checkout" -> handleCheckout(request, path, form, key);
                case "payment_intents" -> handleIntent(request, path, form, key);
                case "charges" -> {
                    var charge = charges.get(path[1]);
                    if (charge == null) {
                        error(request, 404, "Unknown charge");
                        return;
                    }
                    reply(request, 200, JSON.createObjectNode().put("object", "charge").put("id", charge.charge)
                            .put("livemode", false).put("currency", "cad").put("paid", true)
                            .put("captured", true).put("disputed", false).put("amount_captured", charge.received)
                            .put("amount_refunded", charge.externalRefundedCents));
                }
                case "refunds" -> {
                    if ("GET".equals(method)) {
                        list(request, refundsByIntent.get(form.get("payment_intent")));
                    } else {
                        refund(request, form, key);
                    }
                }
                case "accounts" -> {
                    var data = JSON.createObjectNode().put("id", path[1]).put("object", "account").put("country", "CA");
                    data.putObject("capabilities").put("transfers", "active");
                    reply(request, 200, data);
                }
                case "transfers" -> {
                    if ("GET".equals(method)) {
                        list(request, transfersByGroup.get(form.get("transfer_group")));
                    } else {
                        transfer(request, form, key);
                    }
                }
                default -> error(request, 404, "Unknown resource");
            }
        }
    }

    private void handleCheckout(HttpExchange request, String[] path, Map<String, String> form, String key)
            throws IOException {
        if (path.length == 2) {
            int concurrent = opening.incrementAndGet();
            try {
                maxOpenConcurrency.accumulateAndGet(concurrent, Math::max);
                String capture = form.get("payment_intent_data[capture_method]");
                if (!"cad".equals(form.get("line_items[0][price_data][currency]"))
                        || !"manual".equals(capture)
                        || !"false".equals(form.get("adaptive_pricing[enabled]"))
                        || !"card".equals(form.get("allowed_payment_method_types[0]"))) {
                    error(request, 400, "Unexpected session properties");
                    return;
                }
                var checkout = new Checkout(form.get("client_reference_id"),
                        Long.parseLong(form.get("line_items[0][price_data][unit_amount]")));
                ObjectNode result = idempotent.putIfAbsent(key, checkout.sessionJson());
                if (result == null) {
                    sessions.put(checkout.session, checkout);
                    intents.put(checkout.intent, checkout);
                    charges.put(checkout.charge, checkout);
                    opens.incrementAndGet();
                    result = checkout.sessionJson();
                }
                reply(request, 200, result);
            } finally {
                opening.decrementAndGet();
            }
            return;
        }
        Checkout checkout = sessions.get(path[2]);
        if (checkout == null) {
            error(request, 404, "Unknown Checkout Session");
            return;
        }
        synchronized (checkout) {
            if (path.length == 4) {
                if (!"open".equals(checkout.sessionState)) {
                    error(request, 400, "Session no longer open");
                    return;
                }
                checkout.sessionState = "expired";
                idempotent.put(key, checkout.sessionJson());
            }
            reply(request, 200, checkout.sessionJson());
        }
    }

    private void handleIntent(HttpExchange request, String[] path, Map<String, String> form, String key)
            throws IOException {
        Checkout checkout = intents.get(path[1]);
        if (checkout == null) {
            error(request, 404, "Unknown PaymentIntent");
            return;
        }
        if (checkout.intentUnavailable) {
            error(request, 503, "Provider unavailable");
            return;
        }
        synchronized (checkout) {
            if (path.length == 3) {
                if (!"requires_capture".equals(checkout.intentState)) {
                    error(request, 409, "Authorization already resolved");
                    return;
                }
                if ("capture".equals(path[2])) {
                    if (form.containsKey("final_capture")) {
                        error(request, 400, "Multi-capture parameter was sent to a single-capture payment");
                        return;
                    }
                    checkout.received = Long.parseLong(form.get("amount_to_capture"));
                    checkout.intentState = "succeeded";
                    checkout.captures.incrementAndGet();
                } else {
                    checkout.intentState = "canceled";
                    checkout.voids.incrementAndGet();
                }
                idempotent.put(key, checkout.intentJson());
                if ("capture".equals(path[2]) && checkout.loseCaptureResponse) {
                    checkout.loseCaptureResponse = false;
                    error(request, 500, "Capture completed before connection failure");
                    return;
                }
            }
            reply(request, 200, checkout.intentJson());
        }
    }

    private void refund(HttpExchange request, Map<String, String> form, String key) throws IOException {
        Checkout checkout = intents.get(form.get("payment_intent"));
        if (checkout == null || checkout.received == 0) {
            error(request, 400, "No captured funds");
            return;
        }
        synchronized (checkout) {
            long amount = Long.parseLong(form.get("amount"));
            if (checkout.refunds.get() != 0 || amount != checkout.received) {
                error(request, 409, "Unexpected refund");
                return;
            }
            checkout.refunds.incrementAndGet();
            var data = JSON.createObjectNode().put("id", "re_" + checkout.suffix).put("object", "refund")
                    .put("payment_intent", checkout.intent).put("currency", "cad").put("status", "succeeded").put("amount", amount);
            data.putObject("metadata").put("quike_payment", checkout.reference);
            idempotent.put(key, data);
            refundsByIntent.put(checkout.intent, data);
            if (checkout.loseRefundResponse) {
                checkout.loseRefundResponse = false;
                cachedFailures.add(key);
                error(request, 500, "Refund completed before connection failure");
                return;
            }
            reply(request, 200, data);
        }
    }

    private void transfer(HttpExchange request, Map<String, String> form, String key) throws IOException {
        var data = JSON.createObjectNode().put("id", "tr_" + UUID.randomUUID()).put("object", "transfer")
                .put("destination", form.get("destination")).put("amount", Long.parseLong(form.get("amount")))
                .put("source_transaction", form.get("source_transaction")).put("currency", "cad")
                .put("livemode", false).put("reversed", false).put("transfer_group", form.get("transfer_group"));
        data.putObject("metadata").put("quike_transfer", form.get("metadata[quike_transfer]"));
        var existing = idempotent.putIfAbsent(key, data);
        if (existing == null) {
            transfers.incrementAndGet();
            transfersByGroup.put(form.get("transfer_group"), data);
            if (loseTransferResponse.compareAndSet(true, false)) {
                cachedFailures.add(key);
                error(request, 500, "Transfer completed before connection failure");
                return;
            }
        }
        reply(request, 200, existing == null ? data : existing);
    }

    private static Map<String, String> form(HttpExchange request) throws IOException {
        String raw = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = new java.util.HashMap<>();
        decode(raw, fields);
        return fields;
    }

    private static void decode(String raw, Map<String, String> fields) {
        if (!raw.isEmpty()) {
            for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
    }

    private static void list(HttpExchange request, ObjectNode entry) throws IOException {
        var collection = JSON.createObjectNode().put("object", "list").put("has_more", false);
        var data = collection.putArray("data");
        if (entry != null) {
            data.add(entry);
        }
        reply(request, 200, collection);
    }

    private static void error(HttpExchange request, int status, String detail) throws IOException {
        var data = JSON.createObjectNode();
        data.putObject("error").put("type", "invalid_request_error").put("message", detail);
        reply(request, status, data);
    }

    private static void reply(HttpExchange request, int status, ObjectNode json) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(json);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, bytes.length);
        request.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        network.shutdownNow();
    }
}
