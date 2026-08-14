package net.messeb.volt;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class InMemoryExchange extends HttpExchange {
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final URI uri;
    private final String method;
    private final Map<String, Object> attributes = new HashMap<>();
    private InputStream requestBody;
    private OutputStream responseBody = new ByteArrayOutputStream();
    private int responseCode = -1;
    private boolean headersSent;
    private boolean closed;

    InMemoryExchange(String method, String path, Map<String, List<String>> headers, byte[] body) {
        this.method = method;
        uri = URI.create(path);
        headers.forEach((name, values) -> requestHeaders.put(name, new ArrayList<>(values)));
        if (body.length > 0 && requestHeaders.getFirst("Content-Length") == null) {
            requestHeaders.set("Content-Length", Integer.toString(body.length));
        }
        requestBody = new ByteArrayInputStream(body.clone());
    }

    Result result() {
        if (responseCode < 0 || !closed) {
            throw new IllegalStateException("test request did not complete a response");
        }
        if (!(responseBody instanceof ByteArrayOutputStream bytes)) {
            throw new IllegalStateException("test response stream was replaced");
        }
        return new Result(responseCode, responseHeaders, bytes.toByteArray());
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
        if (!headersSent || closed) {
            throw new IllegalStateException("response body is not writable");
        }
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int status, long responseLength) throws IOException {
        if (headersSent) {
            throw new IOException("response headers were already sent");
        }
        if (closed) {
            throw new IOException("exchange is closed");
        }
        responseCode = status;
        headersSent = true;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
        if (input != null) {
            requestBody = input;
        }
        if (output != null) {
            responseBody = output;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    record Result(int status, Map<String, List<String>> headers, byte[] body) {
    }
}
