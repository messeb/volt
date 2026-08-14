package net.messeb.volt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class Volt implements AutoCloseable {
    private static final int DEFAULT_PORT = 8080;
    private static final long DEFAULT_MAX_BODY_SIZE = 1_048_576;
    private static final int DEFAULT_MAX_HEADER_COUNT = 100;
    private static final long DEFAULT_MAX_HEADER_BYTES = 65_536;
    private static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private static final System.Logger LOGGER = System.getLogger(Volt.class.getName());
    private static final Comparator<RouteCandidate> ROUTE_SPECIFICITY = (first, second) -> {
        int specificity = first.route().pattern().compareSpecificity(second.route().pattern());
        return specificity != 0
                ? specificity
                : Long.compare(second.route().index(), first.route().index());
    };

    private final int configuredPort;
    private final Object lifecycleLock = new Object();
    private final Object routeLock = new Object();
    private final Object filterLock = new Object();
    private final Object optionLock = new Object();
    private final Object exceptionLock = new Object();
    private final Object inFlightLock = new Object();
    private volatile List<Route> routes = List.of();
    private volatile List<Route> activeRoutes = List.of();
    private volatile List<Filter> beforeFilters = List.of();
    private volatile List<Filter> afterFilters = List.of();
    private volatile List<Cors> corsPolicies = List.of();
    private volatile List<RouteOption> defaultRouteOptions = List.of();
    private volatile List<MapperRegistration<?>> exceptionMappers = List.of();
    private volatile long maxBodySize = DEFAULT_MAX_BODY_SIZE;
    private volatile int maxHeaderCount = DEFAULT_MAX_HEADER_COUNT;
    private volatile long maxHeaderBytes = DEFAULT_MAX_HEADER_BYTES;
    private volatile JsonCodec jsonCodec = JsonCodec.defaultCodec();
    private volatile boolean installShutdownHook = true;
    private volatile State state = State.NEW;
    private volatile HttpServer transport;
    private ExecutorService executor;
    private Thread shutdownHook;
    private long nextRouteIndex;
    private int inFlightRequests;

    private Volt(int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        configuredPort = port;
    }

    public static Volt create() {
        return create(DEFAULT_PORT);
    }

    public static Volt create(int port) {
        return new Volt(port);
    }

    public static Volt port(int port) {
        return create(port);
    }

    public Volt maxBodySize(long bytes) {
        if (bytes < 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximum body size must be between 0 and " + Integer.MAX_VALUE);
        }
        synchronized (lifecycleLock) {
            requireNew("maximum body size");
            maxBodySize = bytes;
        }
        return this;
    }

    public Volt maxRequestHeaders(int count, long bytes) {
        if (count < 1) {
            throw new IllegalArgumentException("maximum header count must be positive");
        }
        if (bytes < 1) {
            throw new IllegalArgumentException("maximum header bytes must be positive");
        }
        synchronized (lifecycleLock) {
            requireNew("request header limits");
            maxHeaderCount = count;
            maxHeaderBytes = bytes;
        }
        return this;
    }

    public Volt jsonCodec(JsonCodec codec) {
        Objects.requireNonNull(codec, "codec");
        synchronized (lifecycleLock) {
            requireNew("JSON codec");
            jsonCodec = codec;
        }
        return this;
    }

    public Volt shutdownHook(boolean enabled) {
        synchronized (lifecycleLock) {
            requireNew("shutdown hook configuration");
            installShutdownHook = enabled;
        }
        return this;
    }

    public Volt routeOption(RouteOption option) {
        RouteOption checked = validateOption(option);
        synchronized (lifecycleLock) {
            requireNew("route options");
            synchronized (optionLock) {
                var updated = new ArrayList<>(defaultRouteOptions);
                updated.removeIf(existing -> existing.key().equals(checked.key()));
                updated.add(checked);
                defaultRouteOptions = List.copyOf(updated);
            }
        }
        return this;
    }

    public Volt apiKey(Predicate<String> validator) {
        return routeOption(Auth.apiKey(validator));
    }

    public Volt apiKey(String headerName, Predicate<String> validator) {
        return routeOption(Auth.apiKey(headerName, validator));
    }

    public Volt apiKeyQuery(String parameterName, Predicate<String> validator) {
        return routeOption(Auth.query(parameterName, validator));
    }

    public Volt bearerAuth(Predicate<String> validator) {
        return routeOption(Auth.bearer(validator));
    }

    public Volt cors(Cors cors) {
        Objects.requireNonNull(cors, "cors");
        Filter filter = new Filter(PathPattern.compile("/*"), cors::apply);
        synchronized (lifecycleLock) {
            requireNew("CORS configuration");
            synchronized (filterLock) {
                var updatedPolicies = new ArrayList<>(corsPolicies);
                updatedPolicies.add(cors);
                corsPolicies = List.copyOf(updatedPolicies);

                var updatedFilters = new ArrayList<>(beforeFilters);
                updatedFilters.add(filter);
                beforeFilters = List.copyOf(updatedFilters);
            }
        }
        return this;
    }

    public Volt get(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.GET, path, handler, options);
    }

    public Volt post(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.POST, path, handler, options);
    }

    public Volt put(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.PUT, path, handler, options);
    }

    public Volt delete(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.DELETE, path, handler, options);
    }

    public Volt patch(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.PATCH, path, handler, options);
    }

    public Volt head(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.HEAD, path, handler, options);
    }

    public Volt options(String path, Handler handler, RouteOption... options) {
        return route(HttpMethod.OPTIONS, path, handler, options);
    }

    public Volt route(HttpMethod method, String path, Handler handler, RouteOption... options) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        PathPattern pattern = PathPattern.compile(path);
        List<RouteOption> checkedOptions = validateOptions(options);
        synchronized (lifecycleLock) {
            requireNew("route registration");
            synchronized (routeLock) {
                boolean duplicate = routes.stream().anyMatch(route ->
                        route.method() == method && route.pattern().shape().equals(pattern.shape()));
                if (duplicate) {
                    throw new IllegalArgumentException("duplicate " + method + " route: " + path);
                }
                var updated = new ArrayList<>(routes);
                updated.add(new Route(method, pattern, handler, checkedOptions, nextRouteIndex++));
                routes = List.copyOf(updated);
            }
        }
        return this;
    }

    public Volt path(String prefix, Consumer<RouteGroup> routes) {
        Objects.requireNonNull(routes, "routes");
        PathPattern pattern = PathPattern.compile(prefix);
        if (pattern.wildcard()) {
            throw new IllegalArgumentException("route group prefix must not contain a wildcard");
        }
        if (prefix.length() > 1 && prefix.endsWith("/")) {
            throw new IllegalArgumentException("route group prefix must not end with '/'");
        }
        synchronized (lifecycleLock) {
            requireNew("route group registration");
            routes.accept(new RouteGroup(this, prefix));
        }
        return this;
    }

    public Volt before(Handler handler) {
        return before("/*", handler);
    }

    public Volt before(String path, Handler handler) {
        addFilter(true, path, handler);
        return this;
    }

    public Volt after(Handler handler) {
        return after("/*", handler);
    }

    public Volt after(String path, Handler handler) {
        addFilter(false, path, handler);
        return this;
    }

    public <T extends Throwable> Volt exception(Class<T> type, ExceptionMapper<T> mapper) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
        synchronized (lifecycleLock) {
            requireNew("exception mappers");
            synchronized (exceptionLock) {
                var updated = new ArrayList<>(exceptionMappers);
                updated.removeIf(existing -> existing.type() == type);
                updated.add(new MapperRegistration<>(type, mapper));
                exceptionMappers = List.copyOf(updated);
            }
        }
        return this;
    }

    public Volt start() {
        synchronized (lifecycleLock) {
            if (state == State.STARTED) {
                throw new IllegalStateException("server is already running");
            }
            if (state != State.NEW) {
                throw new IllegalStateException("a server that has already been used cannot be started");
            }

            List<Route> resolvedRoutes = resolveRouteHandlers();
            ExecutorService newExecutor = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("volt-request-", 0).factory());
            HttpServer newTransport = null;
            try {
                newTransport = HttpServer.create(new InetSocketAddress(configuredPort), 0);
                newTransport.createContext("/", this::handleExchange);
                newTransport.setExecutor(newExecutor);
                activeRoutes = resolvedRoutes;
                newTransport.start();
            } catch (IOException exception) {
                activeRoutes = List.of();
                newExecutor.shutdownNow();
                throw new UncheckedIOException("failed to bind Volt server to port " + configuredPort, exception);
            } catch (RuntimeException exception) {
                activeRoutes = List.of();
                if (newTransport != null) {
                    newTransport.stop(0);
                }
                newExecutor.shutdownNow();
                throw exception;
            }

            transport = newTransport;
            executor = newExecutor;
            state = State.STARTED;
            if (installShutdownHook) {
                shutdownHook = new Thread(() -> stop(DEFAULT_SHUTDOWN_GRACE), "volt-shutdown");
                try {
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                } catch (RuntimeException exception) {
                    newTransport.stop(0);
                    newExecutor.shutdownNow();
                    transport = null;
                    executor = null;
                    shutdownHook = null;
                    activeRoutes = List.of();
                    state = State.NEW;
                    throw exception;
                }
            }
            return this;
        }
    }

    public boolean isRunning() {
        return state == State.STARTED;
    }

    public int localPort() {
        if (state != State.STARTED) {
            throw new IllegalStateException("server is not running");
        }
        HttpServer current = transport;
        if (current == null) {
            throw new IllegalStateException("server is not running");
        }
        return current.getAddress().getPort();
    }

    public InetSocketAddress localAddress() {
        if (state != State.STARTED) {
            throw new IllegalStateException("server is not running");
        }
        HttpServer current = transport;
        if (current == null) {
            throw new IllegalStateException("server is not running");
        }
        return current.getAddress();
    }

    public Volt stop(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod");
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("grace period must not be negative");
        }
        synchronized (lifecycleLock) {
            if (state == State.STOPPED) {
                return this;
            }
            if (state == State.NEW) {
                state = State.STOPPED;
                return this;
            }
            int seconds = delaySeconds(gracePeriod);
            long deadline = deadline(gracePeriod);
            HttpServer currentTransport = transport;
            ExecutorService currentExecutor = executor;
            state = State.STOPPING;
            try {
                if (currentTransport != null) {
                    currentTransport.stop(seconds);
                }
                boolean drained = awaitInFlight(deadline);
                if (currentExecutor != null) {
                    currentExecutor.shutdown();
                    if (!drained || !awaitTermination(currentExecutor, deadline)) {
                        currentExecutor.shutdownNow();
                    }
                }
            } finally {
                Thread hook = shutdownHook;
                shutdownHook = null;
                transport = null;
                executor = null;
                activeRoutes = List.of();
                state = State.STOPPED;
                if (hook != null && hook != Thread.currentThread()) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (IllegalStateException ignored) {
                        LOGGER.log(System.Logger.Level.DEBUG, "JVM shutdown is already in progress");
                    }
                }
            }
            return this;
        }
    }

    public Volt stop() {
        return stop(Duration.ZERO);
    }

    @Override
    public void close() {
        stop(DEFAULT_SHUTDOWN_GRACE);
    }

    private void handleExchange(HttpExchange exchange) {
        requestStarted();
        processExchange(exchange);
    }

    void dispatchForTest(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        synchronized (lifecycleLock) {
            if (state == State.NEW) {
                activeRoutes = resolveRouteHandlers();
                state = State.TESTING;
            } else if (state == State.STOPPING || state == State.STOPPED) {
                throw new IllegalStateException("server is stopped");
            }
            requestStarted();
        }
        processExchange(exchange);
    }

    private void processExchange(HttpExchange exchange) {
        try {
            Context context = new Context(exchange, maxBodySize, jsonCodec);
            PathPattern.RequestPath requestPath = null;
            try {
                validateHeaders(exchange);
                requestPath = PathPattern.RequestPath.parse(context.uri().getRawPath());
                dispatch(context, requestPath);
            } catch (VirtualMachineError | LinkageError fatal) {
                exchange.close();
                throw fatal;
            } catch (Throwable failure) {
                try {
                    handleFailure(failure, context);
                } catch (VirtualMachineError | LinkageError fatal) {
                    exchange.close();
                    throw fatal;
                }
            }
            if (requestPath != null) {
                try {
                    runFilters(afterFilters, requestPath, context, false);
                } catch (VirtualMachineError | LinkageError fatal) {
                    exchange.close();
                    throw fatal;
                } catch (Throwable failure) {
                    try {
                        handleFailure(failure, context);
                    } catch (VirtualMachineError | LinkageError fatal) {
                        exchange.close();
                        throw fatal;
                    }
                }
            }
            try {
                context.commit();
            } catch (IOException exception) {
                exchange.close();
                LOGGER.log(System.Logger.Level.WARNING, "failed to write HTTP response", exception);
            } catch (RuntimeException exception) {
                exchange.close();
                LOGGER.log(System.Logger.Level.ERROR, "invalid HTTP response", exception);
            }
        } finally {
            requestFinished();
        }
    }

    private void dispatch(Context context, PathPattern.RequestPath requestPath) throws Exception {
        runFilters(beforeFilters, requestPath, context, true);
        if (context.responded()) {
            return;
        }

        var candidates = new ArrayList<RouteCandidate>();
        for (Route route : activeRoutes) {
            PathPattern.Match match = route.pattern().match(requestPath);
            if (match != null) {
                candidates.add(new RouteCandidate(route, match));
            }
        }
        if (candidates.isEmpty()) {
            throw new NotFound(null);
        }

        String requestMethod = context.method();
        RouteCandidate selected = candidates.stream()
                .filter(candidate -> candidate.route().method().name().equals(requestMethod))
                .max(ROUTE_SPECIFICITY)
                .orElse(null);
        if (selected == null && requestMethod.equals("HEAD")) {
            selected = candidates.stream()
                    .filter(candidate -> candidate.route().method() == HttpMethod.GET)
                    .max(ROUTE_SPECIFICITY)
                    .orElse(null);
        }
        if (selected == null) {
            throw new MethodNotAllowed(allowedMethods(candidates));
        }

        context.pathParameters(selected.match().parameters());
        selected.route().handler().handle(context);
    }

    private void runFilters(
            List<Filter> filters,
            PathPattern.RequestPath path,
            Context context,
            boolean stopOnResponse) throws Exception {
        for (Filter filter : filters) {
            if (filter.pattern().match(path) != null) {
                filter.handler().handle(context);
                if (stopOnResponse && context.responded()) {
                    break;
                }
            }
        }
    }

    private Handler effectiveHandler(Route route) {
        var effective = new LinkedHashMap<String, RouteOption>();
        for (RouteOption option : defaultRouteOptions) {
            effective.put(option.key(), option);
        }
        for (RouteOption option : route.options()) {
            effective.put(option.key(), option);
        }
        var ordered = new ArrayList<>(effective.values());
        ordered.sort(Comparator.comparingInt(RouteOption::order));
        Handler handler = route.handler();
        for (int index = ordered.size() - 1; index >= 0; index--) {
            handler = Objects.requireNonNull(ordered.get(index).wrap(handler), "wrapped route handler");
        }
        return handler;
    }

    private List<Route> resolveRouteHandlers() {
        var resolved = new ArrayList<Route>(routes.size());
        for (Route route : routes) {
            resolved.add(new Route(
                    route.method(),
                    route.pattern(),
                    effectiveHandler(route),
                    List.of(),
                    route.index()));
        }
        return List.copyOf(resolved);
    }

    private List<String> allowedMethods(List<RouteCandidate> candidates) {
        EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
        candidates.forEach(candidate -> methods.add(candidate.route().method()));
        if (methods.contains(HttpMethod.GET)) {
            methods.add(HttpMethod.HEAD);
        }
        return methods.stream().map(Enum::name).toList();
    }

    private void handleFailure(Throwable failure, Context context) {
        MapperRegistration<?> registration = findMapper(failure);
        if (failure instanceof HttpException
                && registration != null
                && !HttpException.class.isAssignableFrom(registration.type())) {
            registration = null;
        }
        try {
            context.resetResponse();
            if (registration != null) {
                registration.handle(failure, context);
            } else if (failure instanceof HttpException httpException) {
                context.status(httpException.status());
                httpException.headers().forEach(context::header);
                context.errorJson(httpException.getMessage());
            } else {
                LOGGER.log(System.Logger.Level.ERROR, "uncaught request failure", failure);
                context.status(500);
                context.errorJson("internal server error");
            }
        } catch (VirtualMachineError | LinkageError fatal) {
            throw fatal;
        } catch (Throwable mapperFailure) {
            LOGGER.log(System.Logger.Level.ERROR, "exception mapper failed", mapperFailure);
            LOGGER.log(System.Logger.Level.ERROR, "original request failure", failure);
            context.resetResponse();
            context.status(500);
            context.errorJson("internal server error");
        }
        for (Cors cors : corsPolicies) {
            try {
                cors.applyErrorHeaders(context);
            } catch (VirtualMachineError | LinkageError fatal) {
                throw fatal;
            } catch (Throwable corsFailure) {
                LOGGER.log(System.Logger.Level.WARNING, "failed to apply CORS error headers", corsFailure);
            }
        }
    }

    private MapperRegistration<?> findMapper(Throwable failure) {
        MapperRegistration<?> best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (MapperRegistration<?> candidate : exceptionMappers) {
            if (candidate.type().isInstance(failure)) {
                int distance = typeDistance(failure.getClass(), candidate.type());
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private int typeDistance(Class<?> actual, Class<?> target) {
        if (actual == target) {
            return 0;
        }
        var queue = new ArrayDeque<Class<?>>();
        var distances = new ArrayDeque<Integer>();
        var visited = new HashSet<Class<?>>();
        queue.add(actual);
        distances.add(0);
        visited.add(actual);
        while (!queue.isEmpty()) {
            Class<?> current = queue.remove();
            int distance = distances.remove();
            Class<?> parent = current.getSuperclass();
            if (parent != null && visited.add(parent)) {
                if (parent == target) {
                    return distance + 1;
                }
                queue.add(parent);
                distances.add(distance + 1);
            }
            for (Class<?> contract : current.getInterfaces()) {
                if (visited.add(contract)) {
                    if (contract == target) {
                        return distance + 1;
                    }
                    queue.add(contract);
                    distances.add(distance + 1);
                }
            }
        }
        return Integer.MAX_VALUE - 1;
    }

    private void validateHeaders(HttpExchange exchange) {
        int count = 0;
        long bytes = 0;
        for (var entry : exchange.getRequestHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                count++;
                bytes += entry.getKey().getBytes(StandardCharsets.ISO_8859_1).length;
                bytes += value.getBytes(StandardCharsets.ISO_8859_1).length;
                if (count > maxHeaderCount || bytes > maxHeaderBytes) {
                    throw new RequestHeaderFieldsTooLarge("request headers exceed configured limits");
                }
            }
        }
    }

    private void addFilter(boolean before, String path, Handler handler) {
        Filter filter = new Filter(PathPattern.compile(path), Objects.requireNonNull(handler, "handler"));
        synchronized (lifecycleLock) {
            requireNew(before ? "before filters" : "after filters");
            synchronized (filterLock) {
                if (before) {
                    var updated = new ArrayList<>(beforeFilters);
                    updated.add(filter);
                    beforeFilters = List.copyOf(updated);
                } else {
                    var updated = new ArrayList<>(afterFilters);
                    updated.add(filter);
                    afterFilters = List.copyOf(updated);
                }
            }
        }
    }

    private List<RouteOption> validateOptions(RouteOption[] options) {
        if (options == null) {
            throw new IllegalArgumentException("route options must not be null");
        }
        var checked = new ArrayList<RouteOption>(options.length);
        var keys = new java.util.HashSet<String>();
        for (RouteOption option : options) {
            RouteOption value = validateOption(option);
            if (!keys.add(value.key())) {
                throw new IllegalArgumentException("duplicate route option key: " + value.key());
            }
            checked.add(value);
        }
        return List.copyOf(checked);
    }

    private RouteOption validateOption(RouteOption option) {
        Objects.requireNonNull(option, "route option");
        String key = Objects.requireNonNull(option.key(), "route option key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("route option key must not be blank");
        }
        return option;
    }

    private void requireNew(String subject) {
        if (state != State.NEW) {
            throw new IllegalStateException(subject + " cannot be changed after the server starts");
        }
    }

    private int delaySeconds(Duration duration) {
        if (duration.isZero()) {
            return 0;
        }
        long seconds = duration.getSeconds();
        if (seconds > Integer.MAX_VALUE
                || seconds == Integer.MAX_VALUE && duration.getNano() > 0) {
            throw new IllegalArgumentException("grace period is too large");
        }
        return (int) seconds;
    }

    JsonCodec jsonCodecForTest() {
        return jsonCodec;
    }

    private void requestStarted() {
        synchronized (inFlightLock) {
            inFlightRequests++;
        }
    }

    private void requestFinished() {
        synchronized (inFlightLock) {
            inFlightRequests--;
            if (inFlightRequests == 0) {
                inFlightLock.notifyAll();
            }
        }
    }

    private boolean awaitInFlight(long deadline) {
        synchronized (inFlightLock) {
            while (inFlightRequests > 0) {
                long remaining = remainingNanos(deadline);
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(inFlightLock, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private boolean awaitTermination(ExecutorService currentExecutor, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) {
            return currentExecutor.isTerminated();
        }
        try {
            return currentExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long deadline(Duration duration) {
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        long result = now + nanos;
        return result < 0 && now > 0 ? Long.MAX_VALUE : result;
    }

    private long remainingNanos(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
    }

    private enum State {
        NEW,
        STARTED,
        TESTING,
        STOPPING,
        STOPPED
    }

    private record Route(
            HttpMethod method,
            PathPattern pattern,
            Handler handler,
            List<RouteOption> options,
            long index) {
    }

    private record RouteCandidate(Route route, PathPattern.Match match) {
    }

    private record Filter(PathPattern pattern, Handler handler) {
    }

    private record MapperRegistration<T extends Throwable>(Class<T> type, ExceptionMapper<T> mapper) {
        void handle(Throwable failure, Context context) throws Exception {
            mapper.handle(type.cast(failure), context);
        }
    }
}
