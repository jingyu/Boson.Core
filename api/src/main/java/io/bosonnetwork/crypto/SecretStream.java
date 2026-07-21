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

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * SecretStream provides stream encryption and decryption using the XChaCha20-Poly1305 algorithm.
 * <p>
 * This is a high-level API compatible with Libsodium's {@code crypto_secretstream_xchacha20poly1305} functions.
 * It allows encrypting and decrypting a sequence of message chunks with authentication, optional additional data (AAD),
 * and explicit stream completion signaling.
 *
 * @see <a href="https://libsodium.gitbook.io/doc/secret-key_cryptography/secretstream">Libsodium Secretstream Documentation</a>
 */
public interface SecretStream extends AutoCloseable {
	/**
	 * Required key size in bytes for secret stream encryption and decryption (32 bytes).
	 */
	int KEY_BYTES = CryptoProvider.SECRET_STREAM_KEY_BYTES;

	/**
	 * Length in bytes of the header required to initialize a stream (24 bytes).
	 */
	int HEADER_BYTES = CryptoProvider.SECRET_STREAM_HEADER_BYTES;

	/**
	 * Authentication tag overhead in bytes added to each encrypted message block (17 bytes).
	 */
	int ABYTES = CryptoProvider.SECRET_STREAM_ABYTES;

	/**
	 * Stream encryptor for encrypting sequential message blocks using XChaCha20-Poly1305.
	 * <p>
	 * Create instances using {@link SecretStream#encryptionStream(byte[])}.
	 */
	class EncryptionStream implements SecretStream {
		/**
		 * The underlying cryptographic state for secret stream encryption.
		 */
		private final CryptoProvider.SecretStreamState state;

		/**
		 * Constructs an {@code EncryptionStream} wrapping the given secret stream state.
		 *
		 * @param state the underlying secret stream state
		 */
		private EncryptionStream(CryptoProvider.SecretStreamState state) {
			this.state = state;
		}

		/**
		 * Returns the stream header generated during initialization.
		 * <p>
		 * The header must be communicated to the recipient before decryption can begin,
		 * as it is required to initialize a {@link DecryptionStream}.
		 *
		 * @return a byte array containing the header
		 */
		public byte[] header() {
			return state.header();
		}

		/**
		 * Encrypts a message chunk with optional additional authenticated data (AAD) and specifies
		 * whether this is the final block in the stream.
		 *
		 * @param message the plain text block to encrypt (cannot be {@code null})
		 * @param additional optional additional authenticated data, or {@code null}
		 * @param finalBlock {@code true} if this is the final block of the stream; {@code false} otherwise
		 * @return the encrypted block (ciphertext including authentication tag)
		 * @throws NullPointerException if {@code message} is {@code null}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 */
		public byte[] push(byte @Nullable [] message, byte @Nullable [] additional, boolean finalBlock) {
			Objects.requireNonNull(message, "message cannot be null");
			if (state.isDestroyed())
				throw new IllegalStateException("stream has been closed");
			if (state.isComplete())
				throw new IllegalStateException("stream already completed");

			return state.push(message, additional, finalBlock);
		}

		/**
		 * Encrypts a non-final message chunk with optional additional authenticated data (AAD).
		 *
		 * @param message the plain text block to encrypt (cannot be {@code null})
		 * @param additional optional additional authenticated data, or {@code null}
		 * @return the encrypted block (ciphertext including authentication tag)
		 * @throws NullPointerException if {@code message} is {@code null}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 */
		public byte[] push(byte @Nullable [] message, byte @Nullable [] additional) {
			return push(message, additional, false);
		}

		/**
		 * Encrypts a non-final message chunk without additional authenticated data.
		 *
		 * @param message the plain text block to encrypt (cannot be {@code null})
		 * @return the encrypted block (ciphertext including authentication tag)
		 * @throws NullPointerException if {@code message} is {@code null}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 */
		public byte[] push(byte @Nullable [] message) {
			return push(message, null, false);
		}

		/**
		 * Encrypts the final message chunk in the stream with optional additional authenticated data (AAD).
		 *
		 * @param message the plain text block to encrypt (cannot be {@code null})
		 * @param additional optional additional authenticated data, or {@code null}
		 * @return the encrypted block (ciphertext including authentication tag)
		 * @throws NullPointerException if {@code message} is {@code null}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 */
		public byte[] pushLast(byte @Nullable [] message, byte @Nullable [] additional) {
			return push(message, additional, true);
		}

		/**
		 * Encrypts the final message chunk in the stream without additional authenticated data.
		 *
		 * @param message the plain text block to encrypt (cannot be {@code null})
		 * @return the encrypted block (ciphertext including authentication tag)
		 * @throws NullPointerException if {@code message} is {@code null}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 */
		public byte[] pushLast(byte @Nullable [] message) {
			return push(message, null, true);
		}

		/**
		 * Returns true if the stream is complete.
		 *
		 * @return {@code true} if no more messages should be encrypted by this stream; {@code false} otherwise
		 */
		@Override
		public boolean isComplete() {
			return state.isComplete();
		}

		/**
		 * Closes the encryption stream and destroys the underlying cryptographic state.
		 */
		@Override
		public void close() {
			state.destroy();
		}
	}

