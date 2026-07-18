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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.jspecify.annotations.Nullable;

/**
 * A self-contained Equihash proof-of-work puzzle, as specified in the Boson registration
 * proof-of-work document (director/docs/RegistrationPoW.md).
 *
 * <p>Equihash is a generalized-birthday puzzle: finding a solution is memory-hard (Wagner's
 * algorithm needs to store a large list of intermediate hashes), while verifying one is cheap
 * (recompute {@code 2^k} leaf hashes and check the collision and ordering conditions). That
 * asymmetry is what allows a super node to verify a registration proof without incurring any
 * memory-hard cost, so a flood of invalid submissions cannot exhaust it.
 *
 * <p>This is a clean-room, self-consistent construction (not wire-compatible with Zcash): the
 * client and the super node share the same implementation, so cross-implementation compatibility
 * is not required. The construction is:
 *
 * <ul>
 *   <li>Parameters {@code n} (bit width) and {@code k} (rounds), with {@code (k + 1)} dividing
 *       {@code n}. The collision bit length is {@code L = n / (k + 1)}.</li>
 *   <li>The index space is {@code [0, 2^(L + 1))}; a solution is {@code 2^k} distinct indices.</li>
 *   <li>The leaf string for index {@code i} is the high {@code n} bits of
 *       {@code Blake2b(personal = "BosonPoW" || n_le32 || k_le32, message = input || i_be32)},
 *       where {@code input} is the caller-provided per-attempt seed.</li>
 *   <li>A solution is a binary tree of depth {@code k} whose leaves, in solution order, XOR to zero
 *       over all {@code n} bits, with each depth-{@code d} subtree zeroing its leading {@code d*L}
 *       bits, and with the left subtree's first index strictly less than the right subtree's first
 *       index at every node (the algorithm-binding ordering that also enforces distinctness).</li>
 * </ul>
 */
public final class Equihash {
	private static final byte[] PERSONAL_PREFIX = "BosonPoW".getBytes(StandardCharsets.US_ASCII);

	// Guard against pathological memory growth during solving. Well-chosen parameters keep the
	// intermediate list near its initial size; this cap only trips on misuse.
	private static final int MAX_LIST_SIZE = 1 << 22;

	private Equihash() {
	}

	/**
	 * An intermediate entry during solving: the current XOR of a subtree's leaf strings, together
	 * with the (disjoint) set of leaf indices that produced it.
	 */
	private static final class Entry {
		final byte[] bits;
		final int[] indices;

		Entry(byte[] bits, int[] indices) {
			this.bits = bits;
			this.indices = indices;
		}
	}

	/**
	 * Validates the parameters and returns the collision bit length {@code L = n / (k + 1)}.
	 *
	 * @param n the hash bit width.
	 * @param k the number of rounds.
	 * @return the collision bit length.
	 * @throws IllegalArgumentException if the parameters are out of range or incompatible.
	 */
	public static int collisionBits(int n, int k) {
		if (k < 1 || k > 20)
			throw new IllegalArgumentException("Invalid Equihash k: " + k);
		if (n < 2 || n > 512)
			throw new IllegalArgumentException("Invalid Equihash n: " + n);
		if (n % (k + 1) != 0)
			throw new IllegalArgumentException("Equihash n must be divisible by (k + 1)");

		int l = n / (k + 1);
		if (l < 1 || l > 30)
			throw new IllegalArgumentException("Equihash collision length out of range: " + l);

		return l;
	}

	/**
	 * The number of leaf indices in a solution: {@code 2^k}.
	 *
	 * @param k the number of rounds.
	 * @return the solution length.
	 */
	public static int solutionLength(int k) {
		return 1 << k;
	}

	private static int hashBytes(int n) {
		return (n + 7) / 8;
	}

	private static byte[] personalization(int n, int k) {
		byte[] p = new byte[16];
		System.arraycopy(PERSONAL_PREFIX, 0, p, 0, 8);
		putIntLE(p, 8, n);
		putIntLE(p, 12, k);
		return p;
	}

