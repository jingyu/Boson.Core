package io.bosonnetwork.crypto;

import java.util.Arrays;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.CryptoBox.PrivateKey;
import io.bosonnetwork.crypto.CryptoBox.PublicKey;

public class CryptoContext implements AutoCloseable {
	private final Id id;
	private final CryptoBox box;

	private Nonce nextNonce;
	private Nonce lastPeerNonce;

	public CryptoContext(Id id, PrivateKey privateKey) throws CryptoException {
		this.id = id;

		Signature.PublicKey sigPk = Signature.PublicKey.fromBytes(id.bytes());
		PublicKey publicKey = PublicKey.fromSignatureKey(sigPk);
		this.box = CryptoBox.fromKeys(publicKey, privateKey);
		this.nextNonce = Nonce.random();
	}

	public CryptoContext(Id id, CryptoBox box) throws CryptoException {
		this.id = id;
		this.box = box;
		this.nextNonce = Nonce.random();
	}

	public Id getId() {
		return id;
	}

	private synchronized Nonce getAndIncrementNonce() {
		Nonce nonce = nextNonce;
		nextNonce = nonce.increment();
		return nonce;
	}

	public byte[] encrypt(byte[] data) throws CryptoException {
		// TODO: how to avoid the memory copy?!
		Nonce nonce = getAndIncrementNonce();
		byte[] cipher = box.encrypt(data, nonce);

		byte[] buf = new byte[Nonce.BYTES + cipher.length];
		System.arraycopy(nonce.bytes(), 0, buf, 0, Nonce.BYTES);
		System.arraycopy(cipher, 0, buf, Nonce.BYTES, cipher.length);
		return buf;
	}

	public byte[] decrypt(byte[] data) throws CryptoException {
		if (data.length <= Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		// TODO: how to avoid the memory copy?!
		byte[] n = Arrays.copyOfRange(data, 0, Nonce.BYTES);
		Nonce nonce = Nonce.fromBytes(n);
		if (lastPeerNonce != null && nonce.equals(lastPeerNonce))
			throw new CryptoException("Duplicated nonce");

		lastPeerNonce = nonce;

		byte[] cipher = Arrays.copyOfRange(data, Nonce.BYTES, data.length);
		return box.decrypt(cipher, nonce);
	}

	@Override
	public void close() {
		box.close();
	}
}
