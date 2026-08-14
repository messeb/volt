package net.messeb.volt;

public final class PayloadTooLarge extends HttpException {
    private final long limit;

    public PayloadTooLarge(long limit) {
        super(413, "request body exceeds " + limit + " bytes");
        if (limit < 0) {
            throw new IllegalArgumentException("body size limit must not be negative");
        }
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
