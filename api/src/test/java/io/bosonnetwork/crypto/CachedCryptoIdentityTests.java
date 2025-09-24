package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Identity;

public class CachedCryptoIdentityTests {
	@Test
	void testSignAndVerify() {
		Identity identity = new CachedCryptoIdentity(Caffeine.newBuilder());
		byte[] message = "hello boson".getBytes();

		byte[] signature = identity.sign(message);
		assertNotNull(signature);

		// Verify with same identity
		assertTrue(identity.verify(message, signature));

		// Verify should fail with another identity
		Identity other = new CachedCryptoIdentity(Caffeine.newBuilder());
		assertFalse(other.verify(message, signature));
	}

	@Test
	void testEncryptAndDecrypt() throws Exception {
		Identity sender = new CachedCryptoIdentity(Caffeine.newBuilder());
		Identity recipient = new CachedCryptoIdentity(Caffeine.newBuilder());

		byte[] message = "secret data".getBytes();
		byte[] cipher = sender.encrypt(recipient.getId(), message);
		assertNotNull(cipher);
		assertNotEquals(new String(message), new String(cipher));

		byte[] plain = recipient.decrypt(sender.getId(), cipher);
		assertArrayEquals(message, plain);
	}

	@Test
	void testDecryptWithWrongSenderFails() throws Exception {
		Identity sender = new CachedCryptoIdentity(Caffeine.newBuilder());
		Identity recipient = new CachedCryptoIdentity(Caffeine.newBuilder());
		Identity wrongSender = new CachedCryptoIdentity(Caffeine.newBuilder());

		byte[] message = "important".getBytes();
		byte[] cipher = sender.encrypt(recipient.getId(), message);

		assertThrows(CryptoException.class,
				() -> recipient.decrypt(wrongSender.getId(), cipher));
	}

	@Test
	void testInvalidCipherSize() {
		Identity identity = new CachedCryptoIdentity(Caffeine.newBuilder());
		assertThrows(CryptoException.class,
				() -> identity.decrypt(new CachedCryptoIdentity(Caffeine.newBuilder()).getId(), new byte[5]));
	}

	@Test
	void testCryptoContextRoundTrip() throws Exception {
		Identity alice = new CachedCryptoIdentity(Caffeine.newBuilder());
		Identity bob = new CachedCryptoIdentity(Caffeine.newBuilder());

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
		Identity alice = new CachedCryptoIdentity(Caffeine.newBuilder());
		Identity bob = new CachedCryptoIdentity(Caffeine.newBuilder());

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

	@Test
	void testCompatibleWithCryptoContext() throws Exception {
		CryptoIdentity alice = new CachedCryptoIdentity(Caffeine.newBuilder());
		CryptoIdentity alice2 = new CryptoIdentity(alice.getKeyPair());
		CryptoIdentity bob = new CachedCryptoIdentity(Caffeine.newBuilder());
		CryptoIdentity bob2 = new CryptoIdentity(bob.getKeyPair());

		byte[] message = "context message".getBytes();
		byte[] cipher = alice.encrypt(bob.getId(), message);
		byte[] plain = bob2.decrypt(alice.getId(), cipher);
		assertArrayEquals(message, plain);

		cipher = bob2.encrypt(alice.getId(), message);
		plain = alice.decrypt(bob.getId(), cipher);
		assertArrayEquals(message, plain);
	}

	@Test
	void testCache() throws Exception {
		CachedCryptoIdentity alice = new CachedCryptoIdentity(null);
		Identity bob = new CryptoIdentity();

		CryptoContext ctx1 = alice.createCryptoContext(bob.getId());
		CryptoContext ctx2 = alice.createCryptoContext(bob.getId());
		assertNotSame(ctx1, ctx2);

		alice.initCache(Caffeine.newBuilder());
		ctx1 = alice.createCryptoContext(bob.getId());
		ctx2 = alice.createCryptoContext(bob.getId());
		assertSame(ctx1, ctx2);
	}
}