package net.messeb.volt;

@FunctionalInterface
public interface Handler {
    void handle(Context context) throws Exception;
}
