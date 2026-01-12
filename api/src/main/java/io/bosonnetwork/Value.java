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

import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Hex;

/**
 * Represents a value in the Boson network.
 *
 * <p>Values can be of three types:
 * <ul>
 *     <li>Immutable: Identified by the SHA-256 hash of their data.</li>
 *     <li>Mutable: Identified by a public key and can be updated by the owner.</li>
 *     <li>Encrypted: A mutable value whose data is encrypted for a specific recipient.</li>
 * </ul>
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

	/** The unique ID of the value. */
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
				throw new IllegalArgumentException("Invalid private key");

			if (nonce == null || nonce.length != NONCE_BYTES)
				throw new IllegalArgumentException("Invalid nonce");

			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number");

			if (signature == null || signature.length != Signature.BYTES)
				throw new IllegalArgumentException("Invalid signature");
		}

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

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
	 * Creates a mutable {@code Value} object from the data. The new value will be signed
	 * by a new generated random key pair.
	 *
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 */
	private static Value createSigned(Signature.KeyPair keypair, int sequenceNumber, byte[] data) {
		// noinspection DuplicatedCode
		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number");

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		if (keypair == null)
			keypair = Signature.KeyPair.random();

		byte[] nonce = new byte[NONCE_BYTES];
		Random.secureRandom().nextBytes(nonce);

		Id publicKey = Id.of(keypair.publicKey().bytes());
		byte[] digest = new Value(publicKey, null, null, nonce, sequenceNumber, null, data).digest();
		byte[] signature = Signature.sign(digest, keypair.privateKey());

		return new Value(publicKey, keypair.privateKey().bytes(), null, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a new mutable Value object from the data, encrypted for a specific recipient.
	 *
	 * @param keypair        The owner's keypair.
	 * @param recipient      The recipient's ID.
	 * @param sequenceNumber The sequence number.
	 * @param data           The data to encrypt.
	 * @return The new encrypted Value instance.
	 * @throws IllegalArgumentException if parameters are invalid.
	 */
	private static Value createEncrypted(Signature.KeyPair keypair, Id recipient, int sequenceNumber, byte[] data) {
		if (recipient == null)
			throw new IllegalArgumentException("Invalid recipient");

		// noinspection DuplicatedCode
		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number");

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		if (keypair == null)
			keypair = Signature.KeyPair.random();

		byte[] nonce = new byte[NONCE_BYTES];
		Random.secureRandom().nextBytes(nonce);

		byte[] encryptData;
		try {
			CryptoBox.PublicKey recipientPk = recipient.toEncryptionKey();
			CryptoBox.PrivateKey ownerSk = CryptoBox.PrivateKey.fromSignatureKey(keypair.privateKey());
			encryptData = CryptoBox.encrypt(data, recipientPk, ownerSk, CryptoBox.Nonce.fromBytes(nonce));
		} catch (Exception e) {
			// only will error on the recipient id is an invalid ED25519 public key
			throw new IllegalArgumentException("Invalid recipient Id", e);
		}

		Id publicKey = Id.of(keypair.publicKey().bytes());
		byte[] digest = new Value(publicKey, null, recipient, nonce, sequenceNumber, null, encryptData).digest();
		byte[] signature = Signature.sign(digest, keypair.privateKey());

		return new Value(publicKey, keypair.privateKey().bytes(), recipient, nonce, sequenceNumber, signature, encryptData);
	}

	/**
	 * Creates a new Value builder.
	 *
	 * @return a new Value builder
	 */
	public static Builder builder() {
		return new Builder();
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
			throw new UnsupportedOperationException("Value is not encrypted");

		if (recipientSk == null)
			throw new IllegalArgumentException("Invalid recipient private key");

		if (!isValid())
			throw new IllegalStateException("Value is not valid");

		Signature.KeyPair recipientKeypair = Signature.KeyPair.fromPrivateKey(recipientSk);
		if (!Arrays.equals(recipientKeypair.publicKey().bytes(), recipient.bytes()))
			throw new IllegalArgumentException("Invalid recipient private key: not matching recipient public key");

		CryptoBox.PublicKey pk = publicKey.toEncryptionKey();
		CryptoBox.PrivateKey sk = CryptoBox.PrivateKey.fromSignatureKey(recipientSk);

		return CryptoBox.decrypt(data, pk, sk, CryptoBox.Nonce.fromBytes(nonce));
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
	private byte[] digest() {
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
	 * Checks if the value is valid, including checks for data integrity and
	 * signature verification.
	 *
	 * @return {@code true} if the value is valid, {@code false} otherwise.
	 */
	public boolean isValid() {
		if (data == null || data.length == 0)
			return false;

		if (isMutable()) {
			if (signature == null || signature.length != Signature.BYTES)
				return false;

			if (nonce == null || nonce.length != CryptoBox.Nonce.BYTES)
				return false;

			if (sequenceNumber < 0)
				return false;

			Signature.PublicKey pk = publicKey.toSignatureKey();
			return Signature.verify(digest(), signature, pk);
		} else {
			if (id == null || recipient != null || nonce != null || sequenceNumber < 0)
				return false;

			return id.equals(calculateId(null, data));
		}
	}


	/**
	 * Updates the value with new data, incrementing the sequence number.
	 *
	 * @param data the new data to be included in the updated value.
	 * @return the updated Value object.
	 */
	public Value update(byte[] data) {
		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		if (!isMutable())
			throw new UnsupportedOperationException("Immutable value");

		if (!hasPrivateKey())
			throw new UnsupportedOperationException("Not the owner of the value");

		if (!isEncrypted() && Arrays.equals(this.data, data))
			return this; // no need to update

		Signature.KeyPair keypair = Signature.KeyPair.fromPrivateKey(getPrivateKey());
		if (isEncrypted())
			return createEncrypted(keypair, recipient, sequenceNumber + 1, data);
		else
			return createSigned(keypair, sequenceNumber + 1, data);
	}

	/**
	 * Returns a new Value instance without the private key, or the current instance if the private key is already null.
	 *
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
		private Signature.KeyPair keyPair = null;
		Id recipient = null;
		private int sequenceNumber = 0;
		private byte[] data = null;

		private Builder() {
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
				throw new IllegalArgumentException("value cannot be null or empty");
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
			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number");
			this.sequenceNumber = sequenceNumber;
			return this;
		}

		/**
		 * Sets the keypair for the mutable/encrypted value.
		 *
		 * @param keyPair the keypair
		 * @return the builder instance
		 */
		public Builder key(Signature.KeyPair keyPair) {
			this.keyPair = keyPair;
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
			Objects.requireNonNull(privateKey);
			this.keyPair = Signature.KeyPair.fromPrivateKey(privateKey);
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
			Objects.requireNonNull(privateKey);
			if (privateKey.length != Signature.PrivateKey.BYTES)
				throw new IllegalArgumentException("Invalid private key");
			this.keyPair = Signature.KeyPair.fromPrivateKey(privateKey);
			return this;
		}

		/**
		 * Builds an immutable Value instance.
		 *
		 * @return the new immutable Value instance
		 * @throws IllegalStateException if the data is missing
		 */
		public Value build() {
			if (data == null)
				throw new IllegalStateException("Value data cannot be null");

			return create(data);
		}

		/**
		 * Builds a signed mutable Value instance.
		 *
		 * @return the new mutable Value instance
		 * @throws IllegalStateException if data is missing
		 */
		public Value buildSigned() {
			if (data == null)
				throw new IllegalStateException("Value data cannot be null");

			if (keyPair == null)
				keyPair = Signature.KeyPair.random();

			return createSigned(keyPair, sequenceNumber, data);
		}

		/**
		 * Builds an encrypted mutable Value instance.
		 *
		 * @return the new encrypted Value instance
		 * @throws IllegalStateException if data or recipient is missing
		 */
		public Value buildEncrypted() {
			if (data == null)
				throw new IllegalStateException("Value data cannot be null");
			if (recipient == null)
				throw new IllegalStateException("Value recipient cannot be null");

			if (keyPair == null)
				keyPair = Signature.KeyPair.random();

			return createEncrypted(keyPair, recipient, sequenceNumber, data);
		}

	}
}