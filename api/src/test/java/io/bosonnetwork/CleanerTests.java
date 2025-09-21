package io.bosonnetwork;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.CryptoBox;

public class CleanerTests {
	static class NativeResource implements AutoCloseable {
		private static final Cleaner cleaner = Cleaner.create();
		private final Cleaner.Cleanable cleanable;

		private final AtomicLong nativeHandler;

		public NativeResource() {
			AtomicLong h = new AtomicLong(123456);
			this.cleanable = cleaner.register(this, () -> cleanup(h));

			this.nativeHandler = h;
		}

		public static void cleanup(AtomicLong nativeHandler) {
			System.out.println("Cleaning native resource");
			nativeHandler.set(0);
		}

		@Override
		public void close() {
			cleanable.clean(); // Explicitly trigger cleanup
		}

		public String toString() {
			return "NativeResource{" +
					"nativeHandler=" + nativeHandler +
					'}';
		}
	}

	public class ResourceHolder {
		private static final Cleaner cleaner = Cleaner.create();
		private final FileChannel channel;

		public ResourceHolder(FileChannel channel) {
			this.channel = channel;
			// Register cleanup without capturing 'this'
			cleaner.register(this, () -> closeChannel(channel));
		}

		private static void closeChannel(FileChannel channel) {
			try {
				if (channel != null && channel.isOpen()) {
					channel.close();
				}
			} catch (IOException e) {
				System.err.println("Failed to close channel: " + e.getMessage());
			}
		}
	}

	@Test
	void testCleaner() throws Exception {
		{
			var keypair = CryptoBox.KeyPair.random();
			@SuppressWarnings("resource")
			var pk = keypair.publicKey();

			var nonce = CryptoBox.Nonce.random();

			pk = null;
			keypair = null;
			nonce = null;
		}

		System.gc();
		System.runFinalization(); // Encourage finalization and Cleaner processing
		Thread.sleep(2000); // Wait for Cleaner thread
		System.gc(); // Try again to ensure GC
		System.runFinalization();
		Thread.sleep(2000); // Wait again
		System.out.println("Done");
	}
}