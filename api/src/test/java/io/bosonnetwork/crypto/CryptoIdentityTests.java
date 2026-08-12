package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Identity;

public class CryptoIdentityTests {
	@Test
	void testSignAndVerify() {
		Identity identity = new CryptoIdentity();
		byte[] message = "hello boson".getBytes();

		byte[] signature = identity.sign(message);
		assertNotNull(signature);

		// Verify with same identity
		assertTrue(identity.verify(message, signature));

		// Verify should fail with another identity
		Identity other = new CryptoIdentity();
		assertFalse(other.verify(message, signature));
	}

	@Test
	void testEncryptAndDecrypt() throws Exception {
		Identity sender = new CryptoIdentity();
		Identity recipient = new CryptoIdentity();

		byte[] message = "secret data".getBytes();
		byte[] cipher = sender.encrypt(recipient.getId(), message);
		assertNotNull(cipher);
		assertNotEquals(new String(message), new String(cipher));

		byte[] plain = recipient.decrypt(sender.getId(), cipher);
		assertArrayEquals(message, plain);
	}

	@Test
	void testDecryptWithWrongSenderFails() throws Exception {
		Identity sender = new CryptoIdentity();
		Identity recipient = new CryptoIdentity();
		Identity wrongSender = new CryptoIdentity();

		byte[] message = "important".getBytes();
		byte[] cipher = sender.encrypt(recipient.getId(), message);

		assertThrows(CryptoException.class,
				() -> recipient.decrypt(wrongSender.getId(), cipher));
	}

	@Test
	void testInvalidCipherSize() {
		Identity identity = new CryptoIdentity();
		assertThrows(CryptoException.class,
				() -> identity.decrypt(new CryptoIdentity().getId(), new byte[5]));
	}

	/**
	 * The duplicate check does what it says on the straight-line path: the same ciphertext offered
	 * twice is refused the second time.
	 */
	@Test
	void testDuplicateNonceIsRejected() throws Exception {
		Identity alice = new CryptoIdentity();
		Identity bob = new CryptoIdentity();

		//noinspection resource
		CryptoContext ctxAlice = alice.createCryptoContext(bob.getId());
		//noinspection resource
		CryptoContext ctxBob = bob.createCryptoContext(alice.getId());

		byte[] cipher = ctxAlice.encrypt("replay me".getBytes());

		assertArrayEquals("replay me".getBytes(), ctxBob.decrypt(cipher));
		assertThrows(CryptoException.class, () -> ctxBob.decrypt(cipher));
	}

	/**
	 * And it keeps doing it when several threads decrypt from one peer at once, which is not a
	 * hypothetical: the DHT decrypts every datagram on a worker pool, so two packets from the same
	 * peer are regularly in flight together.
	 * <p>
	 * A separate volatile read and write cannot promise this - both threads read the previous nonce,
	 * neither sees a match, and the duplicate passes between them. Reading and replacing in one step
	 * makes exactly one caller the first, whatever the interleaving.
	 * </p>
	 */
	@Test
	void testDuplicateNonceIsRejectedUnderConcurrentDecrypt() throws Exception {
		Identity alice = new CryptoIdentity();
		Identity bob = new CryptoIdentity();

		//noinspection resource
		CryptoContext ctxAlice = alice.createCryptoContext(bob.getId());
		//noinspection resource
		CryptoContext ctxBob = bob.createCryptoContext(alice.getId());

		byte[] cipher = ctxAlice.encrypt("replay me".getBytes());

		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(threads);
		AtomicInteger accepted = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			Thread thread = new Thread(() -> {
				try {
					start.await();
					ctxBob.decrypt(cipher);
					accepted.incrementAndGet();
				} catch (Exception e) {
					// Refused as a duplicate, which is the point; anything else is not an acceptance either.
				} finally {
					finished.countDown();
				}
			});
			thread.start();
		}

		start.countDown();
		assertTrue(finished.await(30, TimeUnit.SECONDS), "the concurrent decrypts did not finish");
		assertEquals(1, accepted.get(), "exactly one of the identical copies may be accepted");
	}

	@Test
	void testCryptoContextRoundTrip() throws Exception {
		Identity alice = new CryptoIdentity();
		Identity bob = new CryptoIdentity();

		//noinspection resource
		CryptoContext ctxAlice = alice.createCryptoContext(bob.getId());
		//noinspection resource
		CryptoContext ctxBob = bob.createCryptoContext(alice.getId());

		byte[] message = "context message".getBytes();
		byte[] cipher = ctxAlice.encrypt(message);
		byte[] plain = ctxBob.decrypt(cipher);

		assertArrayEquals(message, plain);
	}

	@Test
	void testCompatible() throws Exception {
		Identity alice = new CryptoIdentity();
		Identity bob = new CryptoIdentity();

		//noinspection resource
		CryptoContext ctxAlice = alice.createCryptoContext(bob.getId());

		byte[] message = "context message".getBytes();
		byte[] cipher = ctxAlice.encrypt(message);
		byte[] plain = bob.decrypt(alice.getId(), cipher);
		assertArrayEquals(message, plain);

		cipher = bob.encrypt(alice.getId(), message);
		plain = ctxAlice.decrypt(cipher);
		assertArrayEquals(message, plain);
	}
}