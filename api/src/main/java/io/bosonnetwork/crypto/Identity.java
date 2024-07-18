package io.bosonnetwork.crypto;

import java.util.Arrays;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.CryptoBox.PublicKey;

public class Identity {
	private final Id id;
	private final Signature.KeyPair keyPair;
	private final CryptoBox.KeyPair encryptionKeyPair;

	public Identity() {
		this(Signature.KeyPair.random());
	}

	public Identity(byte[] privateKey) {
		this(Signature.KeyPair.fromPrivateKey(privateKey));
	}

	public Identity(Signature.KeyPair keyPair) {
		this.keyPair = keyPair;
		this.encryptionKeyPair = CryptoBox.KeyPair.fromSignatureKeyPair(keyPair);
		this.id = Id.of(keyPair.publicKey().bytes());
	}

	public Id getId() {
		return id;
	}

	public byte[] sign(byte[] data) throws CryptoException {
		return Signature.sign(data, keyPair.privateKey());
	}

	public boolean verify(byte[] data, byte[] signature) throws CryptoException {
		return Signature.verify(data, signature, keyPair.publicKey());
	}

	// one-shot encryption
	public byte[] encrypt(Id recipient, byte[] data) throws CryptoException {
		// TODO: how to avoid the memory copy?!
		Nonce nonce = Nonce.random();
		CryptoBox.PublicKey pk = recipient.toEncryptionKey();
		CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
		byte[] cipher = CryptoBox.encrypt(data, pk, sk, nonce);

		byte[] buf = new byte[Nonce.BYTES + cipher.length];
		System.arraycopy(nonce.bytes(), 0, buf, 0, Nonce.BYTES);
		System.arraycopy(cipher, 0, buf, Nonce.BYTES, cipher.length);
		return buf;
	}

	// one-short decryption
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		if (data.length <= Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		// TODO: how to avoid the memory copy?!
		byte[] n = Arrays.copyOfRange(data, 0, Nonce.BYTES);
		Nonce nonce = Nonce.fromBytes(n);

		//if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
		//	throw new CryptoException("Duplicated nonce");

		//	lastPeerNonce = nonce;
		CryptoBox.PublicKey pk = sender.toEncryptionKey();
		CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
		byte[] cipher = Arrays.copyOfRange(data, Nonce.BYTES, data.length);
		return CryptoBox.decrypt(cipher, pk, sk, nonce);
	}

	public CryptoContext createCryptoContext(Id id) throws CryptoException {
		PublicKey pk = id.toEncryptionKey();
		CryptoBox box = CryptoBox.fromKeys(pk, encryptionKeyPair.privateKey());
		return new CryptoContext(id, box);
	}
}
