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

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three rate limit windows a caller is subject to, enforced together.
 * <p>
 * A window of {@code 0} disables that window; a policy with all three at zero is
 * {@linkplain #isLimited() unlimited}. The windows are deliberately charged together rather than
 * being alternatives: {@code perDay} sets the sustained rate, {@code perHour} the medium-term rate,
 * and {@code perMinute} is purely a burst allowance, so raising the burst does not increase what a
 * caller may consume over a day.
 *
 * <h2>Where the numbers come from</h2>
 * A policy is read from configuration with {@link #fromConfig}, and may then be overridden per
 * caller by the authorization details their plan grants, with {@link #override}.
 * <p>
 * The two sources reject bad input differently, on purpose. A bad value in a configuration file is
 * an operator typo and stops the node from starting, because the operator is present to fix it and
 * silently ignoring it would disable a limit they believe they configured. A bad value in plan data
 * arrives from a database at request time with nobody watching, so it falls back to the configured
 * value and is logged - never read as "unlimited", so that a typo in plan data cannot raise a limit.
 *
 * @param perMinute the per-minute window, or {@code 0} to disable it
 * @param perHour   the per-hour window, or {@code 0} to disable it
 * @param perDay    the per-day window, or {@code 0} to disable it
 */
public record RateLimitPolicy(int perMinute, int perHour, int perDay) {
	/** A policy that enforces nothing. */
	public static final RateLimitPolicy UNLIMITED = new RateLimitPolicy(0, 0, 0);

	/** The configuration key holding a policy, wherever one appears. */
	public static final String CONFIG_KEY = "rateLimit";

	private static final Logger log = LoggerFactory.getLogger(RateLimitPolicy.class);

	/**
	 * Canonical constructor.
	 *
	 * @throws IllegalArgumentException if any window is negative
	 */
	public RateLimitPolicy {
		if (perMinute < 0 || perHour < 0 || perDay < 0)
			throw new IllegalArgumentException("Rate limit windows must be non-negative: " +
					perMinute + "/" + perHour + "/" + perDay);
	}

	/**
	 * Creates a policy with only a per-minute window, as used by the per-address scope.
	 *
	 * @param perMinute the per-minute window, or {@code 0} to disable the limit
	 * @return the policy
	 */
	public static RateLimitPolicy perMinute(int perMinute) {
		return new RateLimitPolicy(perMinute, 0, 0);
	}

	/**
	 * Whether this policy enforces anything at all.
	 *
	 * @return {@code true} if at least one window is set
	 */
	public boolean isLimited() {
		return perMinute > 0 || perHour > 0 || perDay > 0;
	}

	/**
	 * Reads the {@code rateLimit} block nested inside {@code scope}.
	 * <p>
	 * An absent or empty block selects {@code defaults} wholesale; a present block overrides only the
	 * windows it names, so a file may set {@code perMinute} alone without silently disabling the
	 * other two.
	 *
	 * @param scope    the configuration object containing a {@code rateLimit} block, may be {@code null}
	 * @param defaults the policy to fall back to, window by window
	 * @return the resulting policy
	 * @throws IllegalArgumentException if a window is present but not a non-negative number
	 */
	public static RateLimitPolicy fromConfig(@Nullable Map<String, Object> scope, RateLimitPolicy defaults) {
		if (scope == null)
			return defaults;

		Object value = scope.get(CONFIG_KEY);
		if (!(value instanceof Map))
			return defaults;

		@SuppressWarnings("unchecked")
		Map<String, Object> rateLimit = (Map<String, Object>) value;
		if (rateLimit.isEmpty())
			return defaults;

		return new RateLimitPolicy(
				window(rateLimit, "perMinute", defaults.perMinute()),
				window(rateLimit, "perHour", defaults.perHour()),
				window(rateLimit, "perDay", defaults.perDay()));
	}

	/**
	 * Reads one window from a configuration file, rejecting anything unusable.
	 * <p>
	 * Deliberately stricter than {@link #limit}, which reads the same shape from a caller's plan.
	 * The two sources fail differently on purpose: a bad window in a configuration file is an
	 * operator typo, and the operator is present to fix it, so the node refuses to start and says
	 * which key is wrong. Falling back to the default there would silently disable a limit the
	 * operator believes they configured. Plan data, by contrast, arrives from a database at request
	 * time with nobody watching, so a bad value must not take the node down - it falls back and
	 * logs.
	 */
	private static int window(Map<String, Object> rateLimit, String key, int defaultValue) {
		Object value = rateLimit.get(key);
		if (value == null)
			return defaultValue;

		int window;
		if (value instanceof Number n) {
			window = n.intValue();
		} else {
			try {
				window = Integer.parseInt(String.valueOf(value).trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid " + CONFIG_KEY + "." + key + ": not a number: " + value);
			}
		}

		if (window < 0)
			throw new IllegalArgumentException("Invalid " + CONFIG_KEY + "." + key + ": must be non-negative");

		return window;
	}

	/**
	 * Applies the {@code rateLimit} overrides carried in a caller's authorization details.
	 * <p>
	 * This is how a subscription plan raises a caller above the service default. Anything unusable -
	 * a missing block, the wrong type, a negative number, an unparseable string - leaves the
	 * corresponding window at this policy's value and is logged, because the alternative (treating it
	 * as absent-means-unlimited) would turn a typo in plan data into an unmetered caller.
	 *
	 * @param features the authorization details, may be {@code null}
	 * @return the effective policy; {@code this} when there is nothing to override
	 */
	@SuppressWarnings("unchecked")
	public RateLimitPolicy override(@Nullable Map<String, Object> features) {
		if (features == null || features.isEmpty())
			return this;

		Object value = features.get(CONFIG_KEY);
		if (value == null)
			return this;

		if (!(value instanceof Map)) {
			// noinspection LoggingSimilarMessage
			log.warn("Ignoring {} of unexpected type {} in authorization details",
					CONFIG_KEY, value.getClass().getName());
			return this;
		}

		Map<String, Object> rateLimit = (Map<String, Object>) value;
		return new RateLimitPolicy(
				limit(rateLimit, "perMinute", perMinute),
				limit(rateLimit, "perHour", perHour),
				limit(rateLimit, "perDay", perDay));
	}

	private static int limit(Map<String, Object> rateLimit, String key, int defaultValue) {
		Object value = rateLimit.get(key);
		if (value == null)
			return defaultValue;

		int limit;
		if (value instanceof Number n) {
			limit = n.intValue();
		} else if (value instanceof String s) {
			try {
				limit = Integer.parseInt(s.trim());
			} catch (NumberFormatException e) {
				log.warn("Ignoring malformed {} in authorization details: {}", key, s);
				return defaultValue;
			}
		} else {
			// noinspection LoggingSimilarMessage
			log.warn("Ignoring {} of unexpected type {} in authorization details", key, value.getClass().getName());
			return defaultValue;
		}

		if (limit < 0) {
			log.warn("Ignoring negative {} in authorization details: {}", key, limit);
			return defaultValue;
		}

		return limit;
	}

	/**
	 * Serializes this policy as a {@code rateLimit} block, omitting disabled windows.
	 *
	 * @return the map representation, empty when nothing is enforced
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		if (perMinute > 0)
			map.put("perMinute", perMinute);
		if (perHour > 0)
			map.put("perHour", perHour);
		if (perDay > 0)
			map.put("perDay", perDay);

		return map;
	}

	@Override
	public String toString() {
		if (!isLimited())
			return "unlimited";

		return (perMinute > 0 ? perMinute : "-") + "/min, " +
				(perHour > 0 ? perHour : "-") + "/hour, " +
				(perDay > 0 ? perDay : "-") + "/day";
	}
}
