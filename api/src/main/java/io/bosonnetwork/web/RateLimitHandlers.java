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

import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routing-layer glue shared by every service that enforces {@link RateLimiter} limits: resolving the
 * client address, charging a request exactly once across reroutes, and rendering a refusal.
 * <p>
 * These are separate from {@link RateLimiter} because they are about HTTP, not about tokens - a
 * service can use the limiter without a router - and separate from each service because getting any
 * of the three subtly wrong is silent. In particular {@link #chargeOnce} exists because
 * {@link RoutingContext#reroute(String)} restarts routing from the top of the router, so a handler
 * mounted on {@code router.route()} runs again on every pass and would charge a single client
 * request several times over.
 */
public final class RateLimitHandlers {
	/**
	 * Context key marking a request as already charged. Namespaced so it cannot collide with a
	 * service's own context data.
	 */
	public static final String LIMITS_CHARGED = "io.bosonnetwork.web.limitsCharged";

	/** Context key holding the resolved token cost of the current request. */
	public static final String REQUEST_COST = "io.bosonnetwork.web.requestCost";

	private static final Logger log = LoggerFactory.getLogger(RateLimitHandlers.class);

	private RateLimitHandlers() {
	}

	/**
	 * Resolves the client address used for rate limiting.
	 * <p>
	 * {@code X-Forwarded-For} is only consulted when the operator has declared the service to be
	 * behind a trusted proxy; trusting it unconditionally would let any client pick its own bucket
	 * simply by setting a header, which is strictly worse than having no per-address limit at all
	 * because it looks like protection.
	 *
	 * @param ctx               the routing context
	 * @param trustProxyHeaders whether the operator has declared a trusted proxy in front
	 * @return the client address, or {@code null} when it cannot be determined
	 */
	public static @Nullable String clientAddress(RoutingContext ctx, boolean trustProxyHeaders) {
		if (trustProxyHeaders) {
			String forwarded = ctx.request().getHeader("X-Forwarded-For");
			if (forwarded != null && !forwarded.isBlank()) {
				// Left-most entry is the originating client; the rest are proxies in the chain.
				int comma = forwarded.indexOf(',');
				return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
			}
		}

		SocketAddress address = ctx.request().remoteAddress();
		return address != null ? address.hostAddress() : null;
	}

	/**
	 * Marks the request as charged, returning whether this is the first time.
	 * <p>
	 * A limit is per client request, not per routing pass, so a handler on {@code router.route()}
	 * must wave through every pass after the first. Callers that reroute - to serve a static file, to
	 * hand off to an internal upload route - would otherwise be billed once per hop.
	 *
	 * @param ctx the routing context
	 * @return {@code true} if the caller should charge the request, {@code false} if it already has
	 */
	public static boolean chargeOnce(RoutingContext ctx) {
		if (ctx.get(LIMITS_CHARGED) != null)
			return false;

		ctx.put(LIMITS_CHARGED, Boolean.TRUE);
		return true;
	}

	/**
	 * The token cost tagged onto the current request, defaulting to one.
	 *
	 * @param ctx the routing context
	 * @return the cost in tokens
	 */
	public static long requestCost(RoutingContext ctx) {
		Number cost = ctx.get(REQUEST_COST);
		return cost != null ? cost.longValue() : 1;
	}

	/**
	 * Tags the current request with what it is worth in rate limit tokens, so that an expensive
	 * request is not billed at the same rate as a cheap one.
	 *
	 * @param ctx  the routing context
	 * @param cost the cost in tokens
	 */
	public static void setRequestCost(RoutingContext ctx, int cost) {
		ctx.put(REQUEST_COST, cost);
	}

	/**
	 * Fails the request with 429 and the headers a client needs to back off correctly.
	 * <p>
	 * The failure is routed through {@link RoutingContext#fail(Throwable)} rather than written
	 * directly, so that the service's own failure handler renders it in the same shape as every other
	 * error and a client does not have to special-case throttling.
	 *
	 * @param ctx      the routing context
	 * @param decision the refusing decision
	 * @param scope    a human-readable description of which limit refused, for the log
	 */
	public static void rejectRateLimited(RoutingContext ctx, RateLimiter.Decision decision, String scope) {
		log.debug("Rate limit exceeded ({}) for {}, retry after {}s", scope, ctx.normalizedPath(),
				decision.retryAfterSeconds());

		ctx.response()
				.putHeader("Retry-After", Long.toString(decision.retryAfterSeconds()))
				.putHeader("X-RateLimit-Remaining", "0");
		ctx.fail(new HttpException(429, "Rate limit exceeded"));
	}

	/**
	 * Fails the request with 503 and a short retry hint, for work refused because a concurrency cap
	 * is full rather than because the caller exceeded a budget.
	 *
	 * @param ctx     the routing context
	 * @param message the failure message
	 */
	public static void rejectBusy(RoutingContext ctx, String message) {
		ctx.response().putHeader("Retry-After", "1");
		ctx.fail(new HttpException(503, message));
	}
}