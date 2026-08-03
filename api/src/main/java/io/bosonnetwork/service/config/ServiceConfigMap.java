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

package io.bosonnetwork.service.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.LookupOption;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.ConfigMap;
import io.bosonnetwork.utils.Hex;
import io.bosonnetwork.web.RateLimitPolicy;

/**
 * A {@link ConfigMap} that additionally understands the settings Boson services have in common.
 * <p>
 * Where {@code ConfigMap} deals in primitives, this adds readers for the Boson domain types that
 * appear in every service configuration - keys, lookup options, rate limit policies - and for the
 * shared {@link ConfigOptions} blocks in this package. Each reader returns the block's documented
 * default when the key is absent, so a service can describe its whole configuration as a sequence
 * of reads with defaults rather than as a tree of null checks.
 * <p>
 * Writing is the mirror image: {@link #put(String, Object)} recognizes {@code ConfigOptions} and
 * {@link RateLimitPolicy} values and serializes them, so a service builds a configuration document
 * by putting the same values it read.
 */
public class ServiceConfigMap extends ConfigMap {
	/**
	 * Wraps an existing configuration map, as parsed from a YAML or JSON document.
	 *
	 * @param map the map to wrap, must not be null
	 */
	public ServiceConfigMap(Map<String, Object> map) {
		super(map);
	}

	/**
	 * Creates an empty configuration map, for building a document up entry by entry.
	 */
	public ServiceConfigMap() {
		super();
	}

