package net.messeb.volt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BodyReaderTest {
    @Test
    void readsBodiesAtTheConfiguredLimit() throws IOException {
        byte[] body = {1, 2, 3, 4};

        byte[] result = BodyReader.read(new ByteArrayInputStream(body), body.length, body.length);

        assertArrayEquals(body, result);
    }

    @Test
    void rejectsKnownOversizedBodyBeforeReading() {
        var input = new CountingInputStream(new byte[]{1, 2, 3});

        PayloadTooLarge exception =
                assertThrows(PayloadTooLarge.class, () -> BodyReader.read(input, 3, 2));

        assertEquals(2, exception.limit());
        assertEquals(0, input.reads);
    }

    @Test
    void rejectsChunkedBodyAsSoonAsItCrossesLimit() {
        var input = new CountingInputStream(new byte[]{1, 2, 3, 4});

        assertThrows(PayloadTooLarge.class, () -> BodyReader.read(input, -1, 3));

        assertEquals(4, input.reads);
    }

    @Test
    void supportsAnEmptyBodyWithZeroLimit() throws IOException {
        assertArrayEquals(
                new byte[0],
                BodyReader.read(new ByteArrayInputStream(new byte[0]), -1, 0));
    }

    @Test
    void makesProgressWhenAStreamTemporarilyReturnsZeroBytes() throws IOException {
        byte[] result = BodyReader.read(new ZeroThenDataInputStream(new byte[]{1, 2, 3}), -1, 3);

        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    @Test
    void validatesReaderArguments() {
        assertThrows(IllegalArgumentException.class, () -> BodyReader.read(null, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BodyReader.read(new ByteArrayInputStream(new byte[0]), -2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BodyReader.read(new ByteArrayInputStream(new byte[0]), -1, -1));
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheDeclaredContentLength() {
        assertThrows(BadRequest.class,
                () -> BodyReader.read(new ByteArrayInputStream(new byte[]{1}), 2, 4));
        assertThrows(BadRequest.class,
                () -> BodyReader.read(new ByteArrayInputStream(new byte[]{1, 2}), 1, 4));
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private int reads;

        private CountingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            int result = super.read(target, offset, length);
            if (result > 0) {
                reads += result;
            }
            return result;
        }
    }

    private static final class ZeroThenDataInputStream extends InputStream {
        private final byte[] data;
        private int index;
        private boolean returnedZero;

        private ZeroThenDataInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return index == data.length ? -1 : data[index++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (!returnedZero) {
                returnedZero = true;
                return 0;
            }
            if (index == data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - index);
            System.arraycopy(data, index, target, offset, count);
            index += count;
            return count;
        }
    }
}
