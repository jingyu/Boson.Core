package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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