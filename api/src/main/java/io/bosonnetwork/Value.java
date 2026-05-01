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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Hex;

/**
 * Represents a value stored and propagated in the Boson network.
 * <p>
 * A {@code Value} can be one of three types:
 * <ul>
 *     <li><b>Immutable</b>: Identified by the SHA-256 hash of its data. Content cannot be changed.</li>
 *     <li><b>Mutable (Signed)</b>: Identified by a public key. The owner can update the value by
 *         increasing the sequence number and providing a valid signature.</li>
 *     <li><b>Encrypted</b>: A mutable value whose payload is encrypted for a specific recipient.
 *         The encrypted payload is still signed by the owner.</li>
 * </ul>
 * <p>
 * For mutable values, the {@code id} represents the logical record (public key), while
 * the sequence number distinguishes different versions.
 */
public class Value {
	/** The number of bytes in the nonce. */
	public static int NONCE_BYTES = 24;

	/** The public key for mutable values. */
	private final Id publicKey;
	/** The private key to sign or update the value. */
	private final byte[] privateKey;
	/** The recipient's public key for encrypted values. */
	private final Id recipient;
	/** The nonce for mutable or encrypted values. */
	private final byte[] nonce;
	/** The sequence number for mutable values. */
	private final int sequenceNumber;
	/** The signature for mutable values. */
	private final byte[] signature;
	/** The data of the value. */
	private final byte[] data;

	/**
	 * The unique ID of the value.
	 * <p>
	 * For immutable values, the ID is the SHA-256 hash of the data.
	 * For mutable values, the ID is the public key and identifies the logical record.
	 * Multiple versions (sequence numbers) share the same ID.
	 * */
	private final transient Id id;

	private Value(Id publicKey, byte[] privateKey, Id recipient, byte[] nonce, int sequenceNumber, byte[] signature, byte[] data) {
		this.publicKey = publicKey;
		this.privateKey = privateKey;
		this.recipient = recipient;
		this.nonce = nonce;
		this.sequenceNumber = sequenceNumber;
		this.signature = signature;
		this.data = data;

		this.id = calculateId(publicKey, data);
	}

	private Value(Id id, byte[] data) {
		this.id = id;
		this.publicKey = null;
		this.privateKey = null;
		this.recipient = null;
		this.nonce = null;
		this.sequenceNumber = 0;
		this.signature = null;
		this.data = data;
	}

