package io.bosonnetwork.crypto.pow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;

public class RegistrationPowClientTests {
	private static final int N = 48;
	private static final int K = 3;

	private static long nonceToLong(byte[] b) {
		long v = 0;
		for (int i = 0; i < 8; i++)
			v = (v << 8) | (b[i] & 0xFFL);
		return v;
	}

	@Test
	void solveProducesServerVerifiableFields() {
		byte[] superNodeId = Signature.KeyPair.random().publicKey().bytes();
		Signature.KeyPair userKey = Signature.KeyPair.random();
		byte[] challengeNonce = Random.randomBytesSecure(PowChallenge.NONCE_BYTES);
		int effort = 0;

		RegistrationPowClient.Result r = RegistrationPowClient.solve(superNodeId, userKey, N, K,
				effort, challengeNonce, 512);

		// Server side: the proof-of-work verifies.
		byte[] seed = ProofOfWork.seed(superNodeId, userKey.publicKey().bytes(), challengeNonce);
		assertTrue(ProofOfWork.verify(seed, N, K, effort, nonceToLong(r.powNonce()), r.solution()));

		// Server side: the identity signature over the registration message verifies.
		byte[] msg = ProofOfWork.signingMessage(superNodeId, userKey.publicKey().bytes(), challengeNonce,
				r.powNonce(), effort);
		assertTrue(userKey.publicKey().verify(msg, r.signature()));
	}

	@Test
	void additionalDeviceSignatureVerifies() {
		byte[] superNodeId = Signature.KeyPair.random().publicKey().bytes();
		Signature.KeyPair userKey = Signature.KeyPair.random();
		Signature.KeyPair deviceKey = Signature.KeyPair.random();
		byte[] challengeNonce = Random.randomBytesSecure(PowChallenge.NONCE_BYTES);
		int effort = 0;

		RegistrationPowClient.Result r = RegistrationPowClient.solve(superNodeId, userKey, N, K,
				effort, challengeNonce, 512);
		byte[] deviceSig = RegistrationPowClient.sign(superNodeId, deviceKey, challengeNonce, r.powNonce(), effort);

		// The device key signs a message bound to its OWN identity (deviceId); the server verifies it
		// against the device key, exactly as it verifies userSig against the user key.
		byte[] deviceMsg = ProofOfWork.signingMessage(superNodeId, deviceKey.publicKey().bytes(),
				challengeNonce, r.powNonce(), effort);
		assertTrue(deviceKey.publicKey().verify(deviceMsg, deviceSig));
	}
}
