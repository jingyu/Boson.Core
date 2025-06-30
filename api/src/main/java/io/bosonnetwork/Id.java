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
 * The Boson Identifiers. On the Boson network, all objects are identified through Id,
 * including nodes, values, peers...
 *
 * <p>This class is immutable and thread-safe. All instances of this class are
 * guaranteed to have a valid byte array of length {@link #BYTES}. The internal state
 * cannot be modified after construction.</p>
 */
public class Id implements Comparable<Id> {
	/**
	 * The number of bits used to represent an Id value in two's complement binary form.
	 */
	public static final int SIZE = 256;
	/**
	 * The number of bytes used to represent an Id value in two's complement binary form.
	 */
	public static final int BYTES = SIZE / Byte.SIZE;
	/**
	 * A constant holding the zero Id.
	 */
	public static final Id ZERO_ID = new Id();
	/**
	 * A constant holding the minimum Id.
	 */
	public static final Id MIN_ID = ZERO_ID;
	/**
	 * A constant holding the maximum Id.
	 */
	public static final Id MAX_ID = Id.ofHex("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

	private static final String DID_PREFIX = "did:boson:";

	// the performance for raw bytes is much better then BigInteger
	private final byte[] bytes;

	// Cache fields for expensive computations
	private volatile String b58;	// Cache for base58 string representation
	private volatile int hashCode;	// Cache for hash code

	/**
	 * 3-way comparison function, compare the ids by distance.
	 */
	public static final class Comparator implements java.util.Comparator<Id> {
		private final Id target;

		/**
		 * Creates a 3-way comparator object with target id.
		 *
		 * @param target the target id that compare to.
		 */
		public Comparator(Id target) {
			this.target = target;
		}

		/**
		 * Compares the two ids by the distance to the target id.
		 *
		 * @param o1 the first id to be compared.
		 * @param o2 the second id to be compared.
		 * @return a negative integer, zero, or a positive integer as the
		 *         distance to the first id is less than, equal to, or greater than
		 *         the distance to the second.
		 * @throws NullPointerException if an argument is null and this
		 *         comparator does not permit null arguments.
		 */
		@Override
		public int compare(Id o1, Id o2) {
			return target.threeWayCompare(o1, o2);
		}
	}

	/**
	 * Construct a random id.
	 */
	protected Id() {
		bytes = new byte[BYTES];
	}

	/**
	 * Construct an id from the existing id.
	 *
	 * @param id the existing id.
	 */
	protected Id(Id id) {
		this(id.bytes);
	}

	/**
	 * Construct an id from a byte array. it should at least {@link #BYTES} bytes available.
	 *
	 * @param buf the byte array that contains a binary id.
	 */
	protected Id(byte[] buf) {
		this(buf, 0);
	}

	/**
	 * Construct an id from a byte array, it should at least {@link #BYTES} bytes
	 * available after the offset.
	 *
	 * @param buf the byte array that contains a binary id.
	 * @param offset The offset of the subarray to be used.
	 */
	protected Id(byte[] buf, int offset) {
		this.bytes = Arrays.copyOfRange(buf, offset, offset + BYTES);
	}

	/**
	 * Creates an id from a byte array. it should at least {@link #BYTES} bytes available.
	 *
	 * @param buf the byte array that contains a binary id.
	 * @return the new created id.
	 * @throws IllegalArgumentException if the buf length less than {@link #BYTES} bytes.
	 */
	public static Id of(byte[] buf) {
		if (buf.length != BYTES)
			throw new IllegalArgumentException("Binary id should be " + BYTES + " bytes long.");

		return new Id(buf);
	}

	/**
	 * Creates an id from a byte array, it should at least {@link #BYTES} bytes
	 * available after the offset.
	 *
	 * @param buf the byte array that contains a binary id.
	 * @param offset The offset of the subarray to be used.
	 * @return the new created id.
	 * @throws IllegalArgumentException if the offset is invalid or less then {@link #BYTES}
	 * 	       bytes available in the buf after the offset.
	 */
	public static Id of(byte[] buf, int offset) {
		if (offset < 0)
			throw new IllegalArgumentException("Invalid offset, must be non-negative");

		if (buf.length - offset < BYTES)
			throw new IllegalArgumentException("Binary id should be " + BYTES + " bytes long.");

		return new Id(buf, offset);
	}

	/**
	 * Clone an existing id.
	 *
	 * @param id the existing id to clone.
	 * @return the new created id.
	 */
	public static Id of(Id id) {
		return new Id(id);
	}

	/**
	 * Creates an id from the id string representation. The string representation could be:
	 *   - hex representation with or the hex prefix '0x'
	 *   - or base58 representation
	 *
	 * @param id the id string .
	 * @return the new created id.
	 * @throws IllegalArgumentException if the id string is invalid id string representation.
	 */
	public static Id of(String id) {
		// return id.startsWith("0x") ? ofHex(id) :
		//		(id.startsWith(DID_PREFIX)) ? ofBase58(id.substring(DID_PREFIX.length())) : ofBase58(id);
		try {
			return ofBase58(id); // base58 is the first class citizen, fail back to hex or w3c format if base58 fails
		} catch (IllegalArgumentException e) {
			if (id.charAt(0) == '0' && id.charAt(1) == 'x')
				return ofHex(id);
			else if (id.startsWith(DID_PREFIX))
				return ofBase58(id.substring(DID_PREFIX.length()));
			else
				throw new IllegalArgumentException("invalid id string");
		}
	}

	/**
	 * Creates an id from the hex string representation.
	 *
	 * @param hexId the id string in hex representation.
	 * @return the new created id.
	 * @throws IllegalArgumentException if the id string is invalid hex representation.
	 */
	public static Id ofHex(String hexId) {
		int offset = hexId.startsWith("0x") ? 2 : 0;
		if (hexId.length() != BYTES * 2 + offset)
			throw new IllegalArgumentException("Hex ID string should be " + BYTES * 2 + " characters long.");

		return of(Hex.decode(hexId, offset, BYTES * 2));
	}

	/**
	 * Creates an id from the base58 string representation.
	 *
	 * @param base58Id the id string in base58 representation.
	 * @return the new created id.
	 * @throws IllegalArgumentException if the id string is invalid base58 representation.
	 */
	public static Id ofBase58(String base58Id) {
		return of(Base58.decode(base58Id));
	}

	/**
	 * Creates an id with the specified bit set to 1.
	 *
	 * @param idx the bit index, from high bit to low bit
	 * @return the new created id.
	 * @throws IllegalArgumentException if the idx out of range.
	 */
	public static Id ofBit(int idx) {
		if (idx < 0 || idx >= SIZE)
			throw new IllegalArgumentException("the index out of range");

		Id id = new Id();
		id.bytes[idx / 8] = (byte)(0x80 >>> (idx % 8));
		return id;
	}

	/**
	 * Creates a new id and set all bits with zero.
	 *
	 * @return the new created id.
	 */
	public static Id zero() {
		return ZERO_ID;
	}

	/**
	 * Creates a random id.
	 *
	 * @return the new created id.
	 */
	public static Id random() {
		Id id = new Id();
		new SecureRandom().nextBytes(id.bytes);
		return id;
	}

	/**
	 * Returns an array of bytes representing the id object.
	 *
	 * @return a copy of the internal bytes
	 */
	public byte[] getBytes() {
		return bytes.clone();
	}

	/**
	 * @hidden
	 *
	 * Returns the internal byte array of the id object.
	 * IMPORTANT: The returned array MUST NOT be modified.
	 *
	 * @return an array of bytes
	 *
	 * REMARK: This method exposes internal state and will be removed in a future version.
	 *         Use {@link #getBytes()} instead.
	 */
	public final byte[] bytes() {
		// Performance critical method: returns internal array directly
		// IMPORTANT: Callers must not modify the returned array
		return bytes;
	}

	/**
	 * Get the integer value from the offset of the id's binary form.
	 *
	 * Reads the next four bytes from the offset, composing them into an int value according to
	 * the big-endian byte order.
	 *
	 * @param offset the offset from which the byte to read.
	 * @return the int value at the offset.
	 * @throws ArrayIndexOutOfBoundsException if the offset exceed the limit.
	 */
	public int getInt(int offset) {
		return Byte.toUnsignedInt(bytes[offset]) << 24 |
				Byte.toUnsignedInt(bytes[offset+1]) << 16 |
				Byte.toUnsignedInt(bytes[offset+2]) << 8 |
				Byte.toUnsignedInt(bytes[offset+3]);
	}

	/**
	 * Returns a new id whose value is (this + id).
	 *
	 * @param id id to be added to this id.
	 * @return the new id whose value is (this + id).
	 */
	public Id add(Id id) {
		return add(this, id);
	}

	/**
	 * Returns a new id whose value is (id1 + id2).
	 *
	 * @param id1 id to be added.
	 * @param id2 id to be added.
	 * @return the new id whose value is (id1 + id2).
	 */
	public static Id add(Id id1, Id id2) {
		Id result = new Id();

		byte[] a = id1.bytes;
		byte[] b = id2.bytes;
		byte[] r = result.bytes;

		int carry = 0;
		for(int i = BYTES - 1; i >= 0; i--) {
			carry = (a[i] & 0xff) + (b[i] & 0xff) + carry;
			r[i] = (byte)(carry & 0xff);
			carry >>>= 8;
		}

		return result;
	}

	/**
	 * Checks the distance between this and another id using the XOR metric.
	 *
	 * @param to another id.
	 * @return The distance of the given id to this id.
	 */
	public Id distance(Id to) {
		return distance(this, to);
	}

	/**
	 * Checks the distance between two ids using the XOR metric.
	 *
	 * @param id1 id to be calculated the distance.
	 * @param id2 id to be calculated the distance.
	 * @return The distance between id1 and id2.
	 */
	public static Id distance(Id id1, Id id2) {
		Id result = new Id();

		byte[] r = result.bytes;
		byte[] a = id1.bytes;
		byte[] b = id2.bytes;

		for (int i = 0; i < BYTES; i++)
			r[i] = (byte) (a[i] ^ b[i]);

		return result;
	}

	/**
	 * Get an Id that is some distance away from this id.
	 *
	 * @param distance in number of bits
	 * @return the new generated Id
	 */
	public Id getIdByDistance(int distance) {
		byte[] result = new byte[BYTES];

		int zeroBytes = (SIZE - distance) / 8;
		int zeroBits = (SIZE - distance) % 8;

		// new byte array is initialized with all zeroes
		// Arrays.fill(result, 0, zeroBytes, (byte)0);

		if (zeroBytes < BYTES) {
			result[zeroBytes] = (byte)(0xFF >>> zeroBits);

			Arrays.fill(result, zeroBytes + 1, BYTES, (byte) 0xFF);
		}

		return this.distance(Id.of(result));
	}

	/**
	 * Gets the approx distance from this id to another id.
	 *
	 * @param to another id.
	 * @return the approx distance to another id.
	 */
	public int approxDistance(Id to) {
		return approxDistance(this, to);
	}

	/**
	 * Gets the approx distance between two ids.
	 *
	 * @param id1 id to be calculated the distance.
	 * @param id2 id to be calculated the distance.
	 * @return the approx distance between the two ids.
	 */
	public static int approxDistance(Id id1, Id id2) {
		/*
		 * Compute the xor of this and to Get the index i of the first set bit of the
		 * xor returned NodeId The distance between them is ID_LENGTH - i
		 */
		return SIZE - id1.distance(id2).getLeadingZeros();
	}

	/**
	 * Compares the distance of two ids relative to this one using the XOR metric.
	 *
	 * @param id1 id to be calculated the distance.
	 * @param id2 id to be calculated the distance.
	 * @return -1 if id1 is closer to this key, 0 if id1 and id2 are equal distant, 1 if
	 *         id2 is closer
	 */
	public int threeWayCompare(Id id1, Id id2) {
		int mmi = Arrays.mismatch(id1.bytes, id2.bytes);
		if (mmi == -1)
			return 0;

		int r = bytes[mmi] & 0xff;
		int a = id1.bytes[mmi] & 0xff;
		int b = id2.bytes[mmi] & 0xff;

		return Integer.compareUnsigned(a ^ r, b ^ r);
	}

	/**
	 * Counts the number of leading 0's in this id
	 *
	 * @return the number of leading 0's
	 */
	public int getLeadingZeros() {
		int msb = 0;

		int i;
		for (i = 0; i < BYTES && bytes[i] == 0; i++);
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
	 * Counts the number of trailing 0's in this id.
	 *
	 * @return the number of trailing 0's
	 */
	public int getTrailingZeros() {
		int lsb = 0;

		int i;
		for (i = BYTES - 1; i >= 0 && bytes[i] == 0; i--);
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
	 * Checks if the leading bits up to the n-th bit of both id are equal.
	 *
	 * @param id1 id to be checked.
	 * @param id2 id to be checked.
	 * @param n the n bits to check. if n &lt; 0 then no bits have to match;
	 *        otherwise n bytes have to match.
	 * @return true if the first bits up to the n-th bit of both keys are equal, otherwise false.
	 */
	public static boolean bitsEqual(Id id1, Id id2, int n) {
		if (n < 0)
			return true;

		int mmi = Arrays.mismatch(id1.bytes, id2.bytes);

		int indexToCheck = n >>> 3;
		int diff = (id1.bytes[indexToCheck] ^ id2.bytes[indexToCheck]) & 0xff;

		boolean bitsDiff = (diff & (0xff80 >>> (n & 0x07))) == 0;

		return mmi == indexToCheck ? bitsDiff : Integer.compareUnsigned(mmi, indexToCheck) > 0;
	}

	/**
	 * Copy the leading depth bits from the source id to the dest id.
	 *
	 * @param src source id object.
	 * @param dest dest id object.
	 * @param depth bits depth to copy.
	 */
	protected static void bitsCopy(Id src, Id dest, int depth) {
		if (depth < 0)
			return;

		// copy over all complete bytes
		int idx = depth >>> 3;
		if (idx > 0)
			System.arraycopy(src.bytes, 0, dest.bytes, 0, idx);

		int mask = 0xFF80 >>> (depth & 0x07);

		// mask out the part we have to copy over from the last prefix byte
		dest.bytes[idx] &= (byte) ~mask;
		// copy the bits from the last byte
		dest.bytes[idx] |= (byte) (src.bytes[idx] & mask);
	}

	/**
	 * Gets the Ed25519 signature public key from this id.
	 *
	 * @return the Ed25519 public key object.
	 */
	public Signature.PublicKey toSignatureKey() {
		return Signature.PublicKey.fromBytes(bytes);
	}

	/**
	 * Gets the X25519 encryption public key from this id.
	 *
	 * @return the X25519 public key object.
	 */
	public CryptoBox.PublicKey toEncryptionKey() {
		return CryptoBox.PublicKey.fromSignatureKey(toSignatureKey());
	}

	public static int compare(Id id1, Id id2) {
		return Arrays.compareUnsigned(id1.bytes, id2.bytes);
	}
	/**
	 * Compares this id with the specified id for ordering.
	 *
	 * @param o the id object to be compared.
	 * @return a negative integer, zero, or a positive integer as this id is less than,
	 *         equal to, or greater than the specified id.
	 */
	@Override
	public int compareTo(Id o) {
		return compare(this, o);
	}

	/**
	 * Compares this id with the specified id for equality.
	 *
	 * @param o Object to which this id is to be compared.
	 * @return true if and only if the specified Object is an Id
	 *         whose value is equal to this id.
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
	 *	Returns a hash code for this id.
	 *
	 *	@return a hash code value for this id.
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
	 * @return The BigInteger representation of the key
	 */
	public BigInteger toInteger() {
		return new BigInteger(1, bytes);
	}

	/**
	 * Returns the string representation of this id, using hex encoding.
	 *
	 * @return string representation of this id in hex encoding.
	 */
	public String toHexString() {
		return "0x" + Hex.encode(bytes);
	}

	/**
	 * Returns the abbreviation string representation of this id, using hex encoding.
	 *
	 * @return abbreviation string representation of this id in base58 encoding.
	 */
	public String toAbbrHexString() {
		String s = Hex.encode(bytes);
		return "0x" + s.substring(0, 6) + "..." + s.substring(s.length() - 4);
	}

	/**
	 * Returns the string representation of this id, using base58 encoding.
	 *
	 * @return string representation of this id in base58 encoding.
	 */
	public String toBase58String() {
		if (b58 == null)
			b58 = Base58.encode(bytes);

		return b58;
	}

	/**
	 * Returns the abbreviation string representation of this id, using base58 encoding.
	 *
	 * @return abbreviation string representation of this id in base58 encoding.
	 */
	public String toAbbrBase58String() {
		String s = toBase58String();
		return s.substring(0, 4) + "..." + s.substring(s.length() - 4);
	}

	public String toDIDString() {
		return DID_PREFIX + toBase58String();
	}

	/**
	 * Returns the string representation of this id, using binary form.
	 *
	 * @return string representation of this id in binary string.
	 */
	public String toBinaryString() {
		StringBuilder repr = new StringBuilder(SIZE + (SIZE >>> 2));

		for(int i = 0; i < SIZE; i++) {
			repr.append((bytes[i >>> 3] & (0x80 >> (i & 0x07))) != 0 ? '1' : '0');
			if ((i & 0x03) == 0x03) repr.append(' ');
		}
		return repr.toString();
	}

	/**
	 * Returns the string representation of this id, using base58 encoding.
	 *
	 * @return string representation of this id.
	 */
	@Override
	public String toString() {
		return this.toBase58String();
	}

	// To abbreviation
	public String toAbbrString() {
		return toAbbrBase58String();
	}
}