package io.bosonnetwork.crypto.pow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;

public class PowChallengeTests {
	private static PowChallenge sample(long now) {
		return new PowChallenge(PowChallenge.VERSION, PowChallenge.ALG_EQUIHASH, 96, 5, 18,
				Random.randomBytesSecure(PowChallenge.NONCE_BYTES), now, now + 120, 3);
	}

	@Test
	void bytesRoundtrip() {
		PowChallenge c = sample(1_752_384_000L);
		byte[] bytes = c.toBytes();
		PowChallenge decoded = PowChallenge.fromBytes(bytes);
		assertEquals(c, decoded);
		// Re-encoding the decoded value yields identical bytes.
		assertArrayEquals(bytes, decoded.toBytes());
	}

	@Test
	void signAndVerify() {
		Signature.KeyPair kp = Signature.KeyPair.random();
		PowChallenge c = sample(1_752_384_000L);
		byte[] bytes = c.toBytes();
		byte[] sig = c.sign(kp.privateKey());

		assertTrue(PowChallenge.verify(bytes, sig, kp.publicKey()));
	}

	@Test
	void verifyRejectsTamperedTokenAndWrongKey() {
		Signature.KeyPair kp = Signature.KeyPair.random();
		Signature.KeyPair other = Signature.KeyPair.random();
		PowChallenge c = sample(1_752_384_000L);
		byte[] bytes = c.toBytes();
		byte[] sig = c.sign(kp.privateKey());

		// Tampered token bytes.
		byte[] tampered = bytes.clone();
		tampered[tampered.length - 1] ^= 0x01;
		assertFalse(PowChallenge.verify(tampered, sig, kp.publicKey()));

		// Wrong verification key.
		assertFalse(PowChallenge.verify(bytes, sig, other.publicKey()));
	}

	@Test
	void expiry() {
		long now = 1_752_384_000L;
		PowChallenge c = sample(now);
		assertFalse(c.isExpired(now));
		assertFalse(c.isExpired(c.expiresAt() - 1));
		assertTrue(c.isExpired(c.expiresAt()));
		assertTrue(c.isExpired(c.expiresAt() + 1));
	}

	@Test
	void rejectsBadNonceLength() {
		long now = 1_752_384_000L;
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new PowChallenge(1, PowChallenge.ALG_EQUIHASH, 96, 5, 18,
						new byte[16], now, now + 120, 0));
	}
}
