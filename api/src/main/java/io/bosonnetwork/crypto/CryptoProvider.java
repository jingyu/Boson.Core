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

import org.jspecify.annotations.Nullable;

/**
 * Service provider interface for the low-level cryptographic primitives used by Boson.
 * <p>
 * Keys and nonces are represented as provider-specific objects ({@link Signature.PublicKey},
 * {@link Signature.PrivateKey}, {@link CryptoBox.PublicKey}, {@link CryptoBox.PrivateKey},
 * {@link CryptoBox.Nonce}, and the precomputed {@link CryptoBox} itself), so a backend can keep
 * its native representation across calls; messages, ciphertexts, hashes and salts are plain
 * {@code byte[]}. The public wrapper classes ({@link Signature}, {@link CryptoBox},
 * {@link PasswordHash}) delegate to the active provider without exposing any implementation type.
 * The default backend is the pure-Java {@link BouncyCastleCryptoProvider}; an alternative backend
 * (for example a future JNI binding to libsodium) can be supplied through the
 * {@link java.util.ServiceLoader} mechanism, discovered by {@link CryptoProviders}.
 * <p>
 * A key object is owned by the provider that created it. A provider that is handed a foreign key
 * object (for example after the active provider was swapped) must still accept it by reconstructing
 * from its raw {@link Signature.PublicKey#bytes() bytes}. Once a key object has been destroyed it
 * must reject further use rather than read freed or zeroed material. The one exception to the
 * foreign-object fallback is the precomputed {@link CryptoBox}: it exposes no shared-key bytes (a
 * native backend may keep the key only in native memory), so it cannot be reconstructed from a
 * foreign instance - {@link #boxEncrypt(byte[], CryptoBox.Nonce, CryptoBox)} and
 * {@link #boxDecrypt(byte[], CryptoBox.Nonce, CryptoBox)} require a box created by the same provider
 * and reject one from another.
 * <p>
 * Every implementation must be byte-for-byte compatible with the libsodium constructions:
 * Ed25519 detached signatures, {@code crypto_kdf} (keyed BLAKE2b), {@code crypto_box}
 * (X25519 + HSalsa20 key derivation + XSalsa20-Poly1305), the Ed25519 to Curve25519 key
 * conversions, sealed boxes, and {@code crypto_pwhash} (Argon2). Secret keys use the libsodium
 * 64-byte layout (32-byte seed followed by the 32-byte public key).
 * <p>
 * <strong>Side-channels:</strong> implementations MUST compare secret material - private keys,
 * MAC tags and password hashes - in constant time (for example
 * {@code org.bouncycastle.util.Arrays.constantTimeAreEqual}). Public values such as public keys
 * and nonces may use ordinary equality.
 * <p>
 * <strong>Argument validation and errors:</strong> the public wrapper layer ({@link Signature},
 * {@link CryptoBox}, {@link PasswordHash}) validates every caller-supplied argument - null checks,
 * key/nonce/salt sizes and value ranges - before dispatching to this interface. Implementations may
 * therefore assume non-null, correctly-sized inputs and are not expected to re-check them.
 * Implementations also never throw a checked exception: an authentication or decryption failure is
 * reported by returning {@code null} (see {@link #boxDecrypt} and {@link #boxSealOpen}), which the
 * wrapper translates into a checked {@link CryptoException}. Keeping providers free of the Boson
 * exception hierarchy keeps a backend a pure cryptographic mechanism.
 */
public interface CryptoProvider {
	/** Length in bytes of an Ed25519 seed. */
	int SIGN_SEED_BYTES = 32;
	/** Length in bytes of an Ed25519 secret key (seed || public key). */
	int SIGN_SECRET_KEY_BYTES = 64;
	/** Length in bytes of an Ed25519 public key. */
	int SIGN_PUBLIC_KEY_BYTES = 32;
	/** Length in bytes of an Ed25519 signature. */
	int SIGN_BYTES = 64;
	/** Length in bytes of the {@code crypto_kdf} derivation context. */
	int KDF_CONTEXT_BYTES = 8;
	/** Length in bytes of a Curve25519 (crypto_box) seed. */
	int BOX_SEED_BYTES = 32;
	/** Length in bytes of a Curve25519 (crypto_box) public key. */
	int BOX_PUBLIC_KEY_BYTES = 32;
	/** Length in bytes of a Curve25519 (crypto_box) secret key. */
	int BOX_SECRET_KEY_BYTES = 32;
	/** Length in bytes of a precomputed crypto_box shared key. */
	int BOX_SHARED_KEY_BYTES = 32;
	/** Length in bytes of a crypto_box nonce. */
	int BOX_NONCE_BYTES = 24;
	/** Length in bytes of the crypto_box message authentication code. */
	int BOX_MAC_BYTES = 16;
	/** Length in bytes of a crypto_pwhash salt. */
	int PWHASH_SALT_BYTES = 16;

