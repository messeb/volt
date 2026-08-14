package net.messeb.volt;

public final class RequestHeaderFieldsTooLarge extends HttpException {
    public RequestHeaderFieldsTooLarge(String message) {
        super(431, message);
    }
}