	/**
	 * Creates a new Value instance from existing information.
	 *
	 * @param publicKey      The public key for mutable values (optional).
	 * @param privateKey     The private key (optional).
	 * @param recipient      The recipient's public key for encrypted values (optional).
	 * @param nonce          The nonce.
	 * @param sequenceNumber The sequence number.
	 * @param signature      The signature.
	 * @param data           The data.
	 * @return The new Value instance.
	 * @throws IllegalArgumentException if parameters are invalid.
	 */
	public static Value of(Id publicKey, byte[] privateKey, Id recipient, byte[] nonce, int sequenceNumber,
						   byte[] signature, byte[] data) {
		if (publicKey != null) {
			// noinspection DuplicatedCode
			if (privateKey != null && privateKey.length != Signature.PrivateKey.BYTES)
				throw new IllegalArgumentException("Invalid private key: incorrect length");

			if (nonce == null || nonce.length != NONCE_BYTES)
				throw new IllegalArgumentException("Invalid nonce: must be exactly NONCE_BYTES (24 bytes)");

			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number: must be non-negative");

			if (signature == null || signature.length != Signature.BYTES)
				throw new IllegalArgumentException("Invalid signature: incorrect length");
		}

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data: must not be null or empty");

		return new Value(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a new Value instance from existing information.
	 *
	 * @param publicKey      The public key for mutable values.
	 * @param recipient      The recipient's public key (optional).
	 * @param nonce          The nonce.
	 * @param sequenceNumber The sequence number.
	 * @param signature      The signature.
	 * @param data           The data.
	 * @return The new Value instance.
	 */
	public static Value of(Id publicKey, Id recipient, byte[] nonce, int sequenceNumber, byte[] signature, byte[] data) {
		return of(publicKey, null, recipient, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a new mutable Value instance from existing information.
	 *
	 * @param publicKey      The public key.
	 * @param nonce          The nonce.
	 * @param sequenceNumber The sequence number.
	 * @param signature      The signature.
	 * @param data           The data.
	 * @return The new Value instance.
	 */
	public static Value of(Id publicKey, byte[] nonce, int sequenceNumber, byte[] signature, byte[] data) {
		return of(publicKey, null, null, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a new immutable Value instance from an ID and data.
	 *
	 * @param id   The ID of the value.
	 * @param data The data.
	 * @return The new Value instance.
	 */
	public static Value of(Id id, byte[] data) {
		return new Value(id, data);
	}

	/**
	 * Creates an immutable {@code Value} object from the data.
	 *
	 * @param data the data of the value.
	 * @return a new immutable {@code Value} object.
	 */
	private static Value create(byte[] data) {
		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		return new Value(null, null, null, null, 0, null, data);
	}

	/**
	 * Creates a mutable {@code Value} object from the data.
	 *
	 * @param identity       The {@code Identity} object containing cryptographic methods, including signing.
	 * @param privateKey     The private key associated with the value. Optional.
	 * @param sequenceNumber The sequence number for the value. Must be non-negative.
	 * @param data           The data to be included in the value. Cannot be null or empty.
	 * @return A new signed {@code Value} instance containing the specified data, nonce, sequence number, and signature.
	 * @throws IllegalArgumentException if the sequence number is negative, or the data is null/empty.
	 */
	private static Value createSigned(Identity identity, byte[] privateKey, int sequenceNumber, byte[] data) {
		// noinspection DuplicatedCode
		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number: must be non-negative");

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data: must not be null or empty");

		byte[] nonce = new byte[NONCE_BYTES];
		Random.secureRandom().nextBytes(nonce);

		Id publicKey = identity.getId();
		byte[] digest = computeDigest(publicKey, null, nonce, sequenceNumber, data);
		byte[] signature = identity.sign(digest);

		return new Value(publicKey, privateKey, null, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a new mutable Value object from the data, encrypted for a specific recipient.
	 *
	 * @param identity       The owner's identity.
	 * @param privateKey   	 The owner's private key. Optional.
	 * @param recipient      The recipient's ID.
	 * @param sequenceNumber The sequence number.
	 * @param data           The data to encrypt.
	 * @return The new encrypted Value instance.
	 * @throws IllegalArgumentException if parameters are invalid.
	 */
	private static Value createEncrypted(Identity identity, byte[] privateKey, Id recipient, int sequenceNumber, byte[] data) {
		if (recipient == null)
			throw new IllegalArgumentException("Invalid recipient: recipient id must not be null");

		// noinspection DuplicatedCode
		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number: must be non-negative");

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data: must not be null or empty");

		byte[] nonce = new byte[NONCE_BYTES];
		Random.secureRandom().nextBytes(nonce);

		byte[] encryptData;
		try {
			encryptData = identity.encrypt(recipient, nonce, data);
		} catch (Exception e) {
			// only will error on the recipient id is an invalid ED25519 public key
			throw new IllegalArgumentException("Invalid recipient id: encryption failed", e);
		}

		Id publicKey = identity.getId();
		byte[] digest = computeDigest(publicKey, recipient, nonce, sequenceNumber, encryptData);
		byte[] signature = identity.sign(digest);

		return new Value(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, encryptData);
	}

	/**
	 * Creates a new immutable Value builder.
	 *
	 * @return a new immutable Value builder
	 */
	public static Builder immutableBuilder() {
		return new Builder(Builder.Type.IMMUTABLE);
	}

	/**
	 * Creates a new signed Value builder.
	 *
	 * @return a new signed Value builder
	 */
	public static Builder signedBuilder() {
		return new Builder(Builder.Type.SIGNED);
	}

	/**
	 * Creates a new encrypted Value builder.
	 *
	 * @return a new encrypted Value builder
	 */
	public static Builder encryptedBuilder() {
		return new Builder(Builder.Type.ENCRYPTED);
	}

	/**
	 * Gets the ID of the value.
	 *
	 * @return the ID of the value.
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Gets the associated public key of the value.
	 *
	 * @return the public key of the value, or null for immutable values.
	 */
	public Id getPublicKey() {
		return publicKey;
	}

	/**
	 * Checks if the current node has the value's private key.
	 *
	 * @return {@code true} if the node has the private key, {@code false} otherwise.
	 */
	public boolean hasPrivateKey() {
		return privateKey != null;
	}

	/**
	 * Gets the associated private key of the value.
	 *
	 * @return the private key of the value, or null if the node does not have the private key.
	 */
	public byte[] getPrivateKey() {
		return privateKey;
	}

	/**
	 * Gets the recipient of the value.
	 *
	 * @return the recipient of the value, or null if no recipient.
	 */
	public Id getRecipient() {
		return recipient;
	}

	/**
	 * Gets the associated nonce of the value.
	 *
	 * @return the nonce of the value, or null for immutable values.
	 */
	public byte[] getNonce() {
		return nonce;
	}

	/**
	 * Gets the sequence number of the value.
	 *
	 * @return the sequence number of the value.
	 */
	public int getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * Gets the signature of the value.
	 *
	 * @return the signature of the value, or null for immutable values.
	 */
	public byte[] getSignature() {
		return signature;
	}

	/**
	 * Gets the data of the value.
	 *
	 * @return the data of the value.
	 */
	public byte[] getData() {
		return data;
	}

	/**
	 * Decrypts the value's data for the recipient.
	 *
	 * @param recipientSk The recipient's private key.
	 * @return the decrypted data
	 * @throws CryptoException               if decryption fails.
	 * @throws UnsupportedOperationException if the value is not encrypted.
	 * @throws IllegalArgumentException      if the recipient key is invalid.
	 * @throws IllegalStateException         if the value is not valid.
	 */
	public byte[] decryptData(Signature.PrivateKey recipientSk) throws CryptoException {
		if (recipient == null)
			throw new UnsupportedOperationException("Cannot decrypt: value is not encrypted");

		Objects.requireNonNull(recipientSk, "Recipient private key must not be null");

		if (!isValid())
			throw new IllegalStateException("Cannot decrypt: value failed validation");

		return decryptData(new CryptoIdentity(recipientSk.bytes()));
	}

	/**
	 * Decrypts the data using the recipient's private key.
	 *
	 * @param recipientSk the private key of the recipient, represented as a byte array.
	 *                    Must not be null and must have a length equal to {@code Signature.PrivateKey.BYTES}.
	 * @return the decrypted data as a byte array.
	 * @throws CryptoException if there is an error during the decryption process.
	 * @throws UnsupportedOperationException if the value is not encrypted.
	 * @throws IllegalArgumentException if the provided private key is invalid.
	 * @throws IllegalStateException if the value is not in a valid state for decryption.
	 */
	public byte[] decryptData(byte[] recipientSk) throws CryptoException {
		if (recipient == null)
			throw new UnsupportedOperationException("Cannot decrypt: value is not encrypted");

		Objects.requireNonNull(recipientSk, "Recipient private key must not be null");
		if (recipientSk.length != Signature.PrivateKey.BYTES)
			throw new IllegalArgumentException("Invalid recipient private key: incorrect length");

		if (!isValid())
			throw new IllegalStateException("Cannot decrypt: value failed validation");

		return decryptData(new CryptoIdentity(recipientSk));
	}

	/**
	 * Decrypts the encrypted data using the recipient's identity.
	 *
	 * @param recipientIdentity the identity of the recipient that contains the private key
	 *                          used to decrypt the data. Must not be null and must match the
	 *                          public key of the intended recipient.
	 * @return a byte array containing the decrypted data.
	 * @throws CryptoException if an error occurs during the decryption process.
	 * @throws UnsupportedOperationException if the data is not encrypted.
	 * @throws NullPointerException if the recipientIdentity is null.
	 * @throws IllegalStateException if the value is not valid or ready for decryption.
	 * @throws IllegalArgumentException if the provided recipientIdentity does not match the
	 *                                  recipient's public key.
	 */
	public byte[] decryptData(Identity recipientIdentity) throws CryptoException {
		if (recipient == null)
			throw new UnsupportedOperationException("Cannot decrypt: value is not encrypted");

		Objects.requireNonNull(recipientIdentity, "recipientIdentity cannot be null");

		if (!isValid())
			throw new IllegalStateException("Cannot decrypt: value failed validation");

		if (!recipientIdentity.getId().equals(recipient))
			throw new IllegalArgumentException("Recipient identity does not match value recipient");

		return recipientIdentity.decrypt(publicKey, nonce, data);
	}

	/**
	 * Calculates the ID of the value.
	 *
	 * @param publicKey The public key associated with the value.
	 * @param data      The data contained in the value.
	 * @return The calculated ID of the value.
	 */
	private static Id calculateId(Id publicKey, byte[] data) {
		return publicKey != null ? publicKey : new Id(Hash.sha256(data));
	}

	/**
	 * Check if the value is immutable.
	 *
	 * @return true if the object is immutable (when the publicKey is null), false otherwise.
	 */
	public boolean isImmutable() {
		return publicKey == null;
	}

	/**
	 * Checks if the value is mutable.
	 *
	 * @return {@code true} if the value is mutable, {@code false} otherwise.
	 */
	public boolean isMutable() {
		return publicKey != null;
	}

	/**
	 * Checks if the value is encrypted.
	 *
	 * @return {@code true} if the value is encrypted, {@code false} otherwise.
	 */
	public boolean isEncrypted() {
		return recipient != null;
	}

	/**
	 * Computes the digest for signing the value.
	 *
	 * @return the digest
	 */
	private static byte[] computeDigest(Id publicKey, Id recipient, byte[] nonce, int sequenceNumber, byte[] data) {
		MessageDigest sha = Hash.sha256();
		if (publicKey != null) {
			sha.update(publicKey.bytes());
			if (recipient != null)
				sha.update(recipient.bytes());
			sha.update(nonce);
			sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(sequenceNumber).array());
		}
		sha.update(data);
		return sha.digest();
	}

	/**
	 * Validates structural integrity and cryptographic correctness of this value.
	 *
	 * <p>For mutable values, this verifies the signature against the computed digest.
	 * For immutable values, this verifies that the id matches the hash of the data.
	 *
	 * @return {@code true} if the value is valid, {@code false} otherwise.
	 */
	public boolean isValid() {
		if (data == null || data.length == 0)
			return false;

		if (isMutable()) {
			if (signature == null || signature.length != Signature.BYTES)
				return false;

			if (nonce == null || nonce.length != NONCE_BYTES)
				return false;

			if (sequenceNumber < 0)
				return false;

			byte[] digest = computeDigest(publicKey, recipient, nonce, sequenceNumber, data);
			Signature.PublicKey pk = publicKey.toSignatureKey();
			return Signature.verify(digest, signature, pk);
		} else {
			if (id == null || publicKey != null || recipient != null || nonce != null ||
					sequenceNumber < 0 || signature != null)
				return false;

			return id.equals(calculateId(null, data));
		}
	}

	/**
	 * Updates the current state of the Value object by creating a new instance of the Builder
	 * initialized with the current state.
	 *
	 * @return a new Builder instance containing the current state of the Value object.
	 */
	public Builder update() {
		if (isImmutable())
			throw new UnsupportedOperationException("Cannot update immutable value");

		if (!isValid())
			throw new IllegalStateException("Cannot update: value failed validation");

		return new Builder(this);
	}

	/**
	 * Returns a new Value instance without the private key, or the current instance if the private key is already null.
	 * <p>
	 * This method checks if the private key is null. If it is, the current instance is returned.
	 * Otherwise, a new Value instance is created and returned with the same properties as the current instance, excluding the private key.
	 *
	 * @return the current Value instance if the private key is null, or a new Value instance without the private key.
	 */
	public Value withoutPrivateKey() {
		if (privateKey == null)
			return this;

		return new Value(publicKey, null, recipient, nonce, sequenceNumber, signature, data);
	}

	// id is excluded from hash code and equality as it is a derived field from data or publicKey
	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(publicKey, recipient, Arrays.hashCode(nonce),
				sequenceNumber, Arrays.hashCode(signature), Arrays.hashCode(data));
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof Value that) {
			return this.sequenceNumber == that.sequenceNumber &&
					Objects.equals(this.publicKey, that.publicKey) &&
					Objects.equals(this.recipient, that.recipient) &&
					Arrays.equals(this.nonce, that.nonce) &&
					Arrays.equals(this.signature, that.signature) &&
					Arrays.equals(this.data, that.data);
		}

		return false;
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();
		repr.append("id:").append(getId());

		if (publicKey != null)
			repr.append(",publicKey:").append(publicKey);

		if (recipient != null)
			repr.append(",recipient:").append(recipient);

		if (publicKey != null)
			repr.append(",seq:").append(sequenceNumber);

		if (signature != null)
			repr.append(",sig:").append(Hex.encode(signature));

		repr.append(",data:").append(Hex.encode(data));

		return repr.toString();
	}

	/**
	 * Value builder.
	 */
	public static class Builder {
		private enum Type { IMMUTABLE, SIGNED, ENCRYPTED }

		private final Value forUpdate;
		private final Type type;

		private Identity identity = null;
		private boolean keepPrivateKey;
		Id recipient = null;
		private int sequenceNumber = 0;
		private byte[] data = null;

		private Builder(Type type) {
			this.type = type;
			this.forUpdate = null;
		}

		private Builder(Value value) {
			this.forUpdate = value;
			this.type = value.isImmutable() ? Type.IMMUTABLE :
					(value.isEncrypted() ? Type.ENCRYPTED : Type.SIGNED);

			this.identity = value.hasPrivateKey() ? new CryptoIdentity(value.getPrivateKey()) : null;
			this.keepPrivateKey = value.hasPrivateKey();
			this.recipient = value.getRecipient();
			this.sequenceNumber = value.getSequenceNumber() + 1;
			this.data = value.getData();
		}

		private boolean isUpdate() {
			return forUpdate != null;
		}

		/**
		 * Sets the data for the value.
		 *
		 * @param value the data
		 * @return the builder instance
		 * @throws IllegalArgumentException if the data is null or empty
		 */
		public Builder data(byte[] value) {
			if (value == null || value.length == 0)
				throw new IllegalArgumentException("Data must not be null or empty");
			this.data = value;
			return this;
		}

		/**
		 * Sets the data for the value from a string.
		 *
		 * @param value the string data
		 * @return the builder instance
		 */
		public Builder data(String value) {
			return data(value.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Sets the recipient for the encrypted value.
		 *
		 * @param recipient the recipient's public key
		 * @return the builder instance
		 */
		public Builder recipient(Id recipient) {
			if (type != Type.ENCRYPTED)
				throw new UnsupportedOperationException("Recipient can only be set for encrypted values");

			if (isUpdate())
				throw new UnsupportedOperationException("Recipient cannot be changed during update");

			this.recipient = recipient;
			return this;
		}

		/**
		 * Sets the sequence number for the mutable value.
		 *
		 * @param sequenceNumber the sequence number
		 * @return the builder instance
		 * @throws IllegalArgumentException if the sequence number is negative
		 */
		public Builder sequenceNumber(int sequenceNumber) {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Sequence number is only applicable to mutable values");

			if (isUpdate())
				throw new UnsupportedOperationException("Sequence number is managed automatically during update");

			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number: must be non-negative");
			this.sequenceNumber = sequenceNumber;
			return this;
		}

		/**
		 * Sets the keypair for the mutable/encrypted value.
		 *
		 * @param keyPair the keypair
		 * @return the builder instance
		 * @throws NullPointerException if the keyPair is null
		 */
		public Builder key(Signature.KeyPair keyPair) {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Keys are only applicable to mutable/encrypted values");

			Objects.requireNonNull(keyPair);
			identity(new CryptoIdentity(keyPair));
			return this;
		}

		/**
		 * Sets the private key for the mutable/encrypted value.
		 *
		 * @param privateKey the private key
		 * @return the builder instance
		 * @throws NullPointerException if the private key is null
		 */
		public Builder key(Signature.PrivateKey privateKey) {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Keys are only applicable to mutable/encrypted values");

			Objects.requireNonNull(privateKey);
			key(Signature.KeyPair.fromPrivateKey(privateKey));
			return this;
		}

		/**
		 * Sets the private key for the mutable/encrypted value from bytes.
		 *
		 * @param privateKey the private key bytes
		 * @return the builder instance
		 * @throws NullPointerException if the private key is null
		 * @throws IllegalArgumentException if the length is invalid
		 */
		public Builder key(byte[] privateKey) {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Keys are only applicable to mutable/encrypted values");

			Objects.requireNonNull(privateKey);
			if (privateKey.length != Signature.PrivateKey.BYTES)
				throw new IllegalArgumentException("Invalid private key: incorrect length");
			key(Signature.KeyPair.fromPrivateKey(privateKey));
			return this;
		}

		/**
		 * Sets the identity for the mutable/encrypted value.
		 *
		 * @param identity the identity to be set; must not be {@code null}
		 * @return the builder instance
		 * @throws NullPointerException if the identity is {@code null}
		 */
		public Builder identity(Identity identity) {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Identity is only applicable to mutable/encrypted values");

			Objects.requireNonNull(identity);

			if (isUpdate()) {
				if (!forUpdate.isMutable())
					throw new UnsupportedOperationException("Identity is only applicable to mutable/encrypted values");

				if (!identity.getId().equals(forUpdate.publicKey))
					throw new IllegalArgumentException("Identity does not match the value's public key");
			}

			this.identity = identity;
			return this;
		}

		/**
		 * Configures that the private key is retained in the built Value instance.
		 *
		 * @return the builder instance
		 */
		public Builder keepPrivateKey() {
			if (type == Type.IMMUTABLE)
				throw new UnsupportedOperationException("Immutable values do not have private keys");

			this.keepPrivateKey = true;
			return this;
		}

		/**
		 * Constructs and returns a new `Value` object based on the current state and type.
		 * The method performs several validations and operations depending on the type of the value.
		 *
		 * @return A `Value` object constructed according to the specifications and type of the current state.
		 * @throws IllegalStateException If mandatory fields (`data`, `identity`, or `recipient`) are missing,
		 *                                or if conditions for specific types are not satisfied.
		 */
		public Value build() {
			if (data == null)
				throw new IllegalStateException("Missing required field: data");

			if (type == Type.SIGNED || type == Type.ENCRYPTED) {
				if (identity == null) {
					if (isUpdate()) {
						throw new IllegalStateException("Missing identity: required for updating an existing value");
					} else {
						identity = new CryptoIdentity();
						keepPrivateKey = true;
					}
				}
			}

			if (type == Type.ENCRYPTED) {
				if (recipient == null)
					throw new IllegalStateException("Missing recipient: required for encrypted value");
			}

			byte[] privateKey = null;
			if (keepPrivateKey) {
				if (identity instanceof CryptoIdentity cid)
					privateKey = cid.getKeyPair().privateKey().bytes();
				else
					throw new IllegalStateException("Unable to extract private key from identity");
			}

			return switch (type) {
				case IMMUTABLE -> create(data);
				case SIGNED -> createSigned(identity, privateKey, sequenceNumber, data);
				case ENCRYPTED -> createEncrypted(identity, privateKey, recipient, sequenceNumber, data);
			};
		}
	}
}