package io.bosonnetwork.crypto;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

public class Random {
	// SecureRandom has been explicitly documented as thread-safe since Java 17
	private static final SecureRandom secureRandom = new SecureRandom();

	/**
	 * Returns the current thread's {@code ThreadLocalRandom} object.
	 * Methods of this object should be called only by the current thread,
	 * not by other threads.
	 *
	 * @return the current thread's {@code ThreadLocalRandom}
	 */
	public static ThreadLocalRandom random() {
		return ThreadLocalRandom.current();
	}

	/**
	 * Returns a {@code SecureRandom} instance.
	 *
	 * @return a thread-safe {@code SecureRandom} instance.
	 */
	public static SecureRandom secureRandom() {
			return secureRandom;
	}

	public static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random().nextBytes(bytes);
		return bytes;
	}

	public static byte[] randomBytesSecure(int length) {
		byte[] bytes = new byte[length];
		secureRandom().nextBytes(bytes);
		return bytes;
	}
}