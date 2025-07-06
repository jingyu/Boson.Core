/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import io.bosonnetwork.Id;
import io.bosonnetwork.utils.Hex;

/**
 * Represents a prefix in the Kademlia Distributed Hash Table (DHT) key-space.
 * A prefix defines a subspace of the key-space by specifying the first bit up to
 * which keys must match to be considered part of the prefix. This class is used
 * to manage and manipulate prefixes for routing and storage in the Kademlia network.
 */
public class Prefix extends Id {
	/**
	 * The depth of the prefix, indicating the number of leading bits that must match
	 * for a key to be covered by this prefix:
	 * <ul>
	 *   <li>-1: Matches the entire key-space.</li>
	 *   <li>0: The 0th bit must match.</li>
	 *   <li>1: The 1st bit must match.</li>
	 *   <li>...</li>
	 * </ul>
	 */
	private final int depth;

	/**
	 * A constant representing a prefix that covers the entire key-space (depth = -1).
	 */
	private static final Prefix ALL = new Prefix();

	/**
	 * Constructs a Prefix with the specified bytes and depth.
	 *
	 * @param bytes The byte array representing the prefix.
	 * @param depth The depth of the prefix, indicating the number of leading bits.
	 */
	private Prefix(byte[] bytes, int depth) {
		super(bytes);
		this.depth = depth;
	}

	/**
	 * Constructs a Prefix that covers the entire key-space (depth = -1).
	 */
	private Prefix() {
		super();
		this.depth = -1;
	}

	/**
	 * Constructs a copy of an existing Prefix.
	 *
	 * @param p The Prefix to copy.
	 */
	public Prefix(Prefix p) {
		super(p);
		this.depth = p.depth;
	}

	/**
	 * Constructs a Prefix from an Id with a specified depth.
	 *
	 * @param id The Id to base the prefix on.
	 * @param depth The depth of the prefix, in the range [-1, {@link Id#SIZE}).
	 * @throws IllegalArgumentException If the id is null or depth is out of range.
	 */
	public Prefix(Id id, int depth) {
		if (id == null)
			throw new IllegalArgumentException("id cannot be null");

		if (depth < -1 || depth >= Id.SIZE)
			throw new IllegalArgumentException("Depth must be in range [-1, " + Id.SIZE + ")");

		if (depth != -1)
			bitsCopy(id, this, depth);

		this.depth = depth;
	}

	public static Prefix all() {
		return ALL;
	}

	/**
	 * Returns the depth of this prefix.
	 *
	 * @return The depth of the prefix, or -1 if it covers the entire key-space.
	 */
	public int getDepth() {
		return depth;
	}

	/**
	 * Checks if the given Id falls within this prefix's key-space.
	 *
	 * @param id The Id to check.
	 * @return true if the Id matches this prefix up to its depth, false otherwise.
	 */
	public boolean isPrefixOf(Id id) {
		if (depth == -1)
			return true;

		return bitsEqual(this, id, depth);
	}

	/**
	 * Checks if this prefix can be split into two child prefixes.
	 *
	 * @return true if the prefix depth is less than {@link Id#SIZE} - 1, false otherwise.
	 */
	public boolean isSplittable() {
		return depth < Id.SIZE - 1;
	}

	/**
	 * Returns the first Id in this prefix's key-space.
	 *
	 * @return The Id representing the smallest key in this prefix.
	 */
	public Id first() {
		return Id.of(this);
	}

	/**
	 * Returns the last Id in this prefix's key-space.
	 *
	 * @return The Id representing the largest key in this prefix.
	 */
	public Id last() {
		Id trailingBits = new Prefix(Id.MAX_ID, depth).distance(Id.MAX_ID);
		return this.distance(trailingBits);
	}

	/**
	 * Returns the parent prefix of this prefix.
	 *
	 * @return The parent prefix, or this prefix if depth is -1.
	 */
	public Prefix getParent() {
		if (depth == -1)
			return this;

		return new Prefix(this, depth -1);
	}

	/**
	 * Creates a child prefix by splitting this prefix at the next bit.
	 *
	 * @param highBranch If true, the child prefix has a 1 at the next bit; if false, a 0.
	 * @return The new child prefix.
	 * @throws IllegalStateException If the prefix is not splittable.
	 */
	public Prefix splitBranch(boolean highBranch) {
		if (depth >= Id.SIZE - 1)
			throw new IllegalStateException("Prefix is not splittable");

		final int branchDepth = depth + 1;
		Prefix branch = new Prefix(this, branchDepth);
		if (highBranch)
			branch.bytes()[branchDepth / 8] |= (byte) (0x80 >> (branchDepth % 8));
		else
			branch.bytes()[branchDepth / 8] &= (byte) ~(0x80 >> (branchDepth % 8));

		return branch;
	}

