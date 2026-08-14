package net.messeb.volt;

public interface JsonCodec {
    byte[] encode(Object value);

    <T> T decode(byte[] json, Class<T> type);

    static JsonCodec defaultCodec() {
        return SimpleJsonCodec.INSTANCE;
    }
}
