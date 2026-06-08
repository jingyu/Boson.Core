package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
public class AsyncInputStreamTest {
	// A ByteArrayInputStream that records whether it was closed.
	private static class TrackingInputStream extends ByteArrayInputStream {
		final AtomicBoolean closed = new AtomicBoolean();

		TrackingInputStream(byte[] buf) {
			super(buf);
		}

		@Override
		public void close() throws IOException {
			closed.set(true);
			super.close();
		}
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(42).nextBytes(b);
		return b;
	}

	@Test
	void readsAllContentInOrderAndClosesInput(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(4096);
		TrackingInputStream in = new TrackingInputStream(data);
		Buffer acc = Buffer.buffer();

		// chunkSize smaller than the payload to force multiple chunks
		vertx.runOnContext(v -> {
			AsyncInputStream stream = new AsyncInputStream(vertx, in, 256, true);
			stream.exceptionHandler(tc::failNow);
			stream.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				assertTrue(in.closed.get(), "input should be closed on end");
				tc.completeNow();
			}));
			stream.handler(acc::appendBuffer);
		});
	}

	@Test
	void doesNotCloseInputWhenConfigured(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(512);
		TrackingInputStream in = new TrackingInputStream(data);

		vertx.runOnContext(v -> {
			AsyncInputStream stream = new AsyncInputStream(vertx, in, 256, false);
			stream.exceptionHandler(tc::failNow);
			stream.endHandler(x -> tc.verify(() -> {
				assertFalse(in.closed.get(), "input should not be closed when closeInput=false");
				tc.completeNow();
			}));
			stream.handler(b -> { });
		});
	}

	@Test
	void fetchHonorsDemand(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(1024); // 4 chunks at chunkSize 256
		TrackingInputStream in = new TrackingInputStream(data);
		AtomicInteger chunks = new AtomicInteger();
		Buffer acc = Buffer.buffer();

		vertx.runOnContext(v -> {
			AsyncInputStream stream = new AsyncInputStream(vertx, in, 256, true);
			stream.exceptionHandler(tc::failNow);
			stream.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			stream.pause();
			stream.handler(b -> {
				chunks.incrementAndGet();
				acc.appendBuffer(b);
			});
			// request exactly one chunk
			stream.fetch(1);

			// after the single chunk has had time to arrive, only one should have been delivered
			vertx.setTimer(300, t -> tc.verify(() -> {
				assertEquals(1, chunks.get(), "fetch(1) must deliver exactly one chunk");
				assertFalse(in.closed.get(), "stream must not have ended yet");
				stream.resume(); // drain the remaining chunks and trigger end
			}));
		});
	}
}