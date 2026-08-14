package net.messeb.volt;

@FunctionalInterface
public interface ExceptionMapper<T extends Throwable> {
    void handle(T exception, Context context) throws Exception;
}
