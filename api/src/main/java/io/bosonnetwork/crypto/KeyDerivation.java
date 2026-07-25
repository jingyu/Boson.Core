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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * KeyDerivation is libsodium compatible key derivation abstraction.
 * <p>
 * It implements the libsodium {@code crypto_kdf} API, which derives a subkey
 * from a master key, a subkey ID, and a context. The same three inputs always yield the same
 * subkey, and subkeys derived from different ids or contexts are independent of each other.
 * <p>
 * The {@code byte[]} context overload is wire-compatible with libsodium. The {@code String}
 * context overload is a Boson extension: it accepts a context of any length and reduces it to
 * {@value #CONTEXT_BYTES} bytes with {@link #contextBytes(String)}, so for an eight-character
 * context string it does <strong>not</strong> derive the same subkey as libsodium or as the
 * {@code byte[]} overload.
 */
public class KeyDerivation {
	/** Length in bytes of the master key. */
	public static final int MASTER_KEY_BYTES = CryptoProvider.KDF_MASTER_KEY_BYTES;
	/** Length in bytes of the derivation context. */
	public static final int CONTEXT_BYTES = CryptoProvider.KDF_CONTEXT_BYTES;
	/** Minimum length in bytes of a derived subkey. */
	public static final int MIN_SUBKEY_BYTES = CryptoProvider.KDF_SUBKEY_MIN_BYTES;
	/** Maximum length in bytes of a derived subkey. */
	public static final int MAX_SUBKEY_BYTES = CryptoProvider.KDF_SUBKEY_MAX_BYTES;

	private KeyDerivation() {}

	/**
	 * Derives a subkey from a master key using libsodium's {@code crypto_kdf} construction.
	 *
	 * @param masterKey     the {@value #MASTER_KEY_BYTES}-byte master key.
	 * @param subKeyId      the subkey identifier; interpreted as an unsigned 64-bit value.
	 * @param context       the {@value #CONTEXT_BYTES}-byte context.
	 * @param subKeyLength  the length of the derived subkey; from {@value #MIN_SUBKEY_BYTES} to
	 *                      {@value #MAX_SUBKEY_BYTES} bytes.
	 * @return the derived subkey.
	 * @throws IllegalArgumentException if any of the arguments are invalid.
	 * @throws NullPointerException if masterKey or context is null.
	 */
	public static byte[] deriveKey(byte[] masterKey, long subKeyId, byte[] context, int subKeyLength) {
		checkMasterKey(masterKey);

		if (Objects.requireNonNull(context, "context").length != CONTEXT_BYTES)
			throw new IllegalArgumentException("Invalid context size: expected " + CONTEXT_BYTES
					+ " bytes, got " + context.length);

		checkSubKeyLength(subKeyLength);

		return provider().kdfDeriveFromKey(masterKey, subKeyId, context, subKeyLength);
	}

	/**
	 * Derives a subkey from a master key using libsodium's {@code crypto_kdf} construction.
	 * <p>
	 * The context string is reduced to {@value #CONTEXT_BYTES} bytes with
	 * {@link #contextBytes(String)}; see there for the caveats that come with it.
	 *
	 * @param masterKey     the {@value #MASTER_KEY_BYTES}-byte master key.
	 * @param subKeyId      the subkey identifier; interpreted as an unsigned 64-bit value.
	 * @param context       the context string; must not be null or empty.
	 * @param subKeyLength  the length of the derived subkey; from {@value #MIN_SUBKEY_BYTES} to
	 *                      {@value #MAX_SUBKEY_BYTES} bytes.
	 * @return the derived subkey.
	 * @throws IllegalArgumentException if any of the arguments are invalid.
	 * @throws NullPointerException if masterKey or context is null.
	 */
	public static byte[] deriveKey(byte[] masterKey, long subKeyId, String context, int subKeyLength) {
		checkMasterKey(masterKey);
		return deriveKey(masterKey, subKeyId, contextBytes(context), subKeyLength);
	}

	/**
	 * Derives the fixed-length {@value #CONTEXT_BYTES}-byte key-derivation context from a
	 * context string.
	 * <p>
	 * The string is hashed with SHA-256 and the 32-byte digest is folded down to the
	 * {@value #CONTEXT_BYTES} bytes required by the {@code crypto_kdf} context.
	 * <p>
	 * <strong>Note:</strong> this reduction is a Boson extension, not part of libsodium. An
	 * eight-character string does not map to its own UTF-8 bytes, so
	 * {@code deriveKey(key, id, "Examples", len)} and
	 * {@code deriveKey(key, id, "Examples".getBytes(UTF_8), len)} derive different subkeys.
	 * <p>
	 * <strong>Note:</strong> the {@value #CONTEXT_BYTES}-byte context is a lossy reduction (the
	 * fixed context size), so distinct context strings can still collide and, for the same subkey
	 * id, derive the same key. Use distinct subkey ids when strong domain separation is required.
	 *
	 * @param context the context string; must not be null or empty.
	 * @return the {@value #CONTEXT_BYTES}-byte derivation context.
	 * @throws NullPointerException if {@code context} is null.
	 * @throws IllegalArgumentException if {@code context} is empty.
	 */
	public static byte[] contextBytes(String context) {
		Objects.requireNonNull(context, "context");
		if (context.isEmpty())
			throw new IllegalArgumentException("context must not be empty");

		byte[] contextBytes = new byte[CONTEXT_BYTES];
		byte[] hashBytes = Hash.sha256(context.getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < hashBytes.length; i++)
			contextBytes[i % CONTEXT_BYTES] += hashBytes[i];

		return contextBytes;
	}

	private static void checkMasterKey(byte[] masterKey) {
		if (Objects.requireNonNull(masterKey, "masterKey").length != MASTER_KEY_BYTES)
			throw new IllegalArgumentException("Invalid master key size: expected " + MASTER_KEY_BYTES
					+ " bytes, got " + masterKey.length);
	}

	private static void checkSubKeyLength(int subKeyLength) {
		if (subKeyLength < MIN_SUBKEY_BYTES || subKeyLength > MAX_SUBKEY_BYTES)
			throw new IllegalArgumentException("Invalid subkey size: expected " + MIN_SUBKEY_BYTES
					+ " to " + MAX_SUBKEY_BYTES + " bytes, got " + subKeyLength);
	}

	private static CryptoProvider provider() {
		return CryptoProviders.getDefault();
	}
}