	/**
	 * Computes the {@code n}-bit leaf string for a single index.
	 *
	 * @param input the per-attempt seed (for registration, {@code seed || powNonce}).
	 * @param index the leaf index.
	 * @param n     the hash bit width.
	 * @param k     the number of rounds.
	 * @return a {@code ceil(n / 8)}-byte array holding the high {@code n} bits (lower bits masked to 0).
	 */
	public static byte[] leaf(byte[] input, int index, int n, int k) {
		int hb = hashBytes(n);
		Blake2bDigest digest = new Blake2bDigest(null, hb, null, personalization(n, k));
		digest.update(input, 0, input.length);
		byte[] idx = new byte[4];
		putIntBE(idx, 0, index);
		digest.update(idx, 0, 4);
		byte[] out = new byte[hb];
		digest.doFinal(out, 0);
		maskTail(out, n);
		return out;
	}

	/**
	 * Verifies an Equihash solution.
	 *
	 * @param input   the per-attempt seed that the solution was produced for.
	 * @param n       the hash bit width.
	 * @param k       the number of rounds.
	 * @param indices the {@code 2^k} candidate solution indices, in solution order.
	 * @return {@code true} if the solution is valid.
	 */
	public static boolean verify(byte[] input, int n, int k, int[] indices) {
		int l = collisionBits(n, k);
		int count = solutionLength(k);
		if (indices == null || indices.length != count)
			return false;

		int indexBound = 1 << (l + 1);
		boolean[] seen = new boolean[indexBound];
		for (int idx : indices) {
			if (idx < 0 || idx >= indexBound || seen[idx])
				return false; // out of range or duplicate
			seen[idx] = true;
		}

		byte @Nullable [] root = verifyNode(input, n, k, l, indices, 0, count, 1);
		return root != null && isAllZero(root);
	}

	// Recursively verifies a subtree spanning indices[lo, hi) and returns its XOR string, or null if
	// the ordering or collision conditions fail. depth is the subtree height (1 for a leaf pair's
	// parent grows upward): here depth counts from the leaves, leaf = depth 0.
	private static byte @Nullable [] verifyNode(byte[] input, int n, int k, int l, int[] indices,
			int lo, int hi, int depthFromRoot) {
		int width = hi - lo;
		if (width == 1)
			return leaf(input, indices[lo], n, k);

		int mid = lo + width / 2;
		// Ordering: the left subtree's first index must be strictly less than the right's.
		if (indices[lo] >= indices[mid])
			return null;

		byte @Nullable [] left = verifyNode(input, n, k, l, indices, lo, mid, depthFromRoot + 1);
		if (left == null)
			return null;
		byte @Nullable [] right = verifyNode(input, n, k, l, indices, mid, hi, depthFromRoot + 1);
		if (right == null)
			return null;

		byte[] x = xor(left, right);
		// A subtree covering 2^d leaves must have its leading d*L bits zeroed. d = log2(width).
		int d = Integer.numberOfTrailingZeros(width);
		int zeroed = d * l;
		if (zeroed < n && !hasLeadingZeroBits(x, zeroed))
			return null;

		return x;
	}

	/**
	 * Finds Equihash solutions for the given input using Wagner's algorithm. May return zero, one, or
	 * several solutions; each returned solution is in canonical order and passes {@link #verify}.
	 *
	 * @param input the per-attempt seed.
	 * @param n     the hash bit width.
	 * @param k     the number of rounds.
	 * @return the list of solutions found (possibly empty).
	 */
	public static List<int[]> solve(byte[] input, int n, int k) {
		int l = collisionBits(n, k);
		int listSize = 1 << (l + 1);

		List<Entry> list = new ArrayList<>(listSize);
		for (int i = 0; i < listSize; i++)
			list.add(new Entry(leaf(input, i, n, k), new int[] { i }));

		List<int[]> solutions = new ArrayList<>();
		for (int round = 1; round <= k; round++) {
			final int startBit = (round - 1) * l;
			list.sort(Comparator.comparingLong(e -> extractBits(e.bits, startBit, l)));

			List<Entry> next = new ArrayList<>(listSize);
			int i = 0;
			int size = list.size();
			while (i < size) {
				long key = extractBits(list.get(i).bits, startBit, l);
				int j = i + 1;
				while (j < size && extractBits(list.get(j).bits, startBit, l) == key)
					j++;

				// Collide every disjoint pair within the group.
				for (int a = i; a < j; a++) {
					for (int b = a + 1; b < j; b++) {
						Entry ea = list.get(a);
						Entry eb = list.get(b);
						if (!disjoint(ea.indices, eb.indices))
							continue;

						byte[] nb = xor(ea.bits, eb.bits);
						int[] ni = mergeCanonical(ea.indices, eb.indices);
						if (round == k) {
							if (isAllZero(nb))
								solutions.add(ni);
						} else {
							next.add(new Entry(nb, ni));
							if (next.size() > MAX_LIST_SIZE)
								throw new IllegalStateException("Equihash intermediate list overflow");
						}
					}
				}
				i = j;
			}

			if (round < k)
				list = next;
		}

		return solutions;
	}

