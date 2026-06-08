package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
public class AsyncOutputStreamTest {
	// A ByteArrayOutputStream that records whether it was closed.
	private static class TrackingOutputStream extends ByteArrayOutputStream {
		final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public void close() {
			closed.set(true);
		}
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(42).nextBytes(b);
		return b;
	}

	@Test
	void writesAllDataInOrderAndCloses(Vertx vertx, VertxTestContext tc) {
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(vertx, out, true);
			stream.exceptionHandler(tc::failNow);
			stream.write(Buffer.buffer("Hello, "))
					.compose(x -> stream.write(Buffer.buffer("Ion ")))
					.compose(x -> stream.write(Buffer.buffer("Store!")))
					.compose(x -> stream.end())
					.onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals("Hello, Ion Store!", out.toString(StandardCharsets.UTF_8));
						assertTrue(out.closed.get(), "output should be closed on end");
						tc.completeNow();
					})));
		});
	}

	@Test
	void doesNotCloseOutputWhenConfigured(Vertx vertx, VertxTestContext tc) {
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(vertx, out, false);
			stream.exceptionHandler(tc::failNow);
			stream.write(Buffer.buffer("data"))
					.compose(x -> stream.end())
					.onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals("data", out.toString(StandardCharsets.UTF_8));
						assertFalse(out.closed.get(), "output should not be closed when closeOutput=false");
						tc.completeNow();
					})));
		});
	}

	@Test
	void pipeFromAsyncInputStreamRoundTrips(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(4096);
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncInputStream in = new AsyncInputStream(vertx, new ByteArrayInputStream(data), 256, true);
			AsyncOutputStream sink = new AsyncOutputStream(vertx, out, true);
			in.pipeTo(sink).onComplete(tc.succeeding(x -> tc.verify(() -> {
				assertArrayEquals(data, out.toByteArray());
				assertTrue(out.closed.get(), "output should be closed once the pipe completes");
				tc.completeNow();
			})));
		});
	}
}
