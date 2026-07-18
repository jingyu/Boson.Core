/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.crypto.pow;

import java.util.List;
import java.util.Objects;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.jspecify.annotations.Nullable;

/**
 * The registration proof-of-work: an {@link Equihash} puzzle bound to a super node and a user
 * identity, plus a tunable effort target that layers on top of the base memory-hard cost.
 *
 * <p>The puzzle input (the "seed") binds the solution to a single super node and a single identity
 * so a solution cannot be reused elsewhere:
 * <pre>
 *     seed = superNodeId(32) || pubkey(32) || challengeNonce(32)
 * </pre>
 * The client searches for a {@code powNonce} such that an Equihash solution exists for
 * {@code seed || powNonce} <em>and</em> the effort hash of that solution clears the effort target:
 * <pre>
 *     effortHash = Blake2b-256(seed || powNonce || encode(solution))
 *     valid iff leadingZeroBits(effortHash) &gt;= effort
 * </pre>
 * Verification recomputes one Equihash solution check plus one Blake2b-256 hash, independent of the
 * effort, so it stays cheap no matter how hard the puzzle was to solve.
 */
public final class ProofOfWork {
	/** The byte length of each identifier that composes the seed. */
	public static final int ID_BYTES = 32;
	/** The byte length of the seed: {@code superNodeId || pubkey || challengeNonce}. */
	public static final int SEED_BYTES = ID_BYTES * 3;
	/** The byte length of the effort hash (Blake2b-256). */
	public static final int EFFORT_HASH_BYTES = 32;

	private ProofOfWork() {
	}

	/** A discovered proof-of-work: the winning {@code powNonce} and the Equihash solution. */
	public record Solution(long powNonce, int[] indices) {
	}

	/**
	 * Builds the puzzle seed from the super node id, the user public key, and the challenge nonce.
	 *
	 * @param superNodeId the 32-byte super node Boson id.
	 * @param pubkey      the 32-byte user public key (user id).
	 * @param challengeNonce    the 32-byte challenge nonce.
	 * @return the 96-byte seed.
	 */
	public static byte[] seed(byte[] superNodeId, byte[] pubkey, byte[] challengeNonce) {
		requireLength(superNodeId, ID_BYTES, "superNodeId");
		requireLength(pubkey, ID_BYTES, "pubkey");
		requireLength(challengeNonce, ID_BYTES, "challengeNonce");

		byte[] seed = new byte[SEED_BYTES];
		System.arraycopy(superNodeId, 0, seed, 0, ID_BYTES);
		System.arraycopy(pubkey, 0, seed, ID_BYTES, ID_BYTES);
		System.arraycopy(challengeNonce, 0, seed, ID_BYTES * 2, ID_BYTES);
		return seed;
	}

	// The per-attempt Equihash input: seed || powNonce (8 bytes, big-endian).
	private static byte[] puzzleInput(byte[] seed, long powNonce) {
		byte[] input = new byte[seed.length + 8];
		System.arraycopy(seed, 0, input, 0, seed.length);
		putLongBE(input, seed.length, powNonce);
		return input;
	}

	/**
	 * Builds the message an identity signs to bind its key to a proof-of-work solution:
	 * {@code superNodeId || pubkey || challengeNonce || powNonce(8, big-endian) || effort(4, big-endian)}.
	 * This is the single source of truth for the registration signing message, shared by the client
	 * (which signs it) and the super node (which verifies it).
	 *
	 * @param superNodeId the 32-byte super node id.
	 * @param pubkey      the 32-byte signing identity public key.
	 * @param challengeNonce    the 32-byte challenge nonce.
	 * @param powNonce    the 8-byte proof-of-work nonce.
	 * @param effort      the authenticated challenge effort.
	 * @return the message bytes to sign or verify.
	 */
	public static byte[] signingMessage(byte[] superNodeId, byte[] pubkey, byte[] challengeNonce,
			byte[] powNonce, int effort) {
		byte[] msg = new byte[superNodeId.length + pubkey.length + challengeNonce.length + powNonce.length + 4];
		int off = 0;
		System.arraycopy(superNodeId, 0, msg, off, superNodeId.length);
		off += superNodeId.length;
		System.arraycopy(pubkey, 0, msg, off, pubkey.length);
		off += pubkey.length;
		System.arraycopy(challengeNonce, 0, msg, off, challengeNonce.length);
		off += challengeNonce.length;
		System.arraycopy(powNonce, 0, msg, off, powNonce.length);
		off += powNonce.length;
		msg[off] = (byte) (effort >>> 24);
		msg[off + 1] = (byte) (effort >>> 16);
		msg[off + 2] = (byte) (effort >>> 8);
		msg[off + 3] = (byte) effort;
		return msg;
	}

