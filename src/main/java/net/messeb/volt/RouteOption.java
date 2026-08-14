package net.messeb.volt;

import java.util.Objects;
import java.util.function.UnaryOperator;

@FunctionalInterface
public interface RouteOption {
    Handler wrap(Handler next);

    default String key() {
        return getClass().getName();
    }

    default int order() {
        return 0;
    }

    static RouteOption of(String key, UnaryOperator<Handler> wrapper) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(wrapper, "wrapper");
        if (key.isBlank()) {
            throw new IllegalArgumentException("route option key must not be blank");
        }
        return new RouteOption() {
            @Override
            public Handler wrap(Handler next) {
                return Objects.requireNonNull(wrapper.apply(next), "wrapped handler");
            }

            @Override
            public String key() {
                return key;
            }
        };
    }
}
