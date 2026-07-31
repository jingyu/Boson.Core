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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The four rate limit windows a caller is subject to, enforced together.
 * <p>
 * A window of {@code 0} disables that window; a policy with all four at zero is
 * {@linkplain #isLimited() unlimited}. The windows are deliberately charged together rather than
 * being alternatives: {@code perDay} sets the sustained rate, {@code perHour} the medium-term rate,
 * and {@code perSecond}/{@code perMinute} are burst allowances, so raising a burst does not increase
 * what a caller may consume over a day.
 *
 * <h2>Where the numbers come from</h2>
 * This is a plain value type for the {@link RateLimiter} API and knows nothing about configuration
 * files. Reading a policy out of a configuration document, and writing one back, belong to the
 * service configuration layer; a configured policy is taken as a whole value rather than assembled
 * window by window from a file and a set of defaults, so that what an operator writes is exactly
 * what is enforced.
 * <p>
 * {@link #override} is the deliberate exception, and works the other way round: it merges the
 * {@code rateLimit} block carried in a caller's authorization details into this policy window by
 * window, leaving windows the plan does not name at their configured value. That asymmetry is
 * intentional - deployment configuration and per-caller plan data are different sources with
 * different failure modes. A bad value in a configuration file is an operator typo, and the operator
 * is present to fix it, so parsing rejects it. Plan data arrives from a database at request time
 * with nobody watching, so a bad value falls back to the configured window and is logged - never
 * read as "unlimited", so that a typo in plan data cannot raise a limit.
 *
 * @param perSecond the per-second window, or {@code 0} to disable it
 * @param perMinute the per-minute window, or {@code 0} to disable it
 * @param perHour   the per-hour window, or {@code 0} to disable it
 * @param perDay    the per-day window, or {@code 0} to disable it
 */
public record RateLimitPolicy(int perSecond, int perMinute, int perHour, int perDay) {
	/** A policy that enforces nothing. */
	public static final RateLimitPolicy UNLIMITED = new RateLimitPolicy(0, 0, 0, 0);

	/** The key holding a policy, wherever one appears - in configuration or in plan data. */
	public static final String CONFIG_KEY = "rateLimit";

	private static final Logger log = LoggerFactory.getLogger(RateLimitPolicy.class);

	/**
	 * Canonical constructor.
	 *
	 * @param perSecond the per-second window, or {@code 0} to disable it
	 * @param perMinute the per-minute window, or {@code 0} to disable it
	 * @param perHour   the per-hour window, or {@code 0} to disable it
	 * @param perDay    the per-day window, or {@code 0} to disable it
	 * @throws IllegalArgumentException if any window is negative
	 */
	public RateLimitPolicy {
		if (perSecond < 0 || perMinute < 0 || perHour < 0 || perDay < 0)
			throw new IllegalArgumentException("Rate limit windows must be non-negative: " +
					perSecond + "/" + perMinute + "/" + perHour + "/" + perDay);
	}

	/**
	 * Creates a policy, returning the shared {@link #UNLIMITED} instance when no window is set.
	 *
	 * @param perSecond the per-second window, or {@code 0} to disable it
	 * @param perMinute the per-minute window, or {@code 0} to disable it
	 * @param perHour   the per-hour window, or {@code 0} to disable it
	 * @param perDay    the per-day window, or {@code 0} to disable it
	 * @return the policy
	 * @throws IllegalArgumentException if any window is negative
	 */
	public static RateLimitPolicy of(int perSecond, int perMinute, int perHour, int perDay) {
		if (perSecond != 0 || perMinute != 0 || perHour != 0 || perDay != 0)
			return new RateLimitPolicy(perSecond, perMinute, perHour, perDay);
		else
			return UNLIMITED;
	}

	/**
	 * Creates a policy with only a per-second window, as used by the per-service scope.
	 * @param perSecond the per-second window, or {@code 0} to disable the limit
	 * @return the policy
	 */
	public static RateLimitPolicy perSecond(int perSecond) {
		return new RateLimitPolicy(perSecond, 0, 0, 0);
	}

	/**
	 * Creates a policy with only a per-minute window, as used by the per-address scope.
	 *
	 * @param perMinute the per-minute window, or {@code 0} to disable the limit
	 * @return the policy
	 */
	public static RateLimitPolicy perMinute(int perMinute) {
		return new RateLimitPolicy(0, perMinute, 0, 0);
	}

	/**
	 * Returns a copy of this policy with the per-second window replaced.
	 *
	 * @param perSecond the per-second window, or {@code 0} to disable it
	 * @return the resulting policy
	 */
	public RateLimitPolicy withPerSecond(int perSecond) {
		return RateLimitPolicy.of(perSecond, perMinute, perHour, perDay);
	}

	/**
	 * Returns a copy of this policy with the per-minute window replaced.
	 *
	 * @param perMinute the per-minute window, or {@code 0} to disable it
	 * @return the resulting policy
	 */
	public RateLimitPolicy withPerMinute(int perMinute) {
		return RateLimitPolicy.of(perSecond, perMinute, perHour, perDay);
	}

	/**
	 * Returns a copy of this policy with the per-hour window replaced.
	 *
	 * @param perHour the per-hour window, or {@code 0} to disable it
	 * @return the resulting policy
	 */
	public RateLimitPolicy withPerHour(int perHour) {
		return RateLimitPolicy.of(perSecond, perMinute, perHour, perDay);
	}

	/**
	 * Returns a copy of this policy with the per-day window replaced.
	 *
	 * @param perDay the per-day window, or {@code 0} to disable it
	 * @return the resulting policy
	 */
	public RateLimitPolicy withPerDay(int perDay) {
		return RateLimitPolicy.of(perSecond, perMinute, perHour, perDay);
	}

	/**
	 * Whether this policy enforces anything at all.
	 *
	 * @return {@code true} if at least one window is set
	 */
	public boolean isLimited() {
		return perSecond > 0 || perMinute > 0 || perHour > 0 || perDay > 0;
	}

	/**
	 * Applies the {@code rateLimit} overrides carried in a caller's authorization details.
	 * <p>
	 * This is how a subscription plan raises a caller above the service default. Unlike a configured
	 * policy, which is taken as a whole value, plan data is merged window by window: a window the
	 * plan does not name keeps this policy's value. Anything unusable - a missing block, the wrong
	 * type, a negative number, an unparseable string - leaves the corresponding window at this
	 * policy's value and is logged, because the alternative (treating it as absent-means-unlimited)
	 * would turn a typo in plan data into an unmetered caller.
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
				limit(rateLimit, "perSecond", perSecond),
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

	@Override
	public String toString() {
		if (!isLimited())
			return "unlimited";

		List<String> limits = new ArrayList<>();
		if (perSecond > 0)
			limits.add(perSecond + "/sec");
		if (perMinute > 0)
			limits.add(perMinute + "/min");
		if (perHour > 0)
			limits.add(perHour + "/hour");
		if (perDay > 0)
			limits.add(perDay + "/day");
		return String.join(", ", limits);
	}
}
