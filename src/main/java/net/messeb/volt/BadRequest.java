package net.messeb.volt;

public final class BadRequest extends HttpException {
    public BadRequest(String message) {
        super(400, message);
    }
}