	/** Argon2i (version 1.3) algorithm id, matching {@code crypto_pwhash_ALG_ARGON2I13}. */
	int PWHASH_ALG_ARGON2I13 = 1;
	/** Argon2id (version 1.3) algorithm id, matching {@code crypto_pwhash_ALG_ARGON2ID13}. */
	int PWHASH_ALG_ARGON2ID13 = 2;

	/**
	 * A short, human-readable identifier for this provider (for example {@code "bc"} or
	 * {@code "libsodium"}).
	 *
	 * @return the provider name.
	 */
	String name();

	// ---- Ed25519 ----------------------------------------------------------

	/**
	 * Creates an Ed25519 secret key from a 32-byte seed (libsodium {@code crypto_sign_seed_keypair}).
	 *
	 * @param seed the {@value #SIGN_SEED_BYTES}-byte seed.
	 * @return the secret key.
	 */
	Signature.PrivateKey ed25519SecretKeyFromSeed(byte[] seed);

	/**
	 * Creates an Ed25519 secret key from its {@value #SIGN_SECRET_KEY_BYTES}-byte encoding
	 * (seed followed by public key).
	 *
	 * @param secretKey the {@value #SIGN_SECRET_KEY_BYTES}-byte secret key.
	 * @return the secret key.
	 */
	Signature.PrivateKey ed25519SecretKeyFromBytes(byte[] secretKey);

	/**
	 * Derives the Ed25519 public key for the given secret key.
	 *
	 * @param secretKey the secret key.
	 * @return the public key.
	 */
	Signature.PublicKey ed25519PublicKeyFromSecretKey(Signature.PrivateKey secretKey);

	/**
	 * Creates an Ed25519 public key from its {@value #SIGN_PUBLIC_KEY_BYTES}-byte encoding.
	 *
	 * @param bytes the {@value #SIGN_PUBLIC_KEY_BYTES}-byte public key.
	 * @return the public key.
	 */
	Signature.PublicKey ed25519PublicKeyFromBytes(byte[] bytes);

	/**
	 * Computes a detached Ed25519 signature.
	 *
	 * @param message   the message to sign.
	 * @param secretKey the secret key.
	 * @return the {@value #SIGN_BYTES}-byte signature.
	 */
	byte[] ed25519Sign(byte[] message, Signature.PrivateKey secretKey);

	/**
	 * Verifies a detached Ed25519 signature.
	 *
	 * @param message   the message.
	 * @param signature the {@value #SIGN_BYTES}-byte signature.
	 * @param publicKey the public key.
	 * @return true if the signature is valid.
	 */
	boolean ed25519Verify(byte[] message, byte[] signature, Signature.PublicKey publicKey);

	// ---- crypto_kdf (keyed BLAKE2b) ---------------------------------------

	/**
	 * Derives a sub-key from a master key using libsodium's {@code crypto_kdf} construction.
	 *
	 * @param masterKey     the 32-byte master key.
	 * @param subKeyId      the sub-key identifier.
	 * @param context       the {@value #KDF_CONTEXT_BYTES}-byte context.
	 * @param subKeyLength  the length of the derived sub-key.
	 * @return the derived sub-key.
	 */
	byte[] kdfDeriveFromKey(byte[] masterKey, long subKeyId, byte[] context, int subKeyLength);

	// ---- Ed25519 -> Curve25519 conversions --------------------------------

	/**
	 * Converts an Ed25519 public key to a Curve25519 (crypto_box) public key.
	 *
	 * @param publicKey the Ed25519 public key.
	 * @return the Curve25519 public key.
	 */
	CryptoBox.PublicKey signPublicKeyToBoxPublicKey(Signature.PublicKey publicKey);

