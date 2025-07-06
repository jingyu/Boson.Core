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

package io.bosonnetwork;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.Hex;

/**
 * Represents a 256-bit identifier used in the Boson network to uniquely identify
 * nodes, values, peers, and other objects. This class is immutable and thread-safe.
 * All instances are guaranteed to have a valid byte array of length {@link #BYTES}.
 * The internal state cannot be modified after construction.
 */
public class Id implements Comparable<Id> {
	/**
	 * The number of bits used to represent an {@code Id} in two's complement binary form.
	 */
	public static final int SIZE = 256;
	/**
	 * The number of bytes used to represent an {@code Id} in two's complement binary form.
	 */
	public static final int BYTES = SIZE / Byte.SIZE;
	/**
	 * A constant representing the zero identifier (all bits set to 0).
	 */
	public static final Id ZERO_ID = new Id();
	/**
	 * A constant representing the minimum identifier (equivalent to {@link #ZERO_ID}).
	 */
	public static final Id MIN_ID = ZERO_ID;
	/**
	 * A constant representing the maximum identifier (all bits set to 1).
	 */
	public static final Id MAX_ID = createMaxId();

	private static final String DID_PREFIX = "did:boson:";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] bytes;
	private volatile String b58;	// Cached base58 string representation
	private volatile int hashCode;	// Cache hash code

	/**
	 * 3-way comparator. For sorting {@code Id} instances based on their
	 * distance to a target identifier using the XOR metric.
	 */
	public static final class ThreeWayComparator implements java.util.Comparator<Id> {
		private final Id target;

		/**
		 * Creates a comparator that sorts identifiers based on their XOR distance to the specified target.
		 *
		 * @param target the target identifier to measure distances against.
		 * @throws NullPointerException if the target is null.
		 */
		public ThreeWayComparator(Id target) {
			if (target == null)
				throw new NullPointerException("Target identifier cannot be null");

			this.target = target;
		}

		/**
		 * Compares two identifiers based on their XOR distance to the target identifier.
		 *
		 * @param o1 the first identifier to compare.
		 * @param o2 the second identifier to compare.
		 * @return a negative integer if {@code o1} is closer to the target, zero if equally distant,
		 *		 or a positive integer if {@code o2} is closer.
		 * @throws NullPointerException if either {@code o1} or {@code o2} is null.
		 */
		@Override
		public int compare(Id o1, Id o2) {
			return target.threeWayCompare(o1, o2);
		}
	}

	/**
	 * Constructs a zero identifier (all bits set to 0).
	 */
	protected Id() {
		bytes = new byte[BYTES];
	}

	/**
	 * Construct an id from a byte array. it should at least {@link #BYTES} bytes available.
	 *
	 * @param buf the byte array containing the identifier data.
	 * @throws IllegalArgumentException if the array length is not exactly {@link #BYTES}.
	 */
	protected Id(byte[] buf) {
		if (buf == null)
			throw new NullPointerException("Byte array cannot be null");

		if (buf.length != BYTES)
			throw new IllegalArgumentException("Byte array should be exactly " + BYTES + " bytes");

		this.bytes = buf;
	}

	/**
	 * Constructs an identifier by copying the bytes of an existing identifier.
	 *
	 * @param id the identifier to copy.
	 * @throws NullPointerException if {@code id} is null.
	 */
	@SuppressWarnings("CopyConstructorMissesField")
	protected Id(Id id) {
		if (id == null)
			throw new NullPointerException("Input identifier cannot be null");

		this.bytes = Arrays.copyOf(id.bytes, BYTES);
	}

	/**
	 * Creates an identifier from a byte array.
	 *
	 * @param buf the byte array containing exactly {@link #BYTES} bytes.
	 * @return a new {@code Id} instance.
	 * @throws NullPointerException if buf is null.
	 * @throws IllegalArgumentException if the array length is not exactly {@link #BYTES}.
	 */
	public static Id of(byte[] buf) {
		return new Id(Arrays.copyOf(buf, buf.length));
	}

	/**
	 * Creates an identifier from a byte array starting at the specified offset.
	 *
	 * @param buf the byte array containing the identifier data.
	 * @param offset the starting offset in the array.
	 * @return a new {@code Id} instance.
	 * @throws NullPointerException if buf is null.
	 * @throws IllegalArgumentException if the offset is negative or there are fewer than
	 *		 {@link #BYTES} bytes available from the offset.
	 */
	public static Id of(byte[] buf, int offset) {
		if (buf == null)
			throw new NullPointerException("Byte array cannot be null");

		if (offset < 0)
			throw new IllegalArgumentException("Offset must be non-negative");

		if (buf.length - offset < BYTES)
			throw new IllegalArgumentException("Byte array must have at least " + BYTES + " bytes available");

		return new Id(Arrays.copyOfRange(buf, offset, offset + BYTES));
	}

	/**
	 * Creates a new identifier by copying an existing identifier.
	 *
	 * @param id the identifier to copy.
	 * @return a new {@code Id} instance with the same value.
	 * @throws NullPointerException if {@code id} is null.
	 */
	public static Id of(Id id) {
		return new Id(id);
	}

	/**
	 * Creates an identifier from an id string representation in one of the following formats:
	 *   - Base58
	 *   - Hexadecimal (with or without "0x" prefix)
	 *   - W3C DID format ("did:boson:...")
	 * Base58 is attempted first, with fallback to hexadecimal or DID.
	 *
	 * @param id the string representation of the identifier.
	 * @return a new {@code Id} instance.
	 * @throws IllegalArgumentException if the string is not a valid Base58, hexadecimal, or DID representation.
	 * @throws NullPointerException if the input string is null.
	 */
	public static Id of(String id) {
		if (id == null)
			throw new NullPointerException("Identifier string cannot be null");

		try {
			return ofBase58(id);
		} catch (IllegalArgumentException e) {
			if (id.startsWith("0x") || id.startsWith("0X"))
				return ofHex(id);
			else if (id.startsWith(DID_PREFIX))
				return ofBase58(id.substring(DID_PREFIX.length()));
			else
				throw new IllegalArgumentException("Invalid identifier string: must be Base58, hexadecimal, or Boson DID format");
		}
	}

	/**
	 * Creates an identifier from a hexadecimal string, with or without the "0x" prefix.
	 *
	 * @param hexId the hexadecimal string representation.
	 * @return a new {@code Id} instance.
	 * @throws IllegalArgumentException if the string is not a valid hexadecimal representation
	 *		 or does not represent exactly {@link #BYTES} bytes.
	 * @throws NullPointerException if the input string is null.
	 */
	public static Id ofHex(String hexId) {
		if (hexId == null)
			throw new NullPointerException("Hexadecimal string cannot be null");

		final int offset = hexId.startsWith("0x") || hexId.startsWith("0X") ? 2 : 0;
		if (hexId.length() != BYTES * 2 + offset)
			throw new IllegalArgumentException("Hexadecimal string must represent exactly " + BYTES + " bytes");

		return of(Hex.decode(hexId, offset, BYTES * 2));
	}

	/**
	 * Creates an identifier from a Base58-encoded string.
	 *
	 * @param base58Id the Base58-encoded string.
	 * @return a new {@code Id} instance.
	 * @throws IllegalArgumentException if the string is not a valid Base58 encoding
	 *		 or does not decode to exactly {@link #BYTES} bytes.
	 * @throws NullPointerException if the input string is null.
	 */
	public static Id ofBase58(String base58Id) {
		if (base58Id == null)
			throw new NullPointerException("Base58 string cannot be null");

		return of(Base58.decode(base58Id));
	}

	/**
	 * Creates an identifier with a single bit set to 1 at the specified index (from MSB to LSB).
	 *
	 * @param idx the bit index (0 is the most significant bit, 255 is the least significant bit).
	 * @return a new {@code Id} instance with the specified bit set.
	 * @throws IllegalArgumentException if the index is not in the range [0, {@link #SIZE}).
	 */
	public static Id ofBit(int idx) {
		if (idx < 0 || idx >= SIZE)
			throw new IllegalArgumentException("Bit index must be in range [0, " + SIZE + ")");

		Id id = new Id();
		id.bytes[idx / 8] = (byte)(0x80 >>> (idx % 8));
		return id;
	}

	/**
	 * Returns the zero identifier (all bits set to 0).
	 *
	 * @return the {@link #ZERO_ID} constant.
	 */
	public static Id zero() {
		return ZERO_ID;
	}

	/**
	 * Returns the minimum identifier (all bits set to 0).
	 *
	 * @return the {@link #MIN_ID} constant.
	 */
	public static Id min() {
		return MIN_ID;
	}

	/**
	 * Returns the maximum identifier (all bits set to 1).
	 *
	 * @return the {@link #MAX_ID} constant.
	 */
	public static Id max() {
		return MAX_ID;
	}

	/**
	 * Generates a random identifier.
	 *
	 * @return a new random {@code Id} instance.
	 */
	public static Id random() {
		Id id = new Id();
		RANDOM.nextBytes(id.bytes);
		return id;
	}

	private static Id createMaxId() {
		Id id = new Id();
		Arrays.fill(id.bytes, (byte) 0xFF);
		return id;
	}

	/**
	 * Returns an array of bytes representing the id object.
	 *
	 * @return a new byte array containing the identifier's value.
	 */
	public byte[] getBytes() {
		return bytes.clone();
	}

	/**
	 * Returns the internal byte array of the identifier.
	 * <p><strong>Warning:</strong> This method is for internal use only and may be removed in future versions.
	 * Modifying the returned array will cause undefined behavior. Use {@link #getBytes()} for safe access.</p>
	 *
	 * @return the internal byte array (must not be modified).
	 */
	public final byte[] bytes() {
		// Performance critical method: returns internal array directly
		return bytes;
	}

	/**
	 * Reads four bytes from the specified offset and returns them as an unsigned integer
	 * in big-endian order.
	 *
	 * @param offset the starting offset in the identifier's byte array.
	 * @return the unsigned integer value.
	 * @throws ArrayIndexOutOfBoundsException if the offset is not in the range [0, {@link #BYTES} - 4].
	 */
	public int getInt(int offset) {
		if (offset < 0 || offset > BYTES - 4)
			throw new ArrayIndexOutOfBoundsException("Offset must be in range [0, " + (BYTES - 4) + "]");

		return Byte.toUnsignedInt(bytes[offset]) << 24 |
				Byte.toUnsignedInt(bytes[offset + 1]) << 16 |
				Byte.toUnsignedInt(bytes[offset + 2]) << 8 |
				Byte.toUnsignedInt(bytes[offset + 3]);
	}

	/**
	 * Returns a new identifier representing the sum of this identifier and another.
	 * Addition is performed using two's complement arithmetic, treating the identifiers
	 * as 256-bit unsigned integers.
	 *
	 * @param id the identifier to add to this one.
	 * @return a new {@code Id} instance representing the sum.
	 * @throws NullPointerException if the input identifier is null.
	 */
	public Id add(Id id) {
		return add(this, id);
	}

	/**
	 * Returns a new identifier representing the sum of two identifiers.
	 * Addition is performed using two's complement arithmetic, treating the identifiers
	 * as 256-bit unsigned integers.
	 *
	 * @param id1 the first identifier.
	 * @param id2 the second identifier.
	 * @return a new {@code Id} instance representing the sum.
	 * @throws NullPointerException if either identifier is null.
	 */
	@SuppressWarnings("UnnecessaryLocalVariable")
	public static Id add(Id id1, Id id2) {
		if (id1 == null || id2 == null)
			throw new NullPointerException("Identifier cannot be null");

		final Id result = new Id();

		final byte[] a = id1.bytes;
		final byte[] b = id2.bytes;
		final byte[] r = result.bytes;

		int carry = 0;
		for (int i = BYTES - 1; i >= 0; i--) {
			carry = (a[i] & 0xff) + (b[i] & 0xff) + carry;
			r[i] = (byte) (carry & 0xff);
			carry >>>= 8;
		}

		return result;
	}

	/**
	 * Calculates the distance between this identifier and another using the XOR metric.
	 *
	 * @param to the other identifier.
	 * @return a new {@code Id} instance representing the XOR distance.
	 * @throws NullPointerException if the input identifier is null.
	 */
	public Id distance(Id to) {
		return distance(this, to);
	}

	/**
	 * Calculates the distance between two identifiers using the XOR metric.
	 *
	 * @param id1 the first identifier.
	 * @param id2 the second identifier.
	 * @return a new {@code Id} instance representing the XOR distance.
	 * @throws NullPointerException if either identifier is null.
	 */
	@SuppressWarnings("UnnecessaryLocalVariable")
	public static Id distance(Id id1, Id id2) {
		if (id1 == null || id2 == null)
			throw new NullPointerException("Identifier cannot be null");

		final Id result = new Id();

		final byte[] r = result.bytes;
		final byte[] a = id1.bytes;
		final byte[] b = id2.bytes;

		for (int i = 0; i < BYTES; i++)
			r[i] = (byte) (a[i] ^ b[i]);

		return result;
	}

	/**
	 * Generates an identifier that is a specified number of bits away from this identifier
	 * using the XOR metric.
	 *
	 * @param distance the number of bits of difference (0 to {@link #SIZE}).
	 * @return a new {@code Id} instance at the specified distance.
	 * @throws IllegalArgumentException if the distance is not in the range [0, {@link #SIZE}].
	 */
	public Id getIdByDistance(int distance) {
		if (distance < 0 || distance > SIZE)
			throw new IllegalArgumentException("Distance must be in range [0, " + SIZE + "]");

		final byte[] result = new byte[BYTES];

		final int zeroBytes = (SIZE - distance) / 8;
		final int zeroBits = (SIZE - distance) % 8;

		if (zeroBytes < BYTES) {
			result[zeroBytes] = (byte)(0xFF >>> zeroBits);
			Arrays.fill(result, zeroBytes + 1, BYTES, (byte) 0xFF);
		}

		return this.distance(new Id(result));
	}

	/**
	 * Calculates the approximate distance between this identifier and another using
	 * the number of leading zeros in their XOR.
	 *
	 * @param to the other identifier.
	 * @return the approximate distance (0 to {@link #SIZE}).
	 * @throws NullPointerException if the input identifier is null.
	 */
	public int approxDistance(Id to) {
		return approxDistance(this, to);
	}

	/**
	 * Calculates the approximate distance between two identifiers using the number
	 * of leading zeros in their XOR.
	 *
	 * @param id1 the first identifier.
	 * @param id2 the second identifier.
	 * @return the approximate distance (0 to {@link #SIZE}).
	 * @throws NullPointerException if either identifier is null.
	 */
	public static int approxDistance(Id id1, Id id2) {
		if (id1 == null || id2 == null)
			throw new NullPointerException("Identifier cannot be null");

		return SIZE - id1.distance(id2).getLeadingZeros();
	}

	/**
	 * Compares two identifiers based on their XOR distance to this identifier.
	 *
	 * @param id1 the first identifier to compare.
	 * @param id2 the second identifier to compare.
	 * @return -1 if {@code id1} is closer, 0 if equally distant, or 1 if {@code id2} is closer.
	 * @throws NullPointerException if either identifier is null.
	 */
	public int threeWayCompare(Id id1, Id id2) {
		if (id1 == null || id2 == null)
			throw new NullPointerException("Identifier cannot be null");

		final int mmi = Arrays.mismatch(id1.bytes, id2.bytes);
		if (mmi == -1)
			return 0;

		int r = bytes[mmi] & 0xff;
		int a = id1.bytes[mmi] & 0xff;
		int b = id2.bytes[mmi] & 0xff;

		return Integer.compareUnsigned(a ^ r, b ^ r);
	}

	/**
	 * Counts the number of leading zeros in this identifier's binary representation.
	 *
	 * @return the number of leading zeros (0 to {@link #SIZE}).
	 */
	public int getLeadingZeros() {
		int msb = 0;

		int i = 0;
		while (i < BYTES && bytes[i] == 0) i++;
		msb += i << 3;

		if (i < BYTES) {
			byte b = bytes[i];
			if (b > 0) {
				int n = 7;
				if (b >= 1 <<  4) { n -=  4; b >>>=  4; }
				if (b >= 1 <<  2) { n -=  2; b >>>=  2; }
				msb += (n - (b >>> 1));
			}
		}

		return msb;
	}

	/**
	 * Counts the number of trailing zeros in this identifier's binary representation.
	 *
	 * @return the number of trailing zeros (0 to {@link #SIZE}).
	 */
	public int getTrailingZeros() {
		int lsb = 0;

		int i =  BYTES - 1;
		while (i >= 0 && bytes[i] == 0) i--;
		lsb += (BYTES - 1 - i) << 3;

		if (i >= 0) {
			byte b = (byte)(~bytes[i] & (bytes[i] - 1));
			if (b <= 0) {
				lsb += (b & 8);
			} else {
				if (b > 1 <<  4) { lsb +=  4; b >>>=  4; }
				if (b > 1 <<  2) { lsb +=  2; b >>>=  2; }
				lsb += ((b >>> 1) + 1);
			}
		}

		return lsb;
	}

	/**
	 * Checks if the first {@code n} bits of two identifiers are equal.
	 *
	 * @param id1 the first identifier.
	 * @param id2 the second identifier.
	 * @param depth the depth of bits to compare (0 to {@link #SIZE} - 1).
	 * @return {@code true} if the first {@code n} bits are equal, {@code false} otherwise.
	 * @throws NullPointerException if either identifier is null.
	 * @throws IllegalArgumentException if {@code n} is out of range.
	 */
	public static boolean bitsEqual(Id id1, Id id2, int depth) {
		if (id1 == null || id2 == null)
			throw new NullPointerException("Identifiers cannot be null");

		if (depth < 0 || depth >= SIZE)
			throw new IllegalArgumentException("Depth of bits must be in range [0, " + SIZE + ")");

		int indexToCheck = depth >>> 3;

		int diff = (id1.bytes[indexToCheck] ^ id2.bytes[indexToCheck]) & 0xff;
		boolean bitsDiff = (diff & (0xff80 >>> (depth & 0x07))) == 0;

		return Arrays.mismatch(id1.bytes, 0, indexToCheck,
				id2.bytes, 0, indexToCheck) == -1 && bitsDiff;
	}

	/**
	 * Copies the first {@code depth} bits from the source identifier to the destination identifier.
	 *
	 * @param src the source identifier.
	 * @param dest the destination identifier.
	 * @param depth the depth of bits to copy (0 to {@link #SIZE} - 1).
	 * @throws NullPointerException if either identifier is null.
	 * @throws IllegalArgumentException if {@code depth} out of range.
	 */
	protected static void bitsCopy(Id src, Id dest, int depth) {
		if (src == null || dest == null)
			throw new NullPointerException("Identifier cannot be null");

		if (depth < 0 || depth >= SIZE)
			throw new IllegalArgumentException("Depth of bits must be in range [0, " + SIZE + ")");

		// copy over all complete bytes
		final int idx = depth >>> 3;
		if (idx > 0)
			System.arraycopy(src.bytes, 0, dest.bytes, 0, idx);

		int mask = 0xFF80 >>> (depth & 0x07);

		// mask out the part we have to copy over from the last prefix byte
		dest.bytes[idx] &= (byte) ~mask;
		// copy the bits from the last byte
		dest.bytes[idx] |= (byte) (src.bytes[idx] & mask);
	}

	/**
	 * Gets the Ed25519 signature public key from this identifier.
	 *
	 * @return the Ed25519 public key derived from this identifier's bytes.
	 * @throws IllegalArgumentException if the identifier's bytes are not a valid Ed25519 public key.
	 */
	public Signature.PublicKey toSignatureKey() {
		return Signature.PublicKey.fromBytes(bytes);
	}

	/**
	 * Gets the X25519 encryption public key from this identifier.
	 *
	 * @return the X25519 public key derived from this identifier's Ed25519 public key.
	 * @throws IllegalArgumentException if the identifier's bytes are not a valid Ed25519 public key
	 *		 or cannot be converted to an X25519 public key.
	 */
	public CryptoBox.PublicKey toEncryptionKey() {
		return CryptoBox.PublicKey.fromSignatureKey(toSignatureKey());
	}

	/**
	 * Compares two identifiers lexicographically, treating their bytes as unsigned values.
	 *
	 * @param id1 the first identifier.
	 * @param id2 the second identifier.
	 * @return a negative integer, zero, or a positive integer if {@code id1} is less than,
	 *		 equal to, or greater than {@code id2}.
	 */
	public static int compare(Id id1, Id id2) {
		return Arrays.compareUnsigned(id1.bytes, id2.bytes);
	}

	/**
	 * Compares this identifier with another for ordering, treating bytes as unsigned values.
	 *
	 * @param o the identifier to compare with.
	 * @return a negative integer, zero, or a positive integer if this identifier is less than,
	 *		 equal to, or greater than the specified identifier.
	 */
	@Override
	public int compareTo(Id o) {
		return compare(this, o);
	}

	/**
	 * Checks if this identifier is equal to another object.
	 *
	 * @param o the object to compare with.
	 * @return {@code true} if the object is an {@code Id} with the same value,
	 *		 {@code false} otherwise.
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof Id that)
			return Arrays.equals(this.bytes, that.bytes);

		return false;
	}

	/**
	 *	Returns a hash code for this identifier.
	 *
	 *	@return the hash code value.
	 */
	@Override
	public int hashCode() {
		if (hashCode == 0) {
			final byte[] b = bytes;

			hashCode = 0x6030A +
					(((b[0] ^ b[1] ^ b[2] ^ b[3] ^ b[4] ^ b[5] ^ b[6] ^ b[7]) & 0xff) << 24)
					| (((b[8] ^ b[9] ^ b[10] ^ b[11] ^ b[12] ^ b[13] ^ b[14] ^ b[15]) & 0xff) << 16)
					| (((b[16] ^ b[17] ^ b[18] ^ b[19] ^ b[20] ^ b[21] ^ b[22] ^ b[23]) & 0xff) << 8)
					| ((b[24] ^ b[25] ^ b[26] ^ b[27] ^ b[28] ^ b[29] ^ b[30] ^ b[31]) & 0xff);
		}

		return hashCode;
	}

	/**
	 * Returns the identifier as a {@code BigInteger}, treating the bytes as an unsigned
	 * 256-bit integer in big-endian order.
	 *
	 * @return the {@code BigInteger} representation.
	 */
	public BigInteger toInteger() {
		return new BigInteger(1, bytes);
	}

	/**
	 * Returns the hexadecimal representation of this identifier, prefixed with "0x".
	 *
	 * @return the hexadecimal string.
	 */
	public String toHexString() {
		return "0x" + Hex.encode(bytes);
	}

	/**
	 * Returns an abbreviated hexadecimal representation of this identifier,
	 * showing the first 6 and last 4 characters with "..." in between, prefixed with "0x".
	 *
	 * @return the abbreviated hexadecimal string.
	 */
	public String toAbbrHexString() {
		String s = Hex.encode(bytes);
		return "0x" + s.substring(0, 6) + "..." + s.substring(s.length() - 4);
	}

	/**
	 * Returns the Base58-encoded representation of this identifier.
	 *
	 * @return the Base58 string.
	 */
	public String toBase58String() {
		if (b58 == null)
			b58 = Base58.encode(bytes);

		return b58;
	}

	/**
	 * Returns an abbreviated Base58 representation of this identifier,
	 * showing the first 4 and last 4 characters with "..." in between.
	 *
	 * @return the abbreviated Base58 string.
	 */
	public String toAbbrBase58String() {
		String s = toBase58String();
		return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
	}

	/**
	 * Returns the W3C Decentralized Identifier (DID) representation of this identifier,
	 * prefixed with "did:boson:" followed by the Base58 encoding.
	 *
	 * @return the DID string.
	 */
	public String toDIDString() {
		return DID_PREFIX + toBase58String();
	}

	/**
	 * Returns the binary representation of this identifier as a string of '0' and '1'
	 * characters.(For debug purposes)
	 *
	 * @param withSpaces if true, includes a space every 8 bits; if false, returns a compact string.
	 * @return the binary string.
	 */
	public String toBinaryString(boolean withSpaces) {
		StringBuilder repr = new StringBuilder(withSpaces ? SIZE + BYTES : SIZE);
		final char[] bits = new char[8];

		for (int i = 0; i < BYTES; i++) {
			int b = bytes[i] & 0xFF;
			for (int j = 7; j >= 0; j--) {
				bits[j] = (b & 1) == 1 ? '1' : '0';
				b >>>= 1;
			}

			repr.append(bits);
			if (withSpaces && i < BYTES - 1)
				repr.append(' ');
		}

		return repr.toString();
	}

	/**
	 * Returns the binary representation of this identifier as a string of '0' and '1'
	 * characters, with spaces every 8 bits for readability.(For debug purposes)
	 *
	 * @return the binary string.
	 */
	public String toBinaryString() {
		return toBinaryString(true);
	}

	/**
	 * Returns the default string representation of this identifier, using Base58 encoding.
	 *
	 * @return the Base58 string.
	 */
	@Override
	public String toString() {
		return toBase58String();
	}

	/**
	 * Returns an abbreviated string representation of this identifier, using Base58 encoding.
	 *
	 * @return the abbreviated Base58 string.
	 */
	public String toAbbrString() {
		return toAbbrBase58String();
	}
}