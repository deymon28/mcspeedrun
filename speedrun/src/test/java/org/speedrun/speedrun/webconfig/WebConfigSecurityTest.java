package org.speedrun.speedrun.webconfig;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigSecurityTest {
    @Test
    void acceptsBodiesUpToTheDocumentedLimit() throws IOException {
        assertEquals(0, WebConfigManager.readRequestBody(new ByteArrayInputStream(new byte[0])).length);
        byte[] body = new byte[WebConfigManager.MAX_REQUEST_BODY_BYTES];
        assertEquals(body.length, WebConfigManager.readRequestBody(new ByteArrayInputStream(body)).length);
    }

    @Test
    void rejectsOversizedBodiesWithoutReadingTheRemainingStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[WebConfigManager.MAX_REQUEST_BODY_BYTES + 64]);

        assertThrows(WebConfigManager.RequestBodyTooLargeException.class,
                () -> WebConfigManager.readRequestBody(input));

        assertEquals(63, input.available());
    }

    @Test
    void authenticationFailsClosedForMissingOrRetiredTokens() {
        assertFalse(WebConfigManager.tokensMatch(null, null));
        assertFalse(WebConfigManager.tokensMatch("", ""));
        assertFalse(WebConfigManager.tokensMatch("test-current-token", null));
        assertFalse(WebConfigManager.tokensMatch("test-current-token", "test-previous-token"));
        assertFalse(WebConfigManager.tokensMatch("test-current-token", "test-current-token "));
        assertTrue(WebConfigManager.tokensMatch("test-current-token", "test-current-token"));
    }

    @Test
    void rejectsApiPathSuffixesBeforeInvokingTheEndpoint() throws IOException {
        for (String path : new String[]{"/api/config/save-extra", "/api/config/save/", "/api/config/%73ave"}) {
            TestExchange exchange = new TestExchange(path);
            AtomicBoolean invoked = new AtomicBoolean();

            WebConfigManager.closedHandler("/api/config/save", request -> invoked.set(true)).handle(exchange);

            assertFalse(invoked.get());
            assertEquals(404, exchange.getResponseCode());
            assertTrue(exchange.closed);
        }
    }

    @Test
    void exactApiPathsStillAcceptQueryParametersAndCloseTheExchange() throws IOException {
        TestExchange exchange = new TestExchange("/api/config/save?mode=test");

        WebConfigManager.closedHandler("/api/config/save",
                request -> request.sendResponseHeaders(204, -1)).handle(exchange);

        assertEquals(204, exchange.getResponseCode());
        assertTrue(exchange.closed);
    }

    @Test
    void closesTheExchangeWhenAnEndpointFails() {
        TestExchange exchange = new TestExchange("/api/config/save");

        assertThrows(IOException.class, () -> WebConfigManager.closedHandler("/api/config/save", request -> {
            throw new IOException("Test endpoint failure");
        }).handle(exchange));

        assertTrue(exchange.closed);
    }

    private static final class TestExchange extends HttpExchange {
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int status = -1;
        private boolean closed;

        private TestExchange(String path) {
            uri = URI.create(path);
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int responseCode, long responseLength) { status = responseCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 2); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
