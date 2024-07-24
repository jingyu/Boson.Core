package io.bosonnetwork.crypto;

import java.util.Arrays;

import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

public class CryptoIdentity implements Identity {
	private final Id id;
	private final Signature.KeyPair keyPair;
	private final CryptoBox.KeyPair encryptionKeyPair;

	public CryptoIdentity() {
		this(Signature.KeyPair.random());
	}

	public CryptoIdentity(byte[] privateKey) {
		this(Signature.KeyPair.fromPrivateKey(privateKey));
	}

	public CryptoIdentity(Signature.KeyPair keyPair) {
		this.keyPair = keyPair;
		this.encryptionKeyPair = CryptoBox.KeyPair.fromSignatureKeyPair(keyPair);
		this.id = Id.of(keyPair.publicKey().bytes());
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public byte[] sign(byte[] data) {
		return Signature.sign(data, keyPair.privateKey());
	}

	@Override
	public boolean verify(byte[] data, byte[] signature) {
		return Signature.verify(data, signature, keyPair.publicKey());
	}

	// one-shot encryption
	@Override
	public byte[] encrypt(Id recipient, byte[] data) {
		// TODO: how to avoid the memory copy?!
		CryptoBox.Nonce nonce = CryptoBox.Nonce.random();
		CryptoBox.PublicKey pk = recipient.toEncryptionKey();
		CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
		byte[] cipher = CryptoBox.encrypt(data, pk, sk, nonce);

		byte[] buf = new byte[CryptoBox.Nonce.BYTES + cipher.length];
		System.arraycopy(nonce.bytes(), 0, buf, 0, CryptoBox.Nonce.BYTES);
		System.arraycopy(cipher, 0, buf,CryptoBox. Nonce.BYTES, cipher.length);
		return buf;
	}

	// one-short decryption
	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		if (data.length <= CryptoBox.Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		// TODO: how to avoid the memory copy?!
		byte[] n = Arrays.copyOfRange(data, 0, CryptoBox.Nonce.BYTES);
		CryptoBox.Nonce nonce = CryptoBox.Nonce.fromBytes(n);

		//if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
		//	throw new CryptoException("Duplicated nonce");

		//	lastPeerNonce = nonce;
		CryptoBox.PublicKey pk = sender.toEncryptionKey();
		CryptoBox.PrivateKey sk = encryptionKeyPair.privateKey();
		byte[] cipher = Arrays.copyOfRange(data, CryptoBox.Nonce.BYTES, data.length);
		return CryptoBox.decrypt(cipher, pk, sk, nonce);
	}

	@Override
	public CryptoContext createCryptoContext(Id id) {
		CryptoBox.PublicKey pk = id.toEncryptionKey();
		CryptoBox box = CryptoBox.fromKeys(pk, encryptionKeyPair.privateKey());
		return new CryptoContext(id, box);
	}

	public Signature.KeyPair getKeyPair() {
		return keyPair;
	}

	public CryptoBox.KeyPair getEncryptionKeyPair() {
		return encryptionKeyPair;
	}
}