	/**
	 * Converts an Ed25519 secret key to a Curve25519 (crypto_box) secret key.
	 *
	 * @param secretKey the Ed25519 secret key.
	 * @return the Curve25519 secret key.
	 */
	CryptoBox.PrivateKey signSecretKeyToBoxSecretKey(Signature.PrivateKey secretKey);

	// ---- crypto_box -------------------------------------------------------

	/**
	 * Creates a Curve25519 (crypto_box) secret key from a seed (libsodium
	 * {@code crypto_box_seed_keypair}).
	 *
	 * @param seed the {@value #BOX_SEED_BYTES}-byte seed.
	 * @return the secret key object.
	 */
	CryptoBox.PrivateKey boxSecretKeyFromSeed(byte[] seed);

	/**
	 * Creates a Curve25519 (crypto_box) public key from raw bytes.
	 *
	 * @param bytes the 32-byte public key.
	 * @return the public key object.
	 */
	CryptoBox.PublicKey boxPublicKeyFromBytes(byte[] bytes);

	/**
	 * Creates a Curve25519 (crypto_box) secret key from raw bytes.
	 *
	 * @param bytes the 32-byte secret key.
	 * @return the secret key object.
	 */
	CryptoBox.PrivateKey boxSecretKeyFromBytes(byte[] bytes);

	/**
	 * Derives the Curve25519 public key for a given Curve25519 secret key.
	 *
	 * @param secretKey the secret key.
	 * @return the public key.
	 */
	CryptoBox.PublicKey boxPublicKeyFromSecretKey(CryptoBox.PrivateKey secretKey);

	/**
	 * Creates a crypto_box nonce from its {@value #BOX_NONCE_BYTES}-byte value.
	 *
	 * @param bytes the {@value #BOX_NONCE_BYTES}-byte nonce.
	 * @return the nonce object.
	 */
	CryptoBox.Nonce boxNonceFromBytes(byte[] bytes);

	/**
	 * Precomputes the shared key for a sender/receiver key pair (libsodium {@code beforenm}),
	 * returning a {@link CryptoBox} whose {@link CryptoBox#encrypt}/{@link CryptoBox#decrypt}
	 * are the per-message {@code afternm} operations.
	 *
	 * @param publicKey the peer public key.
	 * @param secretKey the own secret key.
	 * @return the precomputed crypto box.
	 */
	CryptoBox boxBeforeNm(CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey);

