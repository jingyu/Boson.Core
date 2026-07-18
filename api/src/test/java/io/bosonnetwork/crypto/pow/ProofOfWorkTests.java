package io.bosonnetwork.crypto.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.Random;

public class ProofOfWorkTests {
	private static final int N = 48;
	private static final int K = 3;

	@Test
	void leadingZeroBits() {
		assertEquals(0, ProofOfWork.leadingZeroBits(new byte[] { (byte) 0xFF }));
		assertEquals(8, ProofOfWork.leadingZeroBits(new byte[] { 0x00, (byte) 0xFF }));
		assertEquals(17, ProofOfWork.leadingZeroBits(new byte[] { 0x00, 0x00, 0x40 }));
		assertEquals(24, ProofOfWork.leadingZeroBits(new byte[] { 0x00, 0x00, 0x00 }));
	}

	@Test
	void seedLayout() {
		byte[] node = Random.randomBytesSecure(32);
		byte[] pub = Random.randomBytesSecure(32);
		byte[] nonce = Random.randomBytesSecure(32);
		byte[] seed = ProofOfWork.seed(node, pub, nonce);
		assertEquals(ProofOfWork.SEED_BYTES, seed.length);
	}

	@Test
	void solveAndVerifyRoundtrip() {
		byte[] seed = ProofOfWork.seed(Random.randomBytesSecure(32),
				Random.randomBytesSecure(32), Random.randomBytesSecure(32));

		ProofOfWork.Solution sol = ProofOfWork.solve(seed, N, K, 0, 512);
		assertNotNull(sol, "should find a solution at effort 0");
		assertTrue(ProofOfWork.verify(seed, N, K, 0, sol.powNonce(), sol.indices()));
	}

	@Test
	void verifyEnforcesEffort() {
		byte[] seed = ProofOfWork.seed(Random.randomBytesSecure(32),
				Random.randomBytesSecure(32), Random.randomBytesSecure(32));

		ProofOfWork.Solution sol = ProofOfWork.solve(seed, N, K, 0, 512);
		assertNotNull(sol);

		int actual = ProofOfWork.leadingZeroBits(
				ProofOfWork.effortHash(seed, sol.powNonce(), sol.indices()));
		// The found solution satisfies effort=actual but not effort=actual+1.
		assertTrue(ProofOfWork.verify(seed, N, K, actual, sol.powNonce(), sol.indices()));
		assertFalse(ProofOfWork.verify(seed, N, K, actual + 1, sol.powNonce(), sol.indices()));
	}

	@Test
	void verifyRejectsTampering() {
		byte[] seed = ProofOfWork.seed(Random.randomBytesSecure(32),
				Random.randomBytesSecure(32), Random.randomBytesSecure(32));

		ProofOfWork.Solution sol = ProofOfWork.solve(seed, N, K, 0, 512);
		assertNotNull(sol);

		// Wrong nonce.
		assertFalse(ProofOfWork.verify(seed, N, K, 0, sol.powNonce() + 1, sol.indices()));

		// Wrong seed.
		byte[] otherSeed = seed.clone();
		otherSeed[0] ^= 0x01;
		assertFalse(ProofOfWork.verify(otherSeed, N, K, 0, sol.powNonce(), sol.indices()));
	}

	@Test
	void solveHonoursEffortTarget() {
		byte[] seed = ProofOfWork.seed(Random.randomBytesSecure(32),
				Random.randomBytesSecure(32), Random.randomBytesSecure(32));

		int effort = 5;
		ProofOfWork.Solution sol = ProofOfWork.solve(seed, N, K, effort, 4096);
		assertNotNull(sol, "should find a solution meeting a small effort target");
		assertTrue(ProofOfWork.meetsEffort(
				ProofOfWork.effortHash(seed, sol.powNonce(), sol.indices()), effort));
	}
}