	/**
	 * Stream decryptor for decrypting sequential message blocks using XChaCha20-Poly1305.
	 * <p>
	 * Create instances using {@link SecretStream#decryptionStream(byte[], byte[])}.
	 */
	class DecryptionStream implements SecretStream {
		/**
		 * The underlying cryptographic state for secret stream decryption.
		 */
		private final CryptoProvider.SecretStreamState state;

		/**
		 * Constructs a {@code DecryptionStream} wrapping the given secret stream state.
		 *
		 * @param state the underlying secret stream state
		 */
		private DecryptionStream(CryptoProvider.SecretStreamState state) {
			this.state = state;
		}

		/**
		 * Decrypts and authenticates a ciphertext block using optional additional authenticated data (AAD).
		 *
		 * @param ciphertext the ciphertext block to decrypt (cannot be {@code null}, length must be at least {@link SecretStream#ABYTES})
		 * @param additional optional additional authenticated data, or {@code null}
		 * @return the decrypted plain text block
		 * @throws NullPointerException if {@code ciphertext} is {@code null}
		 * @throws IllegalArgumentException if {@code ciphertext} length is less than {@link SecretStream#ABYTES}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 * @throws CryptoException if decryption or authentication fails
		 */
		public byte[] pull(byte[] ciphertext, byte @Nullable [] additional) {
			Objects.requireNonNull(ciphertext, "ciphertext cannot be null");
			if (ciphertext.length < ABYTES) {
				throw new IllegalArgumentException("ciphertext too short");
			}

			if (state.isDestroyed())
				throw new IllegalStateException("stream has been closed");
			if (state.isComplete())
				throw new IllegalStateException("stream already completed");

			return state.pull(ciphertext, additional);
		}

		/**
		 * Decrypts and authenticates a ciphertext block without additional authenticated data.
		 *
		 * @param ciphertext the ciphertext block to decrypt (cannot be {@code null}, length must be at least {@link SecretStream#ABYTES})
		 * @return the decrypted plain text block
		 * @throws NullPointerException if {@code ciphertext} is {@code null}
		 * @throws IllegalArgumentException if {@code ciphertext} length is less than {@link SecretStream#ABYTES}
		 * @throws IllegalStateException if the stream has been closed or is already completed
		 * @throws CryptoException if decryption or authentication fails
		 */
		public byte[] pull(byte[] ciphertext) {
			return pull(ciphertext, null);
		}

		/**
		 * Returns true if the stream is complete.
		 *
		 * @return {@code true} if no more messages should be decrypted by this stream; {@code false} otherwise
		 */
		@Override
		public boolean isComplete() {
			return state.isComplete();
		}

		/**
		 * Closes the decryption stream and destroys the underlying cryptographic state.
		 */
		@Override
		public void close() {
			state.destroy();
		}
	}

	/**
	 * Creates and initializes a new {@link EncryptionStream} with the specified secret key.
	 *
	 * @param key the secret key of length {@link SecretStream#KEY_BYTES}
	 * @return a new {@link EncryptionStream} instance
	 * @throws NullPointerException if {@code key} is {@code null}
	 * @throws IllegalArgumentException if {@code key} length is not equal to {@link SecretStream#KEY_BYTES}
	 */
	static EncryptionStream encryptionStream(byte[] key) {
		Objects.requireNonNull(key, "key cannot be null");
		if (key.length != KEY_BYTES)
			throw new IllegalArgumentException("key must be " + KEY_BYTES + " bytes long");
		return new EncryptionStream(provider().secretStreamInitPush(key));
	}

	/**
	 * Creates and initializes a new {@link DecryptionStream} with the specified header and secret key.
	 *
	 * @param header the header generated by {@link EncryptionStream#header()} of length {@link SecretStream#HEADER_BYTES}
	 * @param key the secret key of length {@link SecretStream#KEY_BYTES}
	 * @return a new {@link DecryptionStream} instance
	 * @throws NullPointerException if {@code header} or {@code key} is {@code null}
	 * @throws IllegalArgumentException if {@code header} length is not {@link SecretStream#HEADER_BYTES} or {@code key} length is not {@link SecretStream#KEY_BYTES}
	 */
	static DecryptionStream decryptionStream(byte[] header, byte[] key) {
		Objects.requireNonNull(header, "header cannot be null");
		if (header.length != HEADER_BYTES)
			throw new IllegalArgumentException("header must be " + HEADER_BYTES + " bytes long");
		Objects.requireNonNull(key, "key cannot be null");
		if (key.length != KEY_BYTES)
			throw new IllegalArgumentException("key must be " + KEY_BYTES + " bytes long");

		return new DecryptionStream(provider().secretStreamInitPull(header, key));
	}

	/**
	 * Returns true if the stream is complete.
	 *
	 * @return {@code true} if no more messages should be encrypted or decrypted by this stream; {@code false} otherwise
	 */
	boolean isComplete();

	/**
	 * {@inheritDoc}
	 */
	@Override
	void close();

	/**
	 * Returns the default cryptographic provider instance.
	 *
	 * @return the default {@link CryptoProvider}
	 */
	private static CryptoProvider provider() {
		return CryptoProviders.getDefault();
	}
}