	/**
	 * Checks if another prefix is a sibling of this prefix.
	 * Two prefixes are siblings if they have the same depth and share the same parent prefix.
	 *
	 * @param other The prefix to compare with.
	 * @return true if the other prefix is a sibling, false otherwise.
	 */
	public boolean isSiblingOf(Prefix other) {
		if (depth != other.depth || depth == -1)
			return false;

		if (depth == 0)
			return true;

		return bitsEqual(this, other, depth - 1);
	}

	/**
	 * Generates a random Id within this prefix's key-space.
	 *
	 * @return A random Id that falls under this prefix.
	 */
	public Id createRandomId() {
		// first generate a random one
		Id id = Id.random();

		if (depth != -1)
			bitsCopy(this, id, depth);

		return id;
	}

	/**
	 * Computes the common prefix for a collection of Ids.
	 *
	 * @param ids The collection of Ids to find the common prefix for.
	 * @return The Prefix representing the common prefix of all provided Ids.
	 * @throws IllegalArgumentException If the collection is empty.
	 */
	public static Prefix getCommonPrefix(Collection<Id> ids) {
		if (ids.isEmpty())
			throw new IllegalArgumentException("ids cannot be empty");

		final byte[] first = Collections.min(ids).bytes();
		final byte[] last = Collections.max(ids).bytes();

		byte[] prefixBytes = new byte[Id.BYTES];
		int depth = -1;

		int i = 0;
		for (; i < Id.BYTES && first[i] == last[i]; i++) {
			prefixBytes[i] = first[i];
			depth += 8;
		}

		if (i < Id.BYTES) {
			// first differing byte
			prefixBytes[i] = (byte) (first[i] & last[i]);
			for (int j = 0; j < 8; j++) {
				int mask = 0x80 >>> j;

				// find leftmost differing bit and then zero out all following bits
				if (((first[i] ^ last[i]) & mask) != 0) {
					prefixBytes[i] = (byte) (prefixBytes[i] & ~(0xFF >>> j));
					break;
				}

				depth++;
			}
		}

		return new Prefix(prefixBytes, depth);
	}

	/**
	 * Compares this prefix with another object for equality.
	 * Two prefixes are equal if they have the same depth and identical byte arrays.
	 *
	 * @param o The object to compare with.
	 * @return true if the objects are equal, false otherwise.
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof Prefix that) {
			if (this.depth != that.depth)
				return false;

			return Arrays.equals(this.bytes(), that.bytes());
		}
		return false;
	}

	/**
	 *	Returns a hash code for this prefix.
	 *
	 *	@return the hash code value.
	 */
	@Override
	public int hashCode() {
		return 0x6030A + super.hashCode() + Integer.hashCode(depth);
	}

	/**
	 * Returns a binary string representation of the prefix.
	 *
	 * @param withSpaces If true, includes spaces between bytes for readability.
	 * @return A string representing the prefix in binary form, or "all" if depth is -1.
	 */
	@Override
	public String toBinaryString(boolean withSpaces) {
		if (depth == -1)
			return "all";

		StringBuilder repr = new StringBuilder(withSpaces ? depth + 4: depth + depth >>> 3 + 4);
		final byte[] bytes = bytes();
		final char[] bits = new char[8];

		final int prefixBytes = ((depth + 1) >>> 3);
		for (int i = 0; i < prefixBytes; i++) {
			int b = bytes[i] & 0xFF;
			for (int j = 7; j >= 0; j--) {
				bits[j] = (b & 1) == 1 ? '1' : '0';
				b >>>= 1;
			}

			repr.append(bits);
			if (withSpaces && (((i + 1) << 3) < (depth + 1)))
				repr.append(' ');
		}

		final int remainingBits = (depth + 1) & 0x07;
		if (remainingBits > 0) {
			int b = bytes[prefixBytes] & 0xFF;
			b >>>= (8 - remainingBits);
			for (int i = remainingBits - 1; i >= 0; i--) {
				bits[i] = (b & 1) == 1 ? '1' : '0';
				b >>>= 1;
			}

			repr.append(bits, 0, remainingBits);
		}

		repr.append("...");
		return repr.toString();
	}

	/**
	 * Returns a string representation of the prefix in hexadecimal format with depth.
	 *
	 * @return A string in the format "hexPrefix/depth", or "all" if depth is -1.
	 */
	@Override
	public String toString() {
		if (depth == -1)
			return "all";

		return Hex.encode(bytes(), 0, (depth + 8) >>> 3) + "/" + depth;
	}
}