	@Override
	public @Nullable ServiceConfigMap getObject(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			return null;
		} else if (val instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> subMap = (Map<String, Object>) m;
			return new ServiceConfigMap(subMap);
		} else {
			throw new IllegalArgumentException("Invalid object value - " + key + ": " + val);
		}
	}

	/**
	 * Retrieves a signing key pair from the private key at the specified key.
	 * <p>
	 * The key is accepted either as Base58 or, with a {@code 0x} prefix, as hexadecimal.
	 *
	 * @param key the configuration key, must not be null
	 * @return the key pair derived from the private key
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the key is missing, empty, or not a valid private key
	 */
	public Signature.KeyPair getPrivateKey(String key) {
		Objects.requireNonNull(key);
		String sk = getString(key);
		if (sk.isEmpty())
			throw new IllegalArgumentException("Private key cannot be empty - " + key);

		try {
			return Signature.KeyPair.fromPrivateKey(sk.startsWith("0x") ?
					Hex.decode(sk, 2, sk.length() - 2) :
					Base58.decode(sk));
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid private key - " + key, e);
		}
	}

	/**
	 * Retrieves a signing key pair from the private key at the specified key, with a default.
	 *
	 * @param key the configuration key, must not be null
	 * @param def the key pair to return if the key is absent; may be {@code null}
	 * @return the key pair, or {@code def} if the key is absent
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the value is not a valid private key
	 */
	public Signature.@Nullable KeyPair getPrivateKey(String key, Signature.@Nullable KeyPair def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getPrivateKey(key) : def;
	}

	/**
	 * Retrieves a {@link LookupOption} for the specified key, matched case-insensitively.
	 *
	 * @param key the configuration key, must not be null
	 * @return the lookup option
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the key is missing, empty, or not a known lookup option
	 */
	public LookupOption getLookupOption(String key) {
		Objects.requireNonNull(key);
		String option = getString(key);
		if (option.isEmpty())
			throw new IllegalArgumentException("Lookup option cannot be empty - " + key);

		try {
			return LookupOption.valueOf(option.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid lookup option - " + key + ": " + option, e);
		}
	}

	/**
	 * Retrieves a {@link LookupOption} for the specified key, with a default.
	 *
	 * @param key the configuration key, must not be null
	 * @param def the option to return if the key is absent; may be {@code null}
	 * @return the lookup option, or {@code def} if the key is absent
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the value is not a known lookup option
	 */
	public @Nullable LookupOption getLookupOption(String key, @Nullable LookupOption def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getLookupOption(key) : def;
	}

	/**
	 * Reads a {@link RateLimitPolicy} from the block at the specified key.
	 * <p>
	 * A configured policy is taken as a whole value: the windows the block does not name are
	 * disabled, not inherited from anywhere. An absent or empty block yields
	 * {@link RateLimitPolicy#UNLIMITED}. Merging a policy window by window is reserved for per-caller
	 * plan data, which {@link RateLimitPolicy#override} handles.
	 *
	 * @param key the configuration key holding the policy block, must not be null
	 * @return the policy, or {@link RateLimitPolicy#UNLIMITED} if the block is absent or empty
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if a window is present but not a non-negative integer
	 */
	public RateLimitPolicy getRateLimitPolicy(String key) {
		Objects.requireNonNull(key);
		return rateLimitPolicyFromMap(getObject(key));
	}

	/**
	 * Reads a {@link RateLimitPolicy} from the block at the specified key, with a default.
	 * <p>
	 * The default applies only when the key is absent entirely. A block that is present but empty
	 * is an explicit statement that nothing is enforced, and yields
	 * {@link RateLimitPolicy#UNLIMITED} rather than {@code def}.
	 *
	 * @param key the configuration key holding the policy block, must not be null
	 * @param def the policy to return if the key is absent
	 * @return the policy, or {@code def} if the key is absent
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if a window is present but not a non-negative integer
	 */
	public RateLimitPolicy getRateLimitPolicy(String key, RateLimitPolicy def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getRateLimitPolicy(key) : def;
	}

	/**
	 * Reads the {@link PeerOptions} block at the specified key.
	 *
	 * @param key             the configuration key holding the peer block, must not be null
	 * @param expectedSchemes the URI schemes the announced endpoint may use; when empty, any scheme
	 *                        is accepted, though the endpoint must still be a well-formed URI
	 * @return the options, or {@link PeerOptions#DEFAULT} if the block is absent or empty
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the block holds an invalid value
	 */
	public PeerOptions getPeerOptions(String key, String... expectedSchemes) {
		Objects.requireNonNull(key);
		return PeerOptions.fromMap(getObject(key), expectedSchemes);
	}

	/**
	 * Reads the {@link DatabaseOptions} block at the specified key.
	 *
	 * @param key        the configuration key holding the database block, must not be null
	 * @param defaultUri the connection URI to use when the block does not give one
	 * @return the options
	 * @throws NullPointerException     if the key or {@code defaultUri} is null
	 * @throws IllegalArgumentException if the block holds an invalid value
	 */
	public DatabaseOptions getDatabaseOptions(String key, String defaultUri) {
		return getDatabaseOptions(key, defaultUri, null);
	}

	/**
	 * Reads the {@link DatabaseOptions} block at the specified key, checking that the resulting URI
	 * is one the service can actually use.
	 *
	 * @param key          the configuration key holding the database block, must not be null
	 * @param defaultUri   the connection URI to use when the block does not give one
	 * @param supportCheck tests whether a connection URI is supported; applied to the configured URI
	 *                     and to {@code defaultUri} alike, may be {@code null} to skip the check
	 * @return the options
	 * @throws NullPointerException     if the key or {@code defaultUri} is null
	 * @throws IllegalArgumentException if the URI is unsupported or the block holds an invalid value
	 */
	public DatabaseOptions getDatabaseOptions(String key, String defaultUri, @Nullable Predicate<String> supportCheck) {
		Objects.requireNonNull(key);
		return DatabaseOptions.fromMap(getObject(key), defaultUri, supportCheck);
	}

	/**
	 * Reads the {@link ListenOptions} from this map.
	 * <p>
	 * Unlike the other blocks these settings are read from this map directly rather than from a
	 * named sub-block, because {@code host}, {@code port} and {@code ssl} sit at the top level of a
	 * service configuration document. {@link #putListenOptions} writes them back the same way.
	 *
	 * @param defaultHost the host to bind to when the document does not name one
	 * @param defaultPort the port to listen on when the document does not name one
	 * @param defaultSsl  whether TLS is enabled when the document does not say
	 * @return the options
	 * @throws IllegalArgumentException if the document holds an invalid host or port
	 */
	public ListenOptions getListenOptions(String defaultHost, int defaultPort, boolean defaultSsl) {
		return ListenOptions.fromMap(this, defaultHost, defaultPort, defaultSsl);
	}

	/**
	 * Reads the operator-provided {@link TlsOptions} from this map.
	 * <p>
	 * Like {@link #getListenOptions} these are top-level entries rather than a named block, so they
	 * are read from this map directly. {@link #putTlsOptions} writes them back the same way.
	 *
	 * @return the certificate pair, or {@code null} when none is configured - meaning a self-signed
	 *         certificate should be generated
	 * @throws IllegalArgumentException if only one of the certificate and key is given
	 */
	public @Nullable TlsOptions getTlsOptions() {
		return TlsOptions.fromMap(this);
	}

	/**
	 * Writes the {@link TlsOptions} into this map as top-level entries, mirroring
	 * {@link #getTlsOptions}.
	 *
	 * @param options the options to write, or {@code null} to remove the entries
	 */
	public void putTlsOptions(@Nullable TlsOptions options) {
		if (options == null) {
			map.keySet().removeAll(TlsOptions.KEYS);
			return;
		}

		putAll(options.toMap());
	}

	/**
	 * Writes the {@link ListenOptions} into this map as top-level entries, mirroring
	 * {@link #getListenOptions}.
	 * <p>
	 * These options have no key of their own, so they cannot go through
	 * {@link #put(String, Object)} like the other blocks.
	 *
	 * @param options the options to write, or {@code null} to remove the entries
	 */
	public void putListenOptions(@Nullable ListenOptions options) {
		if (options == null) {
			map.keySet().removeAll(ListenOptions.KEYS);
			return;
		}

		putAll(options.toMap());
	}

	private static RateLimitPolicy rateLimitPolicyFromMap(@Nullable ConfigMap cm) {
		if (cm == null)
			return RateLimitPolicy.UNLIMITED;

		return RateLimitPolicy.of(cm.getNonNegativeInteger("perSecond", 0),
				cm.getNonNegativeInteger("perMinute", 0),
				cm.getNonNegativeInteger("perHour", 0),
				cm.getNonNegativeInteger("perDay", 0));
	}

	/**
	 * Serializes a {@link RateLimitPolicy} as a {@code rateLimit} block, omitting disabled windows.
	 * <p>
	 * Public so that a service which nests policies more deeply than {@link #put(String, Object)}
	 * reaches - the Director scopes them per API group - can write them in the same shape everyone
	 * else reads. An empty result means nothing is enforced.
	 *
	 * @param policy the policy to serialize
	 * @return a new, ordered map of the windows that are set
	 */
	public static Map<String, Object> rateLimitPolicyToMap(RateLimitPolicy policy) {
		Map<String, Object> map = new LinkedHashMap<>();
		if (policy.perSecond() > 0)
			map.put("perSecond", policy.perSecond());
		if (policy.perMinute() > 0)
			map.put("perMinute", policy.perMinute());
		if (policy.perHour() > 0)
			map.put("perHour", policy.perHour());
		if (policy.perDay() > 0)
			map.put("perDay", policy.perDay());

		return map;
	}

	/**
	 * Associates a value with the specified key, serializing the value types this class understands.
	 * <p>
	 * A {@link ConfigOptions} or {@link RateLimitPolicy} value is written as its configuration
	 * block; anything else is stored as-is. A value that serializes to an empty block is every
	 * setting at its default, so the key is removed rather than written as an empty block - which
	 * keeps a generated configuration document down to the settings that actually say something.
	 * <p>
	 * {@code RateLimitPolicy} is handled here rather than by implementing {@code ConfigOptions}
	 * because it is a plain value type of the rate limiter API and does not depend on this package.
	 *
	 * @param key    the configuration key, must not be null
	 * @param object the value to associate, or {@code null} to remove the key
	 * @return the previous value associated with the key, or {@code null} if there was none
	 * @throws NullPointerException if the key is null
	 */
	@Override
	public @Nullable Object put(String key, @Nullable Object object) {
		Objects.requireNonNull(key);

		Map<String, Object> subMap;
		if (object instanceof ConfigOptions opts)
			subMap = opts.toMap();
		else if (object instanceof RateLimitPolicy policy)
			subMap = rateLimitPolicyToMap(policy);
		else
			return super.put(key, object);

		return subMap.isEmpty() ? map.remove(key) : map.put(key, subMap);
	}
}