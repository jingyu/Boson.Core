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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoException;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Hex;

/**
 * Represents a value in the Boson network. Values can be immutable, mutable or encrypted,
 * and they are identified by their public key or SHA-256 hash.
 */
public class Value {
	private final Id publicKey;
	private final byte[] privateKey;
	private final Id recipient;
	private final byte[] nonce;
	private final int sequenceNumber;
	private final byte[] signature;
	private final byte[] data;

	private transient Id id;

	/**
	 * Private constructor to create a new Value object.
	 *
	 * @param publicKey the public key associated with the value.
	 * @param privateKey the private key associated with the value.
	 * @param recipient  the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the sequence number of the value.
	 * @param signature the signature of the value.
	 * @param data the data of the value.
	 */
	private Value(Id publicKey, byte[] privateKey, Id recipient, byte[] nonce, int sequenceNumber, byte[] signature, byte[] data) {
		if (publicKey != null) {
			if (privateKey != null && privateKey.length != Signature.PrivateKey.BYTES)
				throw new IllegalArgumentException("Invalid private key");

			if (nonce == null || nonce.length != CryptoBox.Nonce.BYTES)
				throw new IllegalArgumentException("Invalid nonce");

			if (sequenceNumber < 0)
				throw new IllegalArgumentException("Invalid sequence number");

			if (signature == null || signature.length != Signature.BYTES)
				throw new IllegalArgumentException("Invalid signature");
		}

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		this.publicKey = publicKey;
		this.privateKey = privateKey;
		this.recipient = recipient;
		this.nonce = nonce;
		this.sequenceNumber = sequenceNumber;
		this.signature = signature;
		this.data = data;
	}

	/**
	 * Private constructor to create a new Value object.
	 *
	 * @param keypair the signing key pair associated with the value.
	 * @param recipient  the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the sequence number of the value.
	 * @param data the data of the value.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	private Value(Signature.KeyPair keypair, Id recipient, CryptoBox.Nonce nonce, int sequenceNumber, byte[] data) throws CryptoException {
		if (keypair == null)
			throw new IllegalArgumentException("Invalid keypair");

		if (nonce == null)
			throw new IllegalArgumentException("Invalid nonce");

		if (sequenceNumber < 0)
			throw new IllegalArgumentException("Invalid sequence number");

		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Invalid data");

		this.publicKey = new Id(keypair.publicKey().bytes());
		this.privateKey = keypair.privateKey().bytes();
		this.recipient = recipient;
		this.nonce = nonce.bytes();
		this.sequenceNumber = sequenceNumber;

		if (recipient != null) {
			CryptoBox.PublicKey recipientPk = recipient.toEncryptionKey();
			CryptoBox.PrivateKey ownerSk = CryptoBox.PrivateKey.fromSignatureKey(keypair.privateKey());

			this.data = CryptoBox.encrypt(data, recipientPk, ownerSk, nonce);
		} else {
			this.data = data;
		}

		this.signature = Signature.sign(getSignData(), keypair.privateKey());
	}

	/**
	 * Rebuilds a immutable {@code Value} object from the data.
	 *
	 * @param data the data of the value.
	 * @return a new immutable {@code Value} object.
	 */
	public static Value of(byte[] data) {
		return new Value(null, null, null, null, -1, null, data);
	}

