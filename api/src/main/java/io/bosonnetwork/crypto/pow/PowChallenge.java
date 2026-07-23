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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;

/**
 * A stateless, super-node-signed proof-of-work challenge (see director/docs/RegistrationPoW.md).
 *
 * <p>The super node issues one of these on demand, signs its serialized bytes with a derived Ed25519
 * key, and hands the (token bytes, signature) pair to the client. The client solves the puzzle and
 * echoes the token and signature back with its registration request. Because the token carries its
 * own authenticated parameters and expiry, the super node keeps no per-challenge state: it verifies
 * the signature over the exact bytes it receives, checks the expiry, and trusts the parameters.
 *
 * <p>The serialized form is a CBOR array:
 * <pre>
 *     [ version, algorithm, n, k, effort, nonce, issuedAt, expiresAt, keyId ]
 * </pre>
 */
public record PowChallenge(
		int version,
		String algorithm,
		int n,
		int k,
		int effort,
		byte[] nonce,
		long issuedAt,
		long expiresAt,
		int keyId) {

	/** The current protocol version. */
	public static final int VERSION = 1;
	/** The Equihash algorithm identifier. */
	public static final String ALG_EQUIHASH = "equihash";
	/** The byte length of the challenge nonce. */
	public static final int NONCE_BYTES = 32;

	private static final int FIELD_COUNT = 9;

	/**
	 * Constructs a new instance of the PowChallenge class.
	 * <p>
	 * Validates required parameters and ensures the nonce array has the exact
	 * length specified by NONCE_BYTES.
	 *
	 * @param version the protocol version
	 * @param algorithm the proof-of-work algorithm identifier
	 * @param n the Equihash parameter N
	 * @param k the Equihash parameter K
	 * @param effort the difficulty level or effort required
	 * @param nonce the unique challenge nonce
	 * @param issuedAt the time the challenge was issued in epoch seconds
	 * @param expiresAt the time the challenge expires in epoch seconds
	 * @param keyId the identifier of the signing key
	 */
	public PowChallenge {
		Objects.requireNonNull(algorithm, "algorithm");
		Objects.requireNonNull(nonce, "nonce");
		if (nonce.length != NONCE_BYTES)
			throw new IllegalArgumentException("nonce must be " + NONCE_BYTES + " bytes");
	}

	/**
	 * Serializes this challenge to its CBOR byte form. These are the exact bytes that are signed and
	 * echoed on the wire.
	 *
	 * @return the CBOR-encoded token bytes.
	 */
	public byte[] toBytes() {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(96);
		try (JsonGenerator gen = Json.cborFactory().createGenerator(bos)) {
			gen.writeStartArray(this, FIELD_COUNT);
			gen.writeNumber(version);
			gen.writeString(algorithm);
			gen.writeNumber(n);
			gen.writeNumber(k);
			gen.writeNumber(effort);
			gen.writeBinary(nonce);
			gen.writeNumber(issuedAt);
			gen.writeNumber(expiresAt);
			gen.writeNumber(keyId);
			gen.writeEndArray();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to encode PoW challenge", e);
		}
		return bos.toByteArray();
	}

	/**
	 * Parses a challenge from its CBOR byte form.
	 *
	 * @param bytes the token bytes (as received on the wire).
	 * @return the parsed challenge.
	 * @throws IllegalArgumentException if the bytes are not a well-formed challenge token.
	 */
	public static PowChallenge fromBytes(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes");
		try (JsonParser p = Json.cborFactory().createParser(bytes)) {
			if (p.nextToken() != JsonToken.START_ARRAY)
				throw new IllegalArgumentException("Malformed PoW challenge: expected an array");

			int version = nextInt(p);
			String algorithm = nextString(p);
			int n = nextInt(p);
			int k = nextInt(p);
			int effort = nextInt(p);
			byte[] nonce = nextBinary(p);
			long issuedAt = nextLong(p);
			long expiresAt = nextLong(p);
			int keyId = nextInt(p);

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw new IllegalArgumentException("Malformed PoW challenge: trailing fields");

			return new PowChallenge(version, algorithm, n, k, effort, nonce, issuedAt, expiresAt, keyId);
		} catch (IOException e) {
			throw new IllegalArgumentException("Failed to decode PoW challenge", e);
		}
	}

	/**
	 * Signs this challenge's bytes with the given key.
	 *
	 * @param key the super node's derived signing key.
	 * @return the detached signature over {@link #toBytes()}.
	 */
	public byte[] sign(Signature.PrivateKey key) {
		return key.sign(toBytes());
	}

	/**
	 * Verifies a detached signature over token bytes. Verification is performed over the exact bytes
	 * received, so no canonical re-encoding is required.
	 *
	 * @param tokenBytes the token bytes as received.
	 * @param signature  the detached signature.
	 * @param key        the super node's public verification key.
	 * @return {@code true} if the signature is valid.
	 */
	public static boolean verify(byte[] tokenBytes, byte[] signature, Signature.PublicKey key) {
		return key.verify(tokenBytes, signature);
	}

	/**
	 * Returns whether this challenge has expired at the given time.
	 *
	 * @param nowEpochSeconds the current time in epoch seconds.
	 * @return {@code true} if {@code now >= expiresAt}.
	 */
	public boolean isExpired(long nowEpochSeconds) {
		return nowEpochSeconds >= expiresAt;
	}

	private static int nextInt(JsonParser p) throws IOException {
		requireScalar(p.nextToken());
		return p.getIntValue();
	}

	private static long nextLong(JsonParser p) throws IOException {
		requireScalar(p.nextToken());
		return p.getLongValue();
	}

	private static String nextString(JsonParser p) throws IOException {
		if (p.nextToken() != JsonToken.VALUE_STRING)
			throw new IllegalArgumentException("Malformed PoW challenge: expected a string");
		return p.getText();
	}

	private static byte[] nextBinary(JsonParser p) throws IOException {
		if (p.nextToken() != JsonToken.VALUE_EMBEDDED_OBJECT && p.currentToken() != JsonToken.VALUE_STRING)
			throw new IllegalArgumentException("Malformed PoW challenge: expected a byte string");
		return p.getBinaryValue();
	}

	private static void requireScalar(JsonToken token) {
		if (token != JsonToken.VALUE_NUMBER_INT)
			throw new IllegalArgumentException("Malformed PoW challenge: expected an integer");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof PowChallenge other))
			return false;
		return version == other.version && n == other.n && k == other.k && effort == other.effort
				&& issuedAt == other.issuedAt && expiresAt == other.expiresAt && keyId == other.keyId
				&& algorithm.equals(other.algorithm) && java.util.Arrays.equals(nonce, other.nonce);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(version, algorithm, n, k, effort, issuedAt, expiresAt, keyId);
		result = 31 * result + java.util.Arrays.hashCode(nonce);
		return result;
	}

	@Override
	public String toString() {
		return "PowChallenge[version=" + version + ", algorithm=" + algorithm + ", n=" + n + ", k=" + k
				+ ", effort=" + effort + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt
				+ ", keyId=" + keyId + "]";
	}
}