package net.messeb.volt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class BodyReader {
    private BodyReader() {
    }

    static byte[] read(InputStream input, long contentLength, long limit) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("body size limit must not be negative");
        }
        if (contentLength < -1) {
            throw new IllegalArgumentException("content length must be -1 or non-negative");
        }
        if (contentLength > limit) {
            throw new PayloadTooLarge(limit);
        }
        int initialCapacity = (int) Math.min(Math.max(contentLength, 0), Math.min(limit, 8192));
        var output = new ByteArrayOutputStream(initialCapacity);
        var buffer = new byte[8192];
        long total = 0;
        int read;
        while (true) {
            long remaining = limit - total;
            int requested = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            read = input.read(buffer, 0, requested);
            if (read == -1) {
                break;
            }
            if (read == 0) {
                read = input.read();
                if (read == -1) {
                    break;
                }
                total++;
                if (total > limit) {
                    throw new PayloadTooLarge(limit);
                }
                output.write(read);
                continue;
            }
            total += read;
            if (total > limit) {
                throw new PayloadTooLarge(limit);
            }
            output.write(buffer, 0, read);
        }
        if (contentLength >= 0 && total != contentLength) {
            throw new BadRequest("request body length does not match Content-Length");
        }
        return output.toByteArray();
    }
}
