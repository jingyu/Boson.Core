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

package io.bosonnetwork.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConfigurationBuilder;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;

/**
 * Token-bucket rate limiting for Boson's HTTP services, backed by Bucket4j.
 * <p>
 * Three kinds of limit are enforced, each optional and each charged in <em>tokens</em> rather than
 * in requests, so that an expensive request can be priced above a cheap one:
 * <ul>
 *   <li><b>Per remote address</b> - a coarse limit applied <em>before</em> authentication, so that
 *       floods of unauthenticated (or already-throttled) requests cannot burn signature
 *       verifications and authorizer lookups without bound. On a service with permissionless routes
 *       this is the only limit those routes ever see.</li>
 *   <li><b>Per user</b> - the primary limit on authenticated routes, using the windows granted to
 *       the caller by the authorizer and falling back to the scope's defaults.</li>
 *   <li><b>Service wide</b> - a single ceiling across all callers, bounding what the node can be
 *       made to do in aggregate no matter how many users or addresses are active.</li>
 * </ul>
 *
 * <h2>Scopes</h2>
 * The per-user and per-address limits are namespaced by a {@link Scope}, which pairs a name with the
 * default policy for callers in it. A service whose API is homogeneous declares one scope and never
 * thinks about it again; one that hosts several APIs behind a single server - an admin console and a
 * public registration endpoint, say - declares a scope for each, so their budgets neither share a
 * bucket nor have to be reconciled into a single number that fits neither.
 *
 * <h2>IPv6</h2>
 * Addresses are bucketed by {@linkplain Builder#ipv6PrefixBits(int) prefix} rather than by exact
 * address, because a single IPv6 host is routinely assigned an entire /64. Bucketing on the full
 * address would let any such host mint a fresh budget per request simply by picking a new source
 * address, which makes a per-address limit decorative rather than protective.
 *
 * <h2>Bucket lifecycle</h2>
 * Buckets live in a Caffeine-backed {@link CaffeineProxyManager}. Entries expire once the bucket
 * would have refilled to its maximum, so eviction can never discard outstanding consumption -
 * a fully refilled bucket is indistinguishable from an absent one. {@code maxTrackedClients}
 * remains as a memory backstop only.
 *
 * <h2>Configuration changes</h2>
 * A caller's effective limits change when their plan changes. Each request compares the limits
 * stored in the bucket against the ones the authorizer currently grants and reconfigures the bucket
 * when they differ, so a changed plan is picked up on the next request in either direction rather
 * than being pinned to whatever limits were in force when the bucket was first created. Tokens are
 * carried over {@linkplain TokensInheritanceStrategy#PROPORTIONALLY proportionally}, so neither an
 * upgrade nor a downgrade hands out a free full bucket.
 *
 * <h2>Threading</h2>
 * {@link CaffeineProxyManager} executes commands synchronously against an in-memory map, so every
 * method here returns without blocking and is safe to call directly from an event loop. The
 * instance itself is thread safe.
 */
public class RateLimiter implements AutoCloseable {
	/**
	 * Ceiling used when a bucket has no configured limit at all. Bucket4j rejects a configuration
	 * with no bandwidths, so a limit is still required to build one; the check methods short-circuit
	 * before reaching a bucket in that case, so this is only ever a structural placeholder.
	 */
	private static final int UNLIMITED_CAPACITY = 65536;

	/**
	 * Grace period added to the time a bucket needs to refill to its maximum before its cache entry
	 * becomes eligible for expiration. Guards against a bucket being dropped a moment before the
	 * final refill lands.
	 */
	private static final Duration EXPIRY_GRACE = Duration.ofSeconds(30);

	/**
	 * Upper bound on distinct bucket configurations kept around. Configurations are shared by every
	 * caller with the same limits, so this only ever grows with the number of distinct limit
	 * combinations in use across all scopes.
	 */
	private static final int MAX_CACHED_CONFIGURATIONS = 64;

	/** Default IPv6 bucketing prefix: the /64 a host is typically delegated. */
	public static final int DEFAULT_IPV6_PREFIX_BITS = 64;

	/** Default bound on simultaneously tracked callers. */
	public static final int DEFAULT_MAX_TRACKED_CLIENTS = 65536;

	/**
	 * A named limit namespace with the default policy for callers in it.
	 *
	 * @param name     the scope name, used in bucket keys and in log messages
	 * @param defaults the policy applied to callers whose authorization details say nothing
	 */
	public record Scope(String name, RateLimitPolicy defaults) {
		/**
		 * Canonical constructor.
		 *
		 * @throws NullPointerException if either argument is null
		 */
		public Scope {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(defaults, "defaults");
		}
	}

	/** The outcome of a limit check, carrying what a 429 response needs. */
	public record Decision(boolean allowed, long remainingTokens, long retryAfterSeconds) {
		private static final Decision ALLOWED_UNLIMITED = new Decision(true, Long.MAX_VALUE, 0);

