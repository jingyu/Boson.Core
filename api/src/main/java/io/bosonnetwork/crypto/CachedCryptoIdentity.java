package io.bosonnetwork.crypto;

import java.util.Objects;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;

import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * An implementation of {@link Identity} that extends {@link CryptoIdentity} and adds a Caffeine-based cache
 * for precomputed {@link CryptoContext} instances to optimize cryptographic operations.
 * This caching mechanism reduces the overhead of repeatedly computing contexts for encryption and decryption,
 * providing better performance compared to a non-caching {@code CryptoIdentity}.
 * <p>
 * The cache is implemented using Caffeine with configurable settings to control resource usage and cache expiration.
 */
public class CachedCryptoIdentity extends CryptoIdentity implements Identity {
	private volatile LoadingCache<Id, CryptoContext> cryptoContexts;

	/**
	 * Constructs a new {@code CachedCryptoIdentity} with a randomly generated signature key pair
	 * and the provided Caffeine cache configuration.
	 *
	 * @param caffeine the Caffeine cache builder used to configure the cache; must not be {@code null}
	 */
	public CachedCryptoIdentity(Caffeine<Object, Object> caffeine) {
		this(Signature.KeyPair.random(), caffeine);
	}

	/**
	 * Constructs a new {@code CachedCryptoIdentity} from the given private key bytes
	 * and the provided Caffeine cache configuration.
	 *
	 * @param privateKey the private key bytes used to create the signature key pair; must not be {@code null}
	 * @param caffeine the Caffeine cache builder used to configure the cache; must not be {@code null}
	 */
	public CachedCryptoIdentity(byte[] privateKey, Caffeine<Object, Object> caffeine) {
		this(Signature.KeyPair.fromPrivateKey(privateKey), caffeine);
	}

	/**
	 * Constructs a new {@code CachedCryptoIdentity} from the given signature key pair
	 * and the provided Caffeine cache configuration.
	 *
	 * @param keyPair the signature key pair to use for this identity; must not be {@code null}
	 * @param caffeine the Caffeine cache builder used to configure the cache; must not be {@code null}
	 */
	public CachedCryptoIdentity(Signature.KeyPair keyPair, Caffeine<Object, Object> caffeine) {
		super(keyPair);
		if (caffeine != null)
			initCache(caffeine);
	}

	/**
	 * Initializes the cache with the provided Caffeine builder and attaches a removal listener
	 * that closes {@link CryptoContext} instances when they are removed from the cache.
	 *
	 * @param caffeine the Caffeine cache builder used to configure the cache; must not be {@code null}
	 * @throws NullPointerException if {@code caffeine} is {@code null}
	 * @throws IllegalStateException if the cache has already been initialized
	 */
	public void initCache(Caffeine<Object, Object> caffeine) {
		Objects.requireNonNull(caffeine, "caffeine");

		if (cryptoContexts != null)
			throw new IllegalStateException("Cache already initialized");

		this.cryptoContexts = caffeine.removalListener((Id id, CryptoContext ctx, RemovalCause cause) -> {
			if (ctx != null)
				ctx.close();
		}).build(super::createCryptoContext);
	}

	/**
	 * Clears the cached {@link CryptoContext} instances.
	 *
	 * This method invalidates all entries in the cache, ensuring that any
	 * cached cryptographic contexts are removed. Subsequent operations
	 * will no longer utilize the invalidated contexts and may trigger
	 * re-creation of new contexts as needed.
	 *
	 * If the cache is uninitialized or null, this method has no effect.
	 */
	public void clearCache() {
		if (cryptoContexts != null)
			cryptoContexts.invalidateAll();
	}

	private CryptoContext getContext(Id id) throws CryptoException {
		return cryptoContexts != null ? cryptoContexts.get(id) : super.createCryptoContext(id);
	}

	/**
	 * Performs one-shot encryption of the given data for the specified recipient.
	 * <p>
	 * This operation leverages a cached {@link CryptoContext} instance associated with the recipient,
	 * reducing the overhead of repeatedly computing cryptographic contexts.
	 *
	 * @param recipient the recipient's {@link Id}; must not be {@code null}
	 * @param data the plaintext data to encrypt; must not be {@code null}
	 * @return the encrypted data including the nonce prepended
	 * @throws NullPointerException if {@code recipient} or {@code data} is {@code null}
	 * @throws CryptoException if an error occurs during encryption
	 */
	@Override
	public byte[] encrypt(Id recipient, byte[] data) throws CryptoException {
		Objects.requireNonNull(recipient, "recipient");
		Objects.requireNonNull(data, "data");
		return getContext(recipient).encrypt(data);
	}

	/**
	 * Performs one-shot decryption of the given encrypted data from the specified sender.
	 * <p>
	 * This operation leverages a cached {@link CryptoContext} instance associated with the sender,
	 * reducing the overhead of repeatedly computing cryptographic contexts.
	 *
	 * @param sender the sender's {@link Id}; must not be {@code null}
	 * @param data the encrypted data including the nonce prepended
	 * @return the decrypted plaintext data
	 * @throws CryptoException if the cipher size is invalid or an error occurs during decryption
	 */
	@Override
	public byte[] decrypt(Id sender, byte[] data) throws CryptoException {
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(data, "data");

		if (data.length <= CryptoBox.Nonce.BYTES + CryptoBox.MAC_BYTES)
			throw new CryptoException("Invalid cipher size");

		return getContext(sender).decrypt(data);
	}

	/**
	 * Creates a {@link CryptoContext} for secure communications with the specified identity.
	 * <p>
	 * This method returns a cached {@link CryptoContext} instance if available, minimizing
	 * repeated computation and improving performance.
	 *
	 * @param id the identity to create the crypto context for; must not be {@code null}
	 * @return a {@link CryptoContext} instance associated with the specified identity
	 * @throws CryptoException if an error occurs during context creation
	 */
	@Override
	public CryptoContext createCryptoContext(Id id) throws CryptoException {
		Objects.requireNonNull(id, "id");
		return getContext(id);
	}
}