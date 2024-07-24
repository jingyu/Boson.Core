package io.bosonnetwork;

public interface Identity {
	public Id getId();

	public byte[] sign(byte[] data);

	public boolean verify(byte[] data, byte[] signature);

	// one-shot encryption
	public byte[] encrypt(Id recipient, byte[] data);

	// one-short decryption
	public byte[] decrypt(Id sender, byte[] data) throws BosonException;

	public CryptoContext createCryptoContext(Id id);
}
