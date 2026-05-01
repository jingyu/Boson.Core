package io.bosonnetwork.crypto;

import java.util.Arrays;
import java.util.Objects;

import org.apache.tuweni.crypto.sodium.SodiumException;

import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Implements the {@link Identity} interface using cryptographic key pairs.
 * Provides functionality for signing, verifying, encrypting, and decrypting data
 * using signature and encryption key pairs.
 */
public class CryptoIdentity implements Identity {
	private final Id id;
	private final Signature.KeyPair keyPair;
	private final CryptoBox.KeyPair encryptionKeyPair;

	/**
	 * Constructs a new {@code CryptoIdentity} with a randomly generated signature key pair.
	 */
	public CryptoIdentity() {
		this(Signature.KeyPair.random());
	}

	/**
	 * Constructs a new {@code CryptoIdentity} from the given private key bytes.
	 *
	 * @param privateKey the private key bytes used to create the signature key pair
	 */
	public CryptoIdentity(byte[] privateKey) {
		this(Signature.KeyPair.fromPrivateKey(privateKey));
	}

	/**
	 * Constructs a new {@code CryptoIdentity} from the given signature key pair.
	 *
	 * @param keyPair the signature key pair to use for this identity
	 */
	public CryptoIdentity(Signature.KeyPair keyPair) {
		this.keyPair = keyPair;
		this.encryptionKeyPair = CryptoBox.KeyPair.fromSignatureKeyPair(keyPair);
		this.id = Id.of(keyPair.publicKey().bytes());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Id getId() {
		return id;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public byte[] sign(byte[] data) {
		Objects.requireNonNull(data, "data");
		return Signature.sign(data, keyPair.privateKey());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean verify(byte[] data, byte[] signature) {
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(signature, "signature");
		return Signature.verify(data, signature, keyPair.publicKey());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public byte[] encrypt(Id receiver, byte[] data) throws CryptoException {
		Objects.requireNonNull(receiver, "receiver");
		Objects.requireNonNull(data, "data");

		try {
			// TODO: how to avoid the memory copy?!
			CryptoBox.Nonce nonce = CryptoBox.Nonce.random();
			CryptoBox.PublicKey pk = receiver.toEncryptionKey();
			CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
			byte[] cipher = CryptoBox.encrypt(data, pk, sk, nonce);

			byte[] buf = new byte[CryptoBox.Nonce.BYTES + cipher.length];
			System.arraycopy(nonce.bytes(), 0, buf, 0, CryptoBox.Nonce.BYTES);
			System.arraycopy(cipher, 0, buf, CryptoBox.Nonce.BYTES, cipher.length);
			return buf;
		} catch (SodiumException e) {
			throw new CryptoException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public byte[] encrypt(Id receiver, byte[] nonce, byte[] data) throws CryptoException {
		Objects.requireNonNull(receiver, "receiver");
		Objects.requireNonNull(nonce, "nonce");
		Objects.requireNonNull(data, "data");

		try {
			CryptoBox.Nonce n = CryptoBox.Nonce.fromBytes(nonce);
			CryptoBox.PublicKey pk = receiver.toEncryptionKey();
			CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
			return CryptoBox.encrypt(data, pk, sk, n);
		} catch (SodiumException e) {
			throw new CryptoException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(data, "data");

		if (data.length <= CryptoBox.Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		// TODO: how to avoid the memory copy?!
		try {
			byte[] n = Arrays.copyOfRange(data, 0, CryptoBox.Nonce.BYTES);
			CryptoBox.Nonce nonce = CryptoBox.Nonce.fromBytes(n);

			//if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
			//	throw new CryptoException("Duplicated nonce");

			//	lastPeerNonce = nonce;
			CryptoBox.PublicKey pk = sender.toEncryptionKey();
			CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
			byte[] cipher = Arrays.copyOfRange(data, CryptoBox.Nonce.BYTES, data.length);
			return CryptoBox.decrypt(cipher, pk, sk, nonce);
		} catch (SodiumException e) {
			throw new CryptoException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public byte[] decrypt(Id sender, byte[] nonce, byte[] data) throws CryptoException {
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(nonce, "nonce");
		Objects.requireNonNull(data, "data");

		if (data.length <= CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		try {
			CryptoBox.Nonce n = CryptoBox.Nonce.fromBytes(nonce);

			//if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
			//	throw new CryptoException("Duplicated nonce");

			//	lastPeerNonce = nonce;
			CryptoBox.PublicKey pk = sender.toEncryptionKey();
			CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
			return CryptoBox.decrypt(data, pk, sk, n);
		} catch (SodiumException e) {
			throw new CryptoException(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CryptoContext createCryptoContext(Id id) throws CryptoException {
		Objects.requireNonNull(id, "id");

		try {
			CryptoBox.PublicKey pk = id.toEncryptionKey();
			CryptoBox box = CryptoBox.fromKeys(pk, encryptionKeyPair.privateKey());
			return new CryptoContext(id, box);
		} catch (SodiumException e) {
			throw new CryptoException(e);
		}
	}

	/**
	 * Returns the signature key pair used by this identity.
	 *
	 * @return the {@link Signature.KeyPair}
	 */
	public Signature.KeyPair getKeyPair() {
		return keyPair;
	}

	/**
	 * Returns the encryption key pair derived from the signature key pair.
	 *
	 * @return the {@link CryptoBox.KeyPair} used for encryption and decryption
	 */
	public CryptoBox.KeyPair getEncryptionKeyPair() {
		return encryptionKeyPair;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof CryptoIdentity that)
			return this.id.equals(that.id);

		return false;
	}
}