		/** @return a decision that allows the request without consulting any bucket. */
		public static Decision unlimited() {
			return ALLOWED_UNLIMITED;
		}

		/**
		 * Wraps a Bucket4j probe.
		 *
		 * @param probe the consumption probe
		 * @return the decision
		 */
		public static Decision of(ConsumptionProbe probe) {
			if (probe.isConsumed())
				return new Decision(true, probe.getRemainingTokens(), 0);

			// Round up: reporting 0 seconds would invite an immediate retry that is certain to fail.
			long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
			return new Decision(false, 0, Math.max(1, seconds));
		}
	}

	private final int servicePerSecond;
	private final int ipv6PrefixBits;

	private final ProxyManager<UserKey> userBuckets;
	private final ProxyManager<AddressKey> addressBuckets;
	private final @Nullable Bucket serviceBucket;

	// Bucket configurations are immutable and shared by every caller with the same limits. Bounded so
	// that per-caller overrides from an authorizer cannot grow this without limit.
	private final Map<RateLimitPolicy, BucketConfiguration> configCache;

	private record UserKey(String scope, Id id) {
	}

	private record AddressKey(String scope, String address) {
	}

	private RateLimiter(Builder builder) {
		this.servicePerSecond = builder.servicePerSecond;
		this.ipv6PrefixBits = builder.ipv6PrefixBits;

		this.configCache = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<RateLimitPolicy, BucketConfiguration> eldest) {
				return size() > MAX_CACHED_CONFIGURATIONS;
			}
		};

		this.userBuckets = newProxyManager(builder.maxTrackedClients, builder.clock);
		this.addressBuckets = newProxyManager(builder.maxTrackedClients, builder.clock);
		this.serviceBucket = servicePerSecond > 0 ?
				Bucket.builder()
						.addLimit(limit -> limit.capacity(servicePerSecond)
								.refillGreedy(servicePerSecond, Duration.ofSeconds(1)))
						.withCustomTimePrecision(builder.clock)
						.build() :
				null;
	}

	/**
	 * Creates a builder.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** Builder for {@link RateLimiter}. */
	public static class Builder {
		private int servicePerSecond;
		private int maxTrackedClients = DEFAULT_MAX_TRACKED_CLIENTS;
		private int ipv6PrefixBits = DEFAULT_IPV6_PREFIX_BITS;
		private TimeMeter clock = TimeMeter.SYSTEM_MILLISECONDS;

		private Builder() {
		}

		/**
		 * Sets the service-wide ceiling across all callers.
		 *
		 * @param perSecond requests per second, or {@code 0} to disable the service-wide limit
		 * @return this builder
		 * @throws IllegalArgumentException if negative
		 */
		public Builder servicePerSecond(int perSecond) {
			if (perSecond < 0)
				throw new IllegalArgumentException("servicePerSecond must be non-negative");

			this.servicePerSecond = perSecond;
			return this;
		}

		/**
		 * Sets the bound on simultaneously tracked callers. Bucket state is discarded once it has
		 * refilled to capacity, so this bounds concurrent callers, not total known callers.
		 *
		 * @param maxTrackedClients the bound, at least 1
		 * @return this builder
		 * @throws IllegalArgumentException if less than 1
		 */
		public Builder maxTrackedClients(int maxTrackedClients) {
			if (maxTrackedClients < 1)
				throw new IllegalArgumentException("maxTrackedClients must be at least 1");

			this.maxTrackedClients = maxTrackedClients;
			return this;
		}

		/**
		 * Sets the IPv6 prefix length used to group addresses into buckets.
		 *
		 * @param ipv6PrefixBits the prefix length in bits, in {@code [0, 128]}
		 * @return this builder
		 * @throws IllegalArgumentException if out of range
		 */
		public Builder ipv6PrefixBits(int ipv6PrefixBits) {
			if (ipv6PrefixBits < 0 || ipv6PrefixBits > 128)
				throw new IllegalArgumentException("ipv6PrefixBits must be in [0, 128]");

			this.ipv6PrefixBits = ipv6PrefixBits;
			return this;
		}

		/**
		 * Sets the time source backing every bucket. Tests use this to drive refills deterministically
		 * instead of sleeping.
		 *
		 * @param clock the time source
		 * @return this builder
		 */
		public Builder clock(TimeMeter clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
			return this;
		}

		/**
		 * Builds the rate limiter.
		 *
		 * @return the rate limiter
		 */
		public RateLimiter build() {
			return new RateLimiter(this);
		}
	}

	private <K> ProxyManager<K> newProxyManager(int maximumSize, TimeMeter clock) {
		// Deliberately a plain Caffeine builder rather than VertxCaffeine: this cache is written on
		// every request and Caffeine dispatches its post-write maintenance to the configured
		// executor. VertxCaffeine routes that through Vert.x's ordered blocking pool, which would
		// queue cheap housekeeping behind real blocking work (database, file IO) at request rate.
		Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
				.initialCapacity(Math.min(256, maximumSize))
				.maximumSize(maximumSize);

		return Bucket4jCaffeine.<K>builderFor(caffeine)
				// Expire an entry once its bucket would be back at full capacity: at that point the
				// entry carries no information, so dropping it cannot reset anyone's consumption.
				// Without this, entries never expire and size-based eviction silently hands a
				// throttled caller a fresh full bucket.
				.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(EXPIRY_GRACE))
				.clientClock(clock)
				.build();
	}

	/**
	 * Returns the bucket for {@code key}, reconfiguring it if the limits have changed since it was
	 * created.
	 * <p>
	 * {@link ProxyManager#getProxy(Object, java.util.function.Supplier)} only consults the supplier
	 * when no bucket exists yet, so an existing bucket would otherwise keep whatever limits were in
	 * force when it was first created - a caller who changed plan would stay pinned to the old limits
	 * for as long as the entry survives. Bucket4j's implicit configuration replacement is no help
	 * here: it only replaces when the desired version is strictly greater than the stored one, which
	 * cannot express a downgrade or a return to a previously used plan.
	 */
	private static <K> Bucket bucketFor(ProxyManager<K> proxyManager, K key, BucketConfiguration desired) {
		BucketConfiguration stored = proxyManager.getProxyConfiguration(key).orElse(null);
		Bucket bucket = proxyManager.getProxy(key, () -> desired);
		if (stored != null && !stored.equals(desired))
			// PROPORTIONALLY carries the consumed fraction across the change, so neither direction
			// hands out a free full bucket: an exhausted caller stays exhausted and refills at the
			// new rate, rather than plan churn becoming a way to mint tokens on demand.
			bucket.replaceConfiguration(desired, TokensInheritanceStrategy.PROPORTIONALLY);

		return bucket;
	}

	private BucketConfiguration bucketConfiguration(RateLimitPolicy policy) {
		synchronized (configCache) {
			return configCache.computeIfAbsent(policy, RateLimiter::buildBucketConfiguration);
		}
	}

	private static BucketConfiguration buildBucketConfiguration(RateLimitPolicy policy) {
		ConfigurationBuilder builder = BucketConfiguration.builder();
		if (policy.perMinute() > 0)
			builder.addLimit(limit -> limit.capacity(policy.perMinute())
					.refillGreedy(policy.perMinute(), Duration.ofMinutes(1)));
		if (policy.perHour() > 0)
			builder.addLimit(limit -> limit.capacity(policy.perHour())
					.refillGreedy(policy.perHour(), Duration.ofHours(1)));
		if (policy.perDay() > 0)
			builder.addLimit(limit -> limit.capacity(policy.perDay())
					.refillGreedy(policy.perDay(), Duration.ofDays(1)));
		if (!policy.isLimited())
			builder.addLimit(limit -> limit.capacity(UNLIMITED_CAPACITY)
					.refillGreedy(UNLIMITED_CAPACITY, Duration.ofHours(1)));

		return builder.build();
	}

	/**
	 * Whether a service-wide limit is configured.
	 *
	 * @return {@code true} if the service-wide ceiling is enabled
	 */
	public boolean isServiceLimited() {
		return serviceBucket != null;
	}

	/**
	 * The service-wide ceiling, for reporting.
	 *
	 * @return requests per second, or {@code 0} when disabled
	 */
	public int servicePerSecond() {
		return servicePerSecond;
	}

	/**
	 * The bucket key an address maps to, exposed for logging and for tests that need to reason about
	 * grouping. IPv6 addresses are truncated to the configured prefix; IPv4 addresses are used whole.
	 *
	 * @param address the remote address, may be {@code null}
	 * @return the bucket key, or {@code null} when the address is absent
	 */
	public @Nullable String addressKey(@Nullable String address) {
		if (address == null || address.isBlank())
			return null;

		try {
			byte[] bytes = InetAddress.getByName(address).getAddress();
			if (bytes.length != 16)
				return "v4:" + address;

			int keepBytes = Math.min(16, Math.max(0, ipv6PrefixBits) / 8);
			StringBuilder sb = new StringBuilder(3 + keepBytes * 2).append("v6:");
			for (int i = 0; i < keepBytes; i++)
				sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16))
						.append(Character.forDigit(bytes[i] & 0xF, 16));

			return sb.toString();
		} catch (UnknownHostException e) {
			// Not a literal address - keep it verbatim rather than dropping the limit entirely. This
			// is reachable only when a trusted proxy header carries something unparseable.
			return "raw:" + address;
		}
	}

	/**
	 * Charges {@code cost} tokens against a caller's bucket in {@code scope}.
	 *
	 * @param scope    the limit namespace
	 * @param userId   the caller to charge
	 * @param features the authorization details for the caller, may be {@code null}; pass
	 *                 {@code null} for a scope whose limits are deliberately not plan-driven
	 * @param cost     the number of tokens the request is worth
	 * @return the decision; always allowed when the effective policy enforces nothing
	 */
	public Decision checkUser(Scope scope, Id userId, @Nullable Map<String, Object> features, long cost) {
		RateLimitPolicy policy = scope.defaults().override(features);
		// Nothing configured for this caller: skip the bucket entirely rather than charging against
		// the synthetic "unlimited" ceiling, so that a disabled limit costs nothing to enforce.
		if (!policy.isLimited() || userId == null)
			return Decision.unlimited();

		Bucket bucket = bucketFor(userBuckets, new UserKey(scope.name(), userId), bucketConfiguration(policy));
		return Decision.of(bucket.tryConsumeAndReturnRemaining(cost));
	}

	/**
	 * Charges {@code cost} tokens against the bucket for a remote address in {@code scope}.
	 * <p>
	 * The cost matters here in a way it does not for a purely authenticated API: permissionless
	 * routes are only ever seen by this limit, so an expensive one has to be priced above a cheap one
	 * here or it is not priced at all.
	 *
	 * @param scope   the limit namespace
	 * @param address the remote address, as resolved by the caller
	 * @param cost    the number of tokens the request is worth
	 * @return the decision; always allowed when the scope enforces nothing or the address is unknown
	 */
	public Decision checkAddress(Scope scope, @Nullable String address, long cost) {
		RateLimitPolicy policy = scope.defaults();
		if (!policy.isLimited())
			return Decision.unlimited();

		String key = addressKey(address);
		if (key == null)
			return Decision.unlimited();

		Bucket bucket = bucketFor(addressBuckets, new AddressKey(scope.name(), key), bucketConfiguration(policy));
		return Decision.of(bucket.tryConsumeAndReturnRemaining(cost));
	}

	/**
	 * Charges {@code cost} tokens against the service-wide bucket.
	 *
	 * @param cost the number of tokens the request is worth
	 * @return the decision; always allowed when no service-wide limit is configured
	 */
	public Decision checkService(long cost) {
		if (serviceBucket == null)
			return Decision.unlimited();

		return Decision.of(serviceBucket.tryConsumeAndReturnRemaining(cost));
	}

	/**
	 * Returns {@code cost} tokens to a caller's bucket, for a request that was charged but then
	 * refused for a reason of the service's own making rather than the client's.
	 * <p>
	 * {@link Bucket#addTokens(long)} is capped at the bucket's capacity, so a refund can never
	 * mint tokens beyond the limit even if it is called without a matching charge.
	 *
	 * @param scope    the limit namespace the charge was made in
	 * @param userId   the caller to credit
	 * @param features the authorization details for the caller, may be {@code null}
	 * @param cost     the number of tokens to return
	 */
	public void refundUser(Scope scope, Id userId, @Nullable Map<String, Object> features, long cost) {
		RateLimitPolicy policy = scope.defaults().override(features);
		// Mirrors checkUser: nothing was charged, so there is nothing to give back.
		if (!policy.isLimited() || userId == null)
			return;

		bucketFor(userBuckets, new UserKey(scope.name(), userId), bucketConfiguration(policy)).addTokens(cost);
	}

	/**
	 * Returns {@code cost} tokens to an address bucket. See {@link #refundUser}.
	 *
	 * @param scope   the limit namespace the charge was made in
	 * @param address the remote address to credit
	 * @param cost    the number of tokens to return
	 */
	public void refundAddress(Scope scope, @Nullable String address, long cost) {
		RateLimitPolicy policy = scope.defaults();
		if (!policy.isLimited())
			return;

		String key = addressKey(address);
		if (key == null)
			return;

		bucketFor(addressBuckets, new AddressKey(scope.name(), key), bucketConfiguration(policy)).addTokens(cost);
	}

	/**
	 * Returns {@code cost} tokens to the service-wide bucket. See {@link #refundUser}.
	 *
	 * @param cost the number of tokens to return
	 */
	public void refundService(long cost) {
		if (serviceBucket != null)
			serviceBucket.addTokens(cost);
	}

	/**
	 * Releases the bucket caches. Called when the service is undeployed so a redeploy within the
	 * same JVM does not strand the previous generation of buckets.
	 */
	@Override
	public void close() {
		invalidate(userBuckets);
		invalidate(addressBuckets);
		synchronized (configCache) {
			configCache.clear();
		}
	}

	private static void invalidate(ProxyManager<?> proxyManager) {
		if (proxyManager instanceof CaffeineProxyManager<?> caffeine)
			caffeine.getCache().invalidateAll();
	}
}
