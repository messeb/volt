package net.messeb.volt;

import java.util.Objects;
import java.util.function.Consumer;

public final class RouteGroup {
    private final Volt volt;
    private final String prefix;

    RouteGroup(Volt volt, String prefix) {
        this.volt = Objects.requireNonNull(volt, "volt");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    public RouteGroup get(String path, Handler handler, RouteOption... options) {
        volt.get(join(path), handler, options);
        return this;
    }

    public RouteGroup post(String path, Handler handler, RouteOption... options) {
        volt.post(join(path), handler, options);
        return this;
    }

    public RouteGroup put(String path, Handler handler, RouteOption... options) {
        volt.put(join(path), handler, options);
        return this;
    }

    public RouteGroup delete(String path, Handler handler, RouteOption... options) {
        volt.delete(join(path), handler, options);
        return this;
    }

    public RouteGroup patch(String path, Handler handler, RouteOption... options) {
        volt.patch(join(path), handler, options);
        return this;
    }

    public RouteGroup head(String path, Handler handler, RouteOption... options) {
        volt.head(join(path), handler, options);
        return this;
    }

    public RouteGroup options(String path, Handler handler, RouteOption... options) {
        volt.options(join(path), handler, options);
        return this;
    }

    public RouteGroup path(String path, Consumer<RouteGroup> routes) {
        Objects.requireNonNull(routes, "routes").accept(new RouteGroup(volt, join(path)));
        return this;
    }

    private String join(String path) {
        if (path == null || path.isBlank() || path.charAt(0) != '/') {
            throw new IllegalArgumentException("grouped route path must start with '/'");
        }
        if (path.equals("/")) {
            return prefix;
        }
        return prefix.equals("/") ? path : prefix + path;
    }
}
