package io.bosonnetwork.crypto;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

public class Random {
	private static final boolean SECURE_RANDOM_THREAD_SAFE = isSecureRandomThreadSafe();

	// The JDK buit-in SecureRandom should be thead-safe in organic mode.
	private static final SecureRandom secureRandom = isSecureRandomThreadSafe() ? new SecureRandom() : null;

	// Fail-back for non-thread-safe JCE environment
	private static final ThreadLocal<SecureRandom> localSecureRandom = isSecureRandomThreadSafe() ? null :
		ThreadLocal.withInitial(() -> {
			return new SecureRandom();
		});

	private static boolean isSecureRandomThreadSafe() {
		SecureRandom r = new SecureRandom();

		if (r.getProvider() == null || r.getAlgorithm() == null) {
			return false;
		} else {
			return Boolean.parseBoolean(
					r.getProvider().getProperty("SecureRandom." + r.getAlgorithm() + " ThreadSafe", "false"));
		}
	}

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
	 * Returns a thread-safe {@code SecureRandom} instance.
	 *
	 * This method provides an optimized approach for creating a thread-safe
	 * {@code SecureRandom}:
	 *   - If {@code SecureRandom} is inherently thread-safe, a shared instance
	 *     is returned for all calling threads.
	 *   - Otherwise, a thread-localed instance is used to ensure thread safety.
	 *
	 * Since {@code SecureRandom} uses synchronized blocks internally to protect key
	 * methods, the returned instance is safe for use across multiple threads.
	 * However, if {@code SecureRandom} is not inherently thread-safe, using a
	 * shared instance may cause contention in high-concurrency scenarios.
	 *
	 * @return a thread-safe {@code SecureRandom} instance.
	 */
	public static SecureRandom secureRandom() {
		if (SECURE_RANDOM_THREAD_SAFE)
			return secureRandom;
		else
			return localSecureRandom.get();
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
