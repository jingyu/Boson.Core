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

import java.util.Arrays;

import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.CryptoBox.PrivateKey;
import io.bosonnetwork.crypto.CryptoBox.PublicKey;
import io.bosonnetwork.crypto.CryptoException;

/**
 * <p>
 * CryptoContext provides a cryptographic context for encrypting and decrypting messages
 * using public-key authenticated encryption. It manages nonce generation and validation
 * to ensure message uniqueness and replay protection.
 * </p>
 * <p>
 * <b>Thread Safety:</b> The nonce generation for outgoing messages is synchronized to ensure
 * thread safety when used from multiple threads. The class is otherwise not strictly thread-safe
 * for other operations.
 * </p>
 */
public class CryptoContext implements AutoCloseable {
	private final Id id;
	private final CryptoBox box;

	private Nonce nextNonce;
	private Nonce lastPeerNonce;

    /**
     * Constructs a CryptoContext with the given Id and CryptoBox.
     * <p>
     * This constructor is typically used internally or for advanced use cases.
     * </p>
     *
     * @param id   The identity associated with this context.
     * @param box  The CryptoBox instance used for encryption and decryption.
     */
	public CryptoContext(Id id, CryptoBox box) {
		this.id = id;
		this.box = box;
		this.nextNonce = Nonce.random();
	}

    /**
     * Constructs a CryptoContext from the given identity, public key, and private key.
     *
     * @param id         The identity associated with this context.
     * @param publicKey  The public key of the remote party.
     * @param privateKey The private key of the local party.
     */
	public CryptoContext(Id id, PublicKey publicKey, PrivateKey privateKey) {
		this(id, CryptoBox.fromKeys(publicKey, privateKey));
	}

    /**
     * Constructs a CryptoContext from the given identity and private key.
     * <p>
     * The public key is derived from the identity.
     * </p>
     *
     * @param id         The identity associated with this context.
     * @param privateKey The private key of the local party.
     */
	public CryptoContext(Id id, PrivateKey privateKey) {
		this(id, CryptoBox.fromKeys(id.toEncryptionKey(), privateKey));
	}


    /**
     * Returns the identity associated with this CryptoContext.
     *
     * @return The {@link Id} of this context.
     */
	public Id getId() {
		return id;
	}

    /**
     * Generates and returns the next unique nonce for encryption, then increments the internal counter.
     * <p>
     * This method is synchronized to ensure thread safety for nonce generation.
     * </p>
     *
     * @return The next {@link Nonce} to use for encryption.
     */
	private synchronized Nonce getAndIncrementNonce() {
		Nonce nonce = nextNonce;
		nextNonce = nonce.increment();
		return nonce;
	}

    /**
     * Encrypts the given plaintext data and prepends a unique nonce to the ciphertext.
     * <p>
     * The nonce is automatically generated and incremented for each call, ensuring
     * message uniqueness and replay protection.
     * </p>
     *
     * @param data The plaintext data to encrypt.
     * @return The encrypted data, with the nonce prepended (nonce || ciphertext).
     * @throws NullPointerException if {@code data} is {@code null}.
     */
	public byte[] encrypt(byte[] data) {
		// TODO: how to avoid the memory copy?!
		Nonce nonce = getAndIncrementNonce();
		byte[] cipher = box.encrypt(data, nonce);

		byte[] buf = new byte[Nonce.BYTES + cipher.length];
		System.arraycopy(nonce.bytes(), 0, buf, 0, Nonce.BYTES);
		System.arraycopy(cipher, 0, buf, Nonce.BYTES, cipher.length);
		return buf;
	}

    /**
     * Decrypts the given data, verifying and extracting the prepended nonce.
     * <p>
     * This method checks for nonce reuse to prevent replay attacks. If the nonce
     * is duplicated (i.e., the same as the last received nonce), a {@link CryptoException}
     * is thrown.
     * </p>
     *
     * @param data The encrypted data, with the nonce prepended (nonce || ciphertext).
     * @return The decrypted plaintext data.
     * @throws CryptoException If the input is invalid, the nonce is duplicated, or decryption fails.
     * @throws NullPointerException if {@code data} is {@code null}.
     */
	public byte[] decrypt(byte[] data) throws CryptoException {
		if (data.length <= Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		// TODO: how to avoid the memory copy?!
		byte[] n = Arrays.copyOfRange(data, 0, Nonce.BYTES);
		Nonce nonce = Nonce.fromBytes(n);
		if (nonce.equals(lastPeerNonce))
			throw new CryptoException("Duplicated nonce");

		lastPeerNonce = nonce;

		byte[] cipher = Arrays.copyOfRange(data, Nonce.BYTES, data.length);
		return box.decrypt(cipher, nonce);
	}

    /**
     * Closes this CryptoContext and releases any underlying cryptographic resources.
     * <p>
     * After calling this method, the CryptoContext should not be used for further encryption or decryption.
     * </p>
     */
	@Override
	public void close() {
		box.close();
	}
}