	/**
	 * Encrypts a message with a precomputed shared key (libsodium {@code crypto_box_easy_afternm}).
	 * <p>
	 * Unlike a key object, a {@link CryptoBox} does not expose its shared key as bytes, so it cannot
	 * be reconstructed from a foreign instance: {@code box} must be one returned by this provider's
	 * {@link #boxBeforeNm}. Implementations should reject a box from another provider.
	 *
	 * @param message the plaintext.
	 * @param nonce   the {@value #BOX_NONCE_BYTES}-byte nonce.
	 * @param box     a precomputed crypto box created by this provider's {@link #boxBeforeNm}.
	 * @return the ciphertext (MAC prepended).
	 */
	byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox box);

	/**
	 * Decrypts a message with a precomputed shared key (libsodium {@code crypto_box_open_easy_afternm}).
	 * <p>
	 * As with {@link #boxEncrypt(byte[], CryptoBox.Nonce, CryptoBox)}, {@code box} must be one returned
	 * by this provider's {@link #boxBeforeNm}; implementations should reject a box from another provider.
	 *
	 * @param cipher the ciphertext.
	 * @param nonce  the {@value #BOX_NONCE_BYTES}-byte nonce.
	 * @param box    a precomputed crypto box created by this provider's {@link #boxBeforeNm}.
	 * @return the plaintext, or {@code null} if authentication failed.
	 */
	byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox box);

	/**
	 * Encrypts a message with explicit keys (libsodium {@code crypto_box_easy}).
	 *
	 * @param message   the plaintext.
	 * @param nonce     the {@value #BOX_NONCE_BYTES}-byte nonce.
	 * @param publicKey the receiver's public key.
	 * @param secretKey the sender's secret key.
	 * @return the ciphertext (MAC prepended).
	 */
	byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey);

	/**
	 * Decrypts a message with explicit keys (libsodium {@code crypto_box_open_easy}).
	 *
	 * @param cipher    the ciphertext.
	 * @param nonce     the {@value #BOX_NONCE_BYTES}-byte nonce.
	 * @param publicKey the sender's public key.
	 * @param secretKey the receiver's secret key.
	 * @return the plaintext, or {@code null} if authentication failed.
	 */
	byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey);

	/**
	 * Encrypts an anonymous sealed box for a recipient (libsodium {@code crypto_box_seal}).
	 *
	 * @param message   the plaintext.
	 * @param publicKey the recipient's public key.
	 * @return the sealed ciphertext (ephemeral public key prepended).
	 */
	byte[] boxSeal(byte[] message, CryptoBox.PublicKey publicKey);

	/**
	 * Opens an anonymous sealed box (libsodium {@code crypto_box_seal_open}).
	 *
	 * @param cipher    the sealed ciphertext.
	 * @param publicKey the recipient's public key.
	 * @param secretKey the recipient's secret key.
	 * @return the plaintext, or {@code null} if authentication failed.
	 */
	byte @Nullable [] boxSealOpen(byte[] cipher, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey);

	// ---- crypto_pwhash (Argon2) -------------------------------------------

	/**
	 * Derives a key from a password (libsodium {@code crypto_pwhash}).
	 *
	 * @param password  the password bytes.
	 * @param length    the derived key length.
	 * @param salt      the {@value #PWHASH_SALT_BYTES}-byte salt.
	 * @param opsLimit  the operations limit.
	 * @param memLimit  the memory limit in bytes.
	 * @param algorithm the algorithm id ({@link #PWHASH_ALG_ARGON2I13} or {@link #PWHASH_ALG_ARGON2ID13}).
	 * @return the derived key.
	 */
	byte[] pwHash(byte[] password, int length, byte[] salt, long opsLimit, long memLimit, int algorithm);

	/**
	 * Hashes a password into an encoded, self-describing PHC string (libsodium
	 * {@code crypto_pwhash_str}). The string embeds the algorithm, parameters and salt.
	 *
	 * @param password  the password bytes.
	 * @param opsLimit  the operations limit.
	 * @param memLimit  the memory limit in bytes.
	 * @param algorithm the algorithm id.
	 * @return the encoded PHC hash string.
	 */
	String pwHashString(byte[] password, long opsLimit, long memLimit, int algorithm);

	/**
	 * Verifies a password against an encoded PHC hash string (libsodium {@code crypto_pwhash_str_verify}).
	 *
	 * @param hash     the encoded PHC hash string.
	 * @param password the password bytes.
	 * @return true if the password matches.
	 */
	boolean pwHashVerify(String hash, byte[] password);

	/**
	 * Determines whether an encoded PHC hash string should be recomputed for the given limits
	 * (libsodium {@code crypto_pwhash_str_needs_rehash}).
	 *
	 * @param hash     the encoded PHC hash string.
	 * @param opsLimit the target operations limit.
	 * @param memLimit the target memory limit in bytes.
	 * @return true if the hash should be regenerated.
	 */
	boolean pwHashNeedsRehash(String hash, long opsLimit, long memLimit);

	// ---- PEM certification -------------------------------------------

	/**
	 * Generates a self-signed Ed25519 X.509 certificate and private key from a signature private key.
	 * <p>
	 * At least one Subject Alternative Name (SAN) entry must be produced: if both {@code ipAddress}
	 * and {@code hostName} are {@code null} the implementation throws {@link IllegalArgumentException}.
	 *
	 * @param privateKey     the signature private key
	 * @param ipAddress      the IP address to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit an IP SAN entry
	 * @param hostName       the host name to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit a DNS SAN entry
	 * @param enableWildcard whether to include a wildcard host name in the SAN
	 * @return a {@link PemCertificateAndKey} containing the PEM-encoded certificate and private key
	 * @throws CryptoException if an error occurs during key conversion or certificate generation
	 */
	PemCertificateAndKey certificateFromSignatureKey(Signature.PrivateKey privateKey, @Nullable String ipAddress,
	                                                 @Nullable String hostName, boolean enableWildcard) throws CryptoException;
}