	/**
	 * Encodes a proof-of-work nonce as 8 big-endian bytes (the wire form).
	 *
	 * @param powNonce the nonce value.
	 * @return the 8-byte big-endian encoding.
	 */
	public static byte[] encodeNonce(long powNonce) {
		byte[] out = new byte[8];
		putLongBE(out, 0, powNonce);
		return out;
	}

	/**
	 * Computes the effort hash for a candidate solution: {@code Blake2b-256(input || encode(solution))}
	 * where {@code input = seed || powNonce}.
	 *
	 * @param seed     the puzzle seed.
	 * @param powNonce the candidate nonce.
	 * @param indices  the Equihash solution indices.
	 * @return the 32-byte effort hash.
	 */
	public static byte[] effortHash(byte[] seed, long powNonce, int[] indices) {
		byte[] input = puzzleInput(seed, powNonce);
		byte[] solution = Equihash.encodeSolution(indices);
		Blake2bDigest digest = new Blake2bDigest(EFFORT_HASH_BYTES * 8);
		digest.update(input, 0, input.length);
		digest.update(solution, 0, solution.length);
		byte[] out = new byte[EFFORT_HASH_BYTES];
		digest.doFinal(out, 0);
		return out;
	}

	/**
	 * Returns whether a hash clears the given effort target (has at least {@code effort} leading
	 * zero bits).
	 *
	 * @param hash   the effort hash.
	 * @param effort the required number of leading zero bits.
	 * @return {@code true} if the hash meets or exceeds the effort.
	 */
	public static boolean meetsEffort(byte[] hash, int effort) {
		return leadingZeroBits(hash) >= effort;
	}

	/**
	 * Counts the leading zero bits of a byte array, interpreted big-endian.
	 *
	 * @param hash the bytes.
	 * @return the number of leading zero bits.
	 */
	public static int leadingZeroBits(byte[] hash) {
		int count = 0;
		for (byte b : hash) {
			int v = b & 0xFF;
			if (v == 0) {
				count += 8;
			} else {
				count += Integer.numberOfLeadingZeros(v) - 24;
				break;
			}
		}
		return count;
	}

	/**
	 * Searches for a proof-of-work that satisfies both the Equihash puzzle and the effort target.
	 * This is the memory-hard, time-consuming client-side operation.
	 *
	 * @param seed         the puzzle seed (see {@link #seed}).
	 * @param n            the Equihash bit width.
	 * @param k            the Equihash rounds.
	 * @param effort       the required leading zero bits of the effort hash.
	 * @param maxPowNonces the maximum number of nonces to try before giving up.
	 * @return the discovered solution, or {@code null} if none was found within the nonce budget.
	 */
	public static @Nullable Solution solve(byte[] seed, int n, int k, int effort, long maxPowNonces) {
		requireLength(seed, SEED_BYTES, "seed");
		Equihash.collisionBits(n, k); // validate parameters early

		for (long powNonce = 0; powNonce < maxPowNonces; powNonce++) {
			byte[] input = puzzleInput(seed, powNonce);
			List<int[]> candidates = Equihash.solve(input, n, k);
			for (int[] indices : candidates) {
				if (meetsEffort(effortHash(seed, powNonce, indices), effort))
					return new Solution(powNonce, indices);
			}
		}
		return null;
	}

	/**
	 * Verifies a proof-of-work: the Equihash solution must be valid for {@code seed || powNonce} and
	 * its effort hash must clear the effort target.
	 *
	 * @param seed     the puzzle seed.
	 * @param n        the Equihash bit width.
	 * @param k        the Equihash rounds.
	 * @param effort   the required leading zero bits.
	 * @param powNonce the claimed nonce.
	 * @param indices  the claimed Equihash solution.
	 * @return {@code true} if the proof-of-work is valid.
	 */
	public static boolean verify(byte[] seed, int n, int k, int effort, long powNonce, int[] indices) {
		if (seed == null || seed.length != SEED_BYTES || indices == null)
			return false;

		byte[] input = puzzleInput(seed, powNonce);
		if (!Equihash.verify(input, n, k, indices))
			return false;

		return meetsEffort(effortHash(seed, powNonce, indices), effort);
	}

	private static void requireLength(byte[] value, int length, String name) {
		Objects.requireNonNull(value, name);
		if (value.length != length)
			throw new IllegalArgumentException(name + " must be " + length + " bytes, got " + value.length);
	}

	private static void putLongBE(byte[] out, int off, long v) {
		for (int i = 0; i < 8; i++)
			out[off + i] = (byte) (v >>> (56 - i * 8));
	}
}