	/**
	 * Rebuilds a mutable {@code Value} object from the data.
	 *
	 * @param publicKey the public key associated with the value.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the sequence number of the value.
	 * @param signature the signature of the value.
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 */
	public static Value of(Id publicKey, byte[] nonce, int sequenceNumber, byte[] signature, byte[] data) {
		return new Value(publicKey, null, null, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Rebuilds a encrypted {@code Value} object from the data.
	 *
	 * @param publicKey the public key associated with the value.
	 * @param recipient the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the sequence number of the value.
	 * @param signature the signature of the value.
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 */
	public static Value of(Id publicKey, Id recipient, byte[] nonce, int sequenceNumber,
			byte[] signature, byte[] data) {
		return new Value(publicKey, null, recipient, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Rebuilds a encrypted {@code Value} object from the data.
	 *
	 * @param publicKey the public key associated with the value.
	 * @param privateKey the private key associated with the value.
	 * @param recipient the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the sequence number of the value.
	 * @param signature the signature of the value.
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 */
	public static Value of(Id publicKey, byte[] privateKey, Id recipient, byte[] nonce,
			int sequenceNumber, byte[] signature, byte[] data) {
		return new Value(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
	}

	/**
	 * Creates a immutable {@code Value} object from the data.
	 *
	 * @param data the data of the value.
	 * @return a new immutable {@code Value} object.
	 */
	public static Value createValue(byte[] data) {
		return new Value(null, null, null, null, -1, null, data);
	}

	/**
	 * Creates a mutable {@code Value} object from the data. The new value will be signed
	 * by a new generated random key pair.
	 *
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createSignedValue(byte[] data) throws CryptoException {
		return createSignedValue(null, null, 0, data);
	}

	/**
	 * Creates a mutable {@code Value} object from the data with the given key pair and nonce.
	 *
	 * @param keypair the key pair to sign the {@code Value}
	 * @param nonce the nonce used for encryption or signing.
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createSignedValue(Signature.KeyPair keypair, CryptoBox.Nonce nonce,
			byte[] data) throws CryptoException {
		return createSignedValue(keypair, nonce, 0, data);
	}

	/**
	 * Creates a mutable {@code Value} object from the data with the given key pair and nonce.
	 *
	 * @param keypair the key pair to sign the {@code Value}
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the initial sequence number of the value.
	 * @param data the data of the value.
	 * @return a new mutable {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createSignedValue(Signature.KeyPair keypair, CryptoBox.Nonce nonce,
			int sequenceNumber, byte[] data) throws CryptoException {
		if (keypair == null)
			keypair = Signature.KeyPair.random();

		if (nonce == null)
			nonce = CryptoBox.Nonce.random();

		return new Value(keypair, null, nonce, sequenceNumber, data);
	}

	/**
	 * Creates a encrypted {@code Value} object from the data. The new value will be encrypted
	 * and signed by a new generated random key pair.
	 *
	 * @param recipient the recipient's ID if the value is encrypted.
	 * @param data the data of the value.
	 * @return a new encrypted {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createEncryptedValue(Id recipient, byte[] data) throws CryptoException {
		return createEncryptedValue(null, recipient, null, 0, data);
	}

	/**
	 * Creates a encrypted {@code Value} object from the data with the given key pair and nonce.
	 *
	 * @param keypair the key pair to sign the {@code Value}
	 * @param recipient the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param data the data of the value.
	 * @return a new encrypted {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createEncryptedValue(Signature.KeyPair keypair, Id recipient,
			CryptoBox.Nonce nonce, byte[] data) throws CryptoException {
		return createEncryptedValue(keypair, recipient, nonce, 0, data);
	}

	/**
	 * Creates a encrypted {@code Value} object from the data with the given key pair and nonce.
	 *
	 * @param keypair the key pair to sign the {@code Value}
	 * @param recipient the recipient's ID if the value is encrypted.
	 * @param nonce the nonce used for encryption or signing.
	 * @param sequenceNumber the initial sequence number of the value.
	 * @param data the data of the value.
	 * @return a new encrypted {@code Value} object.
	 * @throws CryptoException if a cryptographic error occurs during signing.
	 */
	public static Value createEncryptedValue(Signature.KeyPair keypair, Id recipient,
			CryptoBox.Nonce nonce, int sequenceNumber, byte[] data) throws CryptoException {
		if (recipient == null)
			throw new IllegalArgumentException("Invalid recipient");

		if (keypair == null)
			keypair = Signature.KeyPair.random();

		if (nonce == null)
			nonce = CryptoBox.Nonce.random();

		return new Value(keypair, recipient, nonce, sequenceNumber, data);
	}

	/**
	 * Gets the ID of the value.
	 *
	 * @return the ID of the value.
	 */
	public Id getId() {
		if (id == null)
			id = calculateId(this.publicKey, this.data);

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
	 * Checks if current node has the private key.
	 *
	 * @return true if the node has the private key, false otherwise.
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
	 * Calculates the ID of the value.
	 *
	 * @param publicKey The public key associated with the value.
	 * @param data      The data contained in the value.
	 * @return The calculated ID of the value.
	 */
	public static Id calculateId(Id publicKey, byte[] data) {
		if(publicKey != null) {
			return publicKey;
		} else {
			MessageDigest digest = Hash.sha256();
			digest.reset();
			return new Id(digest.digest(data));
		}
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

	private byte[] getSignData() {
		byte[] toSign = new byte[(recipient != null ? Id.BYTES : 0) +
				CryptoBox.Nonce.BYTES + Integer.BYTES + this.data.length];
		ByteBuffer buf = ByteBuffer.wrap(toSign);
		if (recipient != null)
			buf.put(recipient.bytes());
		buf.put(nonce);
		buf.putInt(sequenceNumber);
		buf.put(data);

		return toSign;
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
			if (nonce == null || nonce.length != CryptoBox.Nonce.BYTES)
				return false;

			if (signature == null || signature.length != Signature.BYTES)
				return false;

			Signature.PublicKey pk = publicKey.toSignatureKey();

			return Signature.verify(getSignData(), signature, pk);
		}

		return true;
	}

	/**
	 * Decrypts the data using the recipient's private key.
	 *
	 * @param recipientSk the recipient's private key.
	 * @return the decrypted data, or {@code null} if decryption fails.
	 * @throws CryptoException if a cryptographic error occurs during decryption.
	 */
	public byte[] decryptData(Signature.PrivateKey recipientSk) throws CryptoException {
		if (!isValid())
			return null;

		if (recipient == null)
			return null;

		CryptoBox.PublicKey pk = publicKey.toEncryptionKey();
		CryptoBox.PrivateKey sk = CryptoBox.PrivateKey.fromSignatureKey(recipientSk);

		return CryptoBox.decrypt(data, pk, sk, CryptoBox.Nonce.fromBytes(nonce));
	}

	/**
	 * Updates the value with new data, incrementing the sequence number.
	 *
	 * @param data the new data to be included in the updated value.
	 * @return the updated Value object.
	 * @throws CryptoException if a cryptographic error occurs during the update.
	 */
	public Value update(byte[] data) throws CryptoException {
		if (!isMutable())
			throw new IllegalStateException("Immutable value " + getId());

		if (!hasPrivateKey())
			throw new IllegalStateException("Not the owner of the value " + getId());

		Signature.KeyPair kp = Signature.KeyPair.fromPrivateKey(getPrivateKey());
		CryptoBox.Nonce nonce = CryptoBox.Nonce.random();


		return new Value(kp, recipient, nonce, sequenceNumber + 1, data);
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof Value) {
			Value v = (Value)o;

			return sequenceNumber == v.sequenceNumber &&
					Objects.equals(publicKey, v.publicKey) &&
					Objects.equals(recipient, v.recipient) &&
					Arrays.equals(nonce, v.nonce) &&
					Arrays.equals(signature, v.signature) &&
					Arrays.equals(data, v.data);
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

		if (nonce != null)
			repr.append(",nonce: ").append(Hex.encode(nonce));

		if (publicKey != null)
			repr.append(",seq:").append(sequenceNumber);

		if (signature != null)
			repr.append(",sig:").append(Hex.encode(signature));

		repr.append(",data:").append(Hex.encode(data));

		return repr.toString();
	}
}
