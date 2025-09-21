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

package io.bosonnetwork;

import io.bosonnetwork.crypto.CryptoException;


/**
 * The {@code Identity} interface abstracts an entity capable of performing cryptographic operations.
 * <p>
 * Implementations of this interface represent entities that can sign and verify data,
 * encrypt and decrypt messages (either in one-shot or via reusable cryptographic contexts),
 * and establish secure cryptographic contexts with other identities.
 * </p>
 * <p>
 * This interface is intended to provide a unified API for cryptographic operations
 * such as digital signatures and authenticated encryption in the Boson Network.
 * </p>
 */
public interface Identity {
	/**
	 * Returns the unique identifier associated with this identity.
	 *
	 * @return the {@link Id} representing this identity
	 */
	Id getId();

	/**
	 * Signs the provided data using this identity's private key.
	 *
	 * @param data the data to be signed
	 * @return the generated digital signature as a byte array
	 */
	byte[] sign(byte[] data);

	/**
	 * Verifies that the provided signature is valid for the given data using this identity's public key.
	 *
	 * @param data the original data that was signed
	 * @param signature the digital signature to verify
	 * @return {@code true} if the signature is valid; {@code false} otherwise
	 */
	boolean verify(byte[] data, byte[] signature);

	/**
	 * Encrypts the provided data for the specified recipient using a one-shot encryption operation.
	 *
	 * @param recipient the {@link Id} of the intended recipient
	 * @param data the plaintext data to encrypt
	 * @return the encrypted data as a byte array
	 * @throws CryptoException if encryption fails due to cryptographic errors or invalid parameters
	 */
	byte[] encrypt(Id recipient, byte[] data) throws CryptoException;

	/**
	 * Decrypts the provided data sent by the specified sender using a one-shot decryption operation.
	 *
	 * @param sender the {@link Id} of the sender who encrypted the data
	 * @param data the encrypted data to decrypt
	 * @return the decrypted plaintext data as a byte array
	 * @throws CryptoException if decryption fails due to cryptographic errors or invalid parameters
	 */
	byte[] decrypt(Id sender, byte[] data) throws CryptoException;

	/**
	 * Creates a reusable cryptographic context for secure communication with the specified identity.
	 * <p>
	 * The returned {@link CryptoContext} can be used for multiple encryption and decryption operations
	 * with the given peer.
	 * </p>
	 *
	 * @param id the {@link Id} of the peer to establish a context with
	 * @return a new {@link CryptoContext} for secure communication
	 * @throws CryptoException if the context cannot be established due to cryptographic errors
	 */
	CryptoContext createCryptoContext(Id id) throws CryptoException;
}