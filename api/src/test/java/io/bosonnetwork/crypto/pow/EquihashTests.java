package io.bosonnetwork.crypto.pow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.Random;

public class EquihashTests {
	// Small but valid parameters that keep solving fast: L = 48 / 4 = 12, index space 2^13.
	private static final int N = 48;
	private static final int K = 3;

	@Test
	void collisionBitsAndParams() {
		assertEquals(12, Equihash.collisionBits(N, K));
		assertEquals(8, Equihash.solutionLength(K));

		assertThrows(IllegalArgumentException.class, () -> Equihash.collisionBits(50, K)); // 50 % 4 != 0
		assertThrows(IllegalArgumentException.class, () -> Equihash.collisionBits(N, 0));
	}

	@Test
	void solveProducesVerifiableSolutions() {
		byte[] input = Random.randomBytesSecure(104);
		List<int[]> solutions = Equihash.solve(input, N, K);
		assertFalse(solutions.isEmpty(), "expected at least one solution for the input");

		for (int[] sol : solutions) {
			assertEquals(Equihash.solutionLength(K), sol.length);
			assertTrue(Equihash.verify(input, N, K, sol), "solver output must verify");
		}
	}

	@Test
	void tamperedSolutionFailsVerification() {
		byte[] input = Random.randomBytesSecure(104);
		List<int[]> solutions = Equihash.solve(input, N, K);
		assertFalse(solutions.isEmpty());

		int[] sol = solutions.get(0).clone();
		// Flip one index to a different valid-range value: breaks the collision/ordering.
		sol[0] = (sol[0] ^ 1);
		assertFalse(Equihash.verify(input, N, K, sol));

		// Wrong length.
		int[] tooShort = new int[sol.length - 1];
		System.arraycopy(sol, 0, tooShort, 0, tooShort.length);
		assertFalse(Equihash.verify(input, N, K, tooShort));

		// Duplicated index.
		int[] dup = solutions.get(0).clone();
		dup[dup.length - 1] = dup[0];
		assertFalse(Equihash.verify(input, N, K, dup));
	}

	@Test
	void solutionBoundToInput() {
		byte[] input = Random.randomBytesSecure(104);
		List<int[]> solutions = Equihash.solve(input, N, K);
		assertFalse(solutions.isEmpty());

		byte[] otherInput = input.clone();
		otherInput[0] ^= 0x01;
		assertFalse(Equihash.verify(otherInput, N, K, solutions.get(0)),
				"a solution must not verify against a different input");
	}

	@Test
	void solutionEncodingRoundtrip() {
		int[] indices = { 1, 2, 3, 100, 200, 4095, 8000, 8191 };
		byte[] encoded = Equihash.encodeSolution(indices);
		assertEquals(indices.length * 4, encoded.length);
		assertTrue(Equihash.sameSolution(indices, Equihash.decodeSolution(encoded)));
	}
}
