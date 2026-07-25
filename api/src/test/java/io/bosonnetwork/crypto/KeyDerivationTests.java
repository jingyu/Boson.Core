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

package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.utils.Hex;

public class KeyDerivationTests {
	// The libsodium crypto_kdf test setup: master key = 0x00..0x1f, context = "KDF test".
	private static final byte[] MASTER_KEY = masterKey();
	private static final byte[] CONTEXT = "KDF test".getBytes(StandardCharsets.UTF_8);

	private static byte[] masterKey() {
		byte[] key = new byte[KeyDerivation.MASTER_KEY_BYTES];
		for (int i = 0; i < key.length; i++)
			key[i] = (byte) i;

		return key;
	}

	/**
	 * Known-answer vectors produced by libsodium itself (via the Tuweni-backed provider used by
	 * {@link CryptoCompatibilityTest}). These pin the derived bytes, so any future change to the
	 * derivation - including to the string-context reduction - fails loudly instead of silently
	 * rotating every key derived through this class.
	 */
	@Test
	public void deriveKeyMatchesLibsodiumVectors() {
		assertEquals("c13fcc2e6cd0cd0f82d93b163a5696c5105378f8c629d36baf3ae0239de9c280",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, 0L, CONTEXT, 32)));
		assertEquals("3c387fab802aae447033073e76ee002c",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, 1L, CONTEXT, 16)));
		assertEquals("1944da61ff18dc2028c3578ac85be904931b83860896598f62468f1cb5471c6a"
						+ "344c945dbc62c9aaf70feb62472d17775ea5db6ed5494c68b7a9a59761f39614",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, 2L, CONTEXT, 64)));
	}

	@Test
	public void deriveKeyWithStringContextMatchesLibsodiumVectors() {
		assertEquals("eb016430bdcb8dc6", Hex.encode(KeyDerivation.contextBytes("Examples")));
		assertEquals("a6a00d076ad2ac30d383f45064a0072826775e70f1ea90d9a8cc68d2cfb616e3",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, 1L, "Examples", 32)));

		assertEquals("360aedb931bd498f", Hex.encode(KeyDerivation.contextBytes("boson.kdf")));
		assertEquals("95197bd7e2af95d0bae701cc7469181dce25099cb4275737132fdcf7d8605bbe",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, 7L, "boson.kdf", 32)));
	}

	@Test
	public void deriveKeyIsDeterministic() {
		byte[] subKey1 = KeyDerivation.deriveKey(MASTER_KEY, 12345L, CONTEXT, 32);
		byte[] subKey2 = KeyDerivation.deriveKey(MASTER_KEY, 12345L, CONTEXT, 32);

		assertNotNull(subKey1);
		assertEquals(32, subKey1.length);
		assertArrayEquals(subKey1, subKey2);

		byte[] subKey3 = KeyDerivation.deriveKey(MASTER_KEY, 12345L, "a context string", 32);
		byte[] subKey4 = KeyDerivation.deriveKey(MASTER_KEY, 12345L, "a context string", 32);

		assertNotNull(subKey3);
		assertEquals(32, subKey3.length);
		assertArrayEquals(subKey3, subKey4);
	}

	@Test
	public void deriveKeySeparatesIdsContextsAndMasterKeys() {
		byte[] subKey = KeyDerivation.deriveKey(MASTER_KEY, 12345L, CONTEXT, 32);

		// A different subkey id derives a different subkey.
		assertFalse(Arrays.equals(subKey, KeyDerivation.deriveKey(MASTER_KEY, 12346L, CONTEXT, 32)));

		// A different context derives a different subkey.
		byte[] otherContext = "KDF tesu".getBytes(StandardCharsets.UTF_8);
		assertFalse(Arrays.equals(subKey, KeyDerivation.deriveKey(MASTER_KEY, 12345L, otherContext, 32)));

		// A different master key derives a different subkey.
		byte[] otherMasterKey = MASTER_KEY.clone();
		otherMasterKey[0] ^= 0x01;
		assertFalse(Arrays.equals(subKey, KeyDerivation.deriveKey(otherMasterKey, 12345L, CONTEXT, 32)));

		// The same holds for the string-context overload.
		byte[] strSubKey = KeyDerivation.deriveKey(MASTER_KEY, 12345L, "context one", 32);
		assertFalse(Arrays.equals(strSubKey, KeyDerivation.deriveKey(MASTER_KEY, 12346L, "context one", 32)));
		assertFalse(Arrays.equals(strSubKey, KeyDerivation.deriveKey(MASTER_KEY, 12345L, "context two", 32)));
	}

	@Test
	public void deriveKeyAcceptsTheFullSubKeyLengthRange() {
		for (int len = KeyDerivation.MIN_SUBKEY_BYTES; len <= KeyDerivation.MAX_SUBKEY_BYTES; len++) {
			assertEquals(len, KeyDerivation.deriveKey(MASTER_KEY, 1L, CONTEXT, len).length,
					"byte[] context, subkey length " + len);
			assertEquals(len, KeyDerivation.deriveKey(MASTER_KEY, 1L, "ctx", len).length,
					"string context, subkey length " + len);
		}
	}

	/**
	 * libsodium treats the subkey id as an unsigned 64-bit value; Java has no unsigned long, so
	 * the whole 64-bit range - including ids that are negative as a Java long - has to work.
	 */
	@Test
	public void subKeyIdCoversTheFullUnsignedRange() {
		assertEquals("500c3043b2b9177ec843ecbe9f98f92d8c11fbbd10a225ab844548de89c21d55",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, -1L, CONTEXT, 32)));
		assertEquals("53ea7c0e4328f827a6079c3db8ba9eae1b1f94d91f6eccf25c39b41fd2fcbb02",
				Hex.encode(KeyDerivation.deriveKey(MASTER_KEY, Long.MAX_VALUE, CONTEXT, 32)));

		byte[] zero = KeyDerivation.deriveKey(MASTER_KEY, 0L, CONTEXT, 32);
		byte[] min = KeyDerivation.deriveKey(MASTER_KEY, Long.MIN_VALUE, CONTEXT, 32);
		assertEquals(32, min.length);
		assertFalse(Arrays.equals(zero, min));
	}

	/**
	 * The string-context overload hashes its argument rather than using it verbatim, so it is NOT
	 * interchangeable with the libsodium-compatible byte[] overload even for an eight-character
	 * context. This is a documented Boson extension; pin it so it cannot drift unnoticed.
	 */
	@Test
	public void stringContextIsHashedNotUsedVerbatim() {
		byte[] fromString = KeyDerivation.deriveKey(MASTER_KEY, 1L, "Examples", 32);
		byte[] fromBytes = KeyDerivation.deriveKey(MASTER_KEY, 1L,
				"Examples".getBytes(StandardCharsets.UTF_8), 32);

		assertFalse(Arrays.equals(fromString, fromBytes));
		assertArrayEquals(fromString,
				KeyDerivation.deriveKey(MASTER_KEY, 1L, KeyDerivation.contextBytes("Examples"), 32));
	}

	/**
	 * {@code Signature.PrivateKey.derive(id, String)} reduces its context with the same helper.
	 * Deployed identities depend on the two staying in lockstep.
	 */
	@Test
	public void signatureDeriveUsesTheSameContextReduction() {
		Signature.KeyPair keyPair = Signature.KeyPair.random();
		Signature.PrivateKey derived = keyPair.privateKey().derive(42L, "boson.identity");

		byte[] expectedSeed = KeyDerivation.deriveKey(keyPair.privateKey().seed(), 42L,
				"boson.identity", Signature.KeyPair.SEED_BYTES);

		assertArrayEquals(expectedSeed, derived.seed());
	}

	@Test
	public void contextBytesLength() {
		assertEquals(KeyDerivation.CONTEXT_BYTES, KeyDerivation.contextBytes("x").length);
		assertEquals(KeyDerivation.CONTEXT_BYTES,
				KeyDerivation.contextBytes("a very long context string, well past eight bytes").length);
		assertArrayEquals(KeyDerivation.contextBytes("stable"), KeyDerivation.contextBytes("stable"));
		assertFalse(Arrays.equals(KeyDerivation.contextBytes("one"), KeyDerivation.contextBytes("two")));
	}

	@Test
	public void testInvalidArguments() {
		byte[] masterKey = new byte[KeyDerivation.MASTER_KEY_BYTES];
		byte[] context = new byte[KeyDerivation.CONTEXT_BYTES];
		long subKeyId = 1;

		// Null master key
		assertThrows(NullPointerException.class, () ->
			KeyDerivation.deriveKey(null, subKeyId, context, 32));
		assertThrows(NullPointerException.class, () ->
			KeyDerivation.deriveKey(null, subKeyId, "ctx", 32));

		// Invalid master key length
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(new byte[KeyDerivation.MASTER_KEY_BYTES - 1], subKeyId, context, 32));
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(new byte[KeyDerivation.MASTER_KEY_BYTES + 1], subKeyId, "ctx", 32));

		// Null context byte array
		assertThrows(NullPointerException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, (byte[]) null, 32));

		// Invalid context length
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, new byte[KeyDerivation.CONTEXT_BYTES - 1], 32));
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, new byte[KeyDerivation.CONTEXT_BYTES + 1], 32));

		// Null context string
		assertThrows(NullPointerException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, (String) null, 32));
		assertThrows(NullPointerException.class, () -> KeyDerivation.contextBytes(null));

		// Empty context string
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, "", 32));
		assertThrows(IllegalArgumentException.class, () -> KeyDerivation.contextBytes(""));

		// Invalid subKeyLength (too small)
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, context, KeyDerivation.MIN_SUBKEY_BYTES - 1));
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, "ctx", KeyDerivation.MIN_SUBKEY_BYTES - 1));

		// Invalid subKeyLength (too large)
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, context, KeyDerivation.MAX_SUBKEY_BYTES + 1));
		assertThrows(IllegalArgumentException.class, () ->
			KeyDerivation.deriveKey(masterKey, subKeyId, "ctx", KeyDerivation.MAX_SUBKEY_BYTES + 1));
	}
}
