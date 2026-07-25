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

	private record Puzzle(byte[] input, List<int[]> solutions) {}

	/**
	 * Returns an input that {@link Equihash#solve} found at least one solution for.
	 * <p>
	 * The solution count for a random input is Poisson-distributed with a mean of 2 at these
	 * parameters, so a single input yields nothing about 15% of the time - {@code solve} documents
	 * that it may return zero solutions. Real callers walk a nonce until one turns up (see
	 * {@code ProofOfWork.solve}); doing the same here keeps the tests off a single random draw.
	 */
	private static Puzzle solvablePuzzle() {
		for (int attempt = 0; attempt < 32; attempt++) {
			byte[] input = Random.randomBytesSecure(104);
			List<int[]> solutions = Equihash.solve(input, N, K);
			if (!solutions.isEmpty())
				return new Puzzle(input, solutions);
		}

		throw new AssertionError("no solvable input found in 32 attempts");
	}

	@Test
	void collisionBitsAndParams() {
		assertEquals(12, Equihash.collisionBits(N, K));
		assertEquals(8, Equihash.solutionLength(K));

		assertThrows(IllegalArgumentException.class, () -> Equihash.collisionBits(50, K)); // 50 % 4 != 0
		assertThrows(IllegalArgumentException.class, () -> Equihash.collisionBits(N, 0));
	}

	@Test
	void solveProducesVerifiableSolutions() {
		Puzzle puzzle = solvablePuzzle();

		for (int[] sol : puzzle.solutions()) {
			assertEquals(Equihash.solutionLength(K), sol.length);
			assertTrue(Equihash.verify(puzzle.input(), N, K, sol), "solver output must verify");
		}
	}

	@Test
	void tamperedSolutionFailsVerification() {
		Puzzle puzzle = solvablePuzzle();
		byte[] input = puzzle.input();

		int[] sol = puzzle.solutions().get(0).clone();
		// Flip one index to a different valid-range value: breaks the collision/ordering.
		sol[0] = (sol[0] ^ 1);
		assertFalse(Equihash.verify(input, N, K, sol));

		// Wrong length.
		int[] tooShort = new int[sol.length - 1];
		System.arraycopy(sol, 0, tooShort, 0, tooShort.length);
		assertFalse(Equihash.verify(input, N, K, tooShort));

		// Duplicated index.
		int[] dup = puzzle.solutions().get(0).clone();
		dup[dup.length - 1] = dup[0];
		assertFalse(Equihash.verify(input, N, K, dup));
	}

	@Test
	void solutionBoundToInput() {
		Puzzle puzzle = solvablePuzzle();

		byte[] otherInput = puzzle.input().clone();
		otherInput[0] ^= 0x01;
		assertFalse(Equihash.verify(otherInput, N, K, puzzle.solutions().get(0)),
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