	// ---- bit helpers ------------------------------------------------------

	// Extracts lenBits bits (lenBits <= 32) starting at startBit, counting bit 0 as the MSB of byte 0.
	private static long extractBits(byte[] bytes, int startBit, int lenBits) {
		long v = 0;
		for (int i = 0; i < lenBits; i++) {
			int bit = startBit + i;
			int b = (bytes[bit >>> 3] >>> (7 - (bit & 7))) & 1;
			v = (v << 1) | b;
		}
		return v;
	}

	// Returns true if the first count bits (from the MSB) are all zero.
	static boolean hasLeadingZeroBits(byte[] bytes, int count) {
		int fullBytes = count >>> 3;
		for (int i = 0; i < fullBytes; i++) {
			if (bytes[i] != 0)
				return false;
		}
		int rem = count & 7;
		if (rem > 0) {
			int mask = (0xFF << (8 - rem)) & 0xFF;
			if ((bytes[fullBytes] & mask) != 0)
				return false;
		}
		return true;
	}

	private static boolean isAllZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0)
				return false;
		}
		return true;
	}

	private static byte[] xor(byte[] a, byte[] b) {
		byte[] out = new byte[a.length];
		for (int i = 0; i < a.length; i++)
			out[i] = (byte) (a[i] ^ b[i]);
		return out;
	}

	// Zeroes the low (8 - n % 8) bits of the last byte so only the high n bits remain.
	private static void maskTail(byte[] bytes, int n) {
		int rem = n & 7;
		if (rem != 0)
			bytes[bytes.length - 1] &= (byte) ((0xFF << (8 - rem)) & 0xFF);
	}

	private static boolean disjoint(int[] a, int[] b) {
		for (int x : a) {
			for (int y : b) {
				if (x == y)
					return false;
			}
		}
		return true;
	}

	// Concatenates the two index lists so the sublist with the smaller first index comes first.
	private static int[] mergeCanonical(int[] a, int[] b) {
		int[] first = a[0] < b[0] ? a : b;
		int[] second = first == a ? b : a;
		int[] out = new int[a.length + b.length];
		System.arraycopy(first, 0, out, 0, first.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	private static void putIntLE(byte[] out, int off, int v) {
		out[off] = (byte) v;
		out[off + 1] = (byte) (v >>> 8);
		out[off + 2] = (byte) (v >>> 16);
		out[off + 3] = (byte) (v >>> 24);
	}

	private static void putIntBE(byte[] out, int off, int v) {
		out[off] = (byte) (v >>> 24);
		out[off + 1] = (byte) (v >>> 16);
		out[off + 2] = (byte) (v >>> 8);
		out[off + 3] = (byte) v;
	}

	/**
	 * Encodes a solution as {@code 2^k} big-endian 32-bit indices (the wire form used by the effort
	 * hash and the registration request).
	 *
	 * @param indices the solution indices.
	 * @return the encoded bytes.
	 */
	public static byte[] encodeSolution(int[] indices) {
		byte[] out = new byte[indices.length * 4];
		for (int i = 0; i < indices.length; i++)
			putIntBE(out, i * 4, indices[i]);
		return out;
	}

	/**
	 * Decodes a solution previously produced by {@link #encodeSolution}.
	 *
	 * @param bytes the encoded bytes.
	 * @return the solution indices.
	 * @throws IllegalArgumentException if the length is not a multiple of 4.
	 */
	public static int[] decodeSolution(byte[] bytes) {
		if (bytes.length % 4 != 0)
			throw new IllegalArgumentException("Invalid solution encoding length: " + bytes.length);
		int[] out = new int[bytes.length / 4];
		for (int i = 0; i < out.length; i++) {
			int off = i * 4;
			out[i] = ((bytes[off] & 0xFF) << 24) | ((bytes[off + 1] & 0xFF) << 16)
					| ((bytes[off + 2] & 0xFF) << 8) | (bytes[off + 3] & 0xFF);
		}
		return out;
	}

	// Package-visible for tests.
	static boolean sameSolution(int[] a, int[] b) {
		return Arrays.equals(a, b);
	}
}
