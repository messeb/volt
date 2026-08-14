package net.messeb.volt;

public final class NotFound extends HttpException {
    public NotFound(String resource) {
        super(404, message(resource));
    }

    private static String message(String resource) {
        return resource == null || resource.isBlank() ? "not found" : resource + " not found";
    }
}
