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

import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

/**
 * What to do with the body of an upload that has been refused.
 * <p>
 * A service that rejects an upload answers while the client is still sending it, which leaves the
 * rest of the body on the connection. It cannot simply be ignored: a keep-alive connection does not
 * move on to its next request until the current request's body has ended, and a request left paused
 * never ends, so the connection sits dead until the idle timeout closes it. Reading the remainder is
 * also what lets the client see the answer, since a Vert.x client does not surface the response
 * while it is still writing its request.
 * <p>
 * Reading it unconditionally is not safe either - the client may have gigabytes still to send - so
 * how much is read depends on what the client declared. Use in two steps, because the decision has
 * to be made before the response is written and acted on after:
 * <pre>
 *     long allowance = RequestBodies.prepareDiscard(ctx, readable);
 *     ctx.fail(error);
 *     RequestBodies.discard(ctx, allowance);
 * </pre>
 */
public final class RequestBodies {
	/**
	 * How much of an unwanted body to read when the client has not declared a length the service
	 * would have read anyway. Mirrors Tomcat's {@code maxSwallowSize} default.
	 */
	private static final long MAX_DISCARD_SIZE = 2 * 1024 * 1024;

	/**
	 * How long to leave a connection open after giving up on its body. Closing a socket with data
	 * still arriving on it resets the connection, and a reset can discard what the client has not
	 * read yet, including the error response just written to it. Waiting costs nothing - nothing is
	 * being read during it - and gives a client that has finished sending time to read the answer.
	 */
	private static final long DISCARD_LINGER = 1000;

	private RequestBodies() {
	}

	/**
	 * Decides how much of a rejected request body is worth reading, and warns the client when that
	 * is not all of it.
	 * <p>
	 * A body whose {@code Content-Length} is no more than {@code readable} is read to the end,
	 * however the upload was rejected: the service was willing to read that many bytes a moment ago,
	 * and reading them leaves the connection reusable. A body that declares more than that, or
	 * declares nothing at all and so could go on indefinitely, is read only up to a fixed cap and
	 * its connection is then dropped - so the response is marked {@code Connection: close}, or the
	 * client would send its next request down a connection that is about to go and lose it.
	 * <p>
	 * Call this before the response is written, and {@link #discard(RoutingContext, long)} after.
	 *
	 * @param ctx      the routing context of the rejected request
	 * @param readable the largest body this service reads before it can know whether it is allowed
	 *                 to keep what is in it
	 * @return how many further bytes to read, or {@code -1} if the body has already ended
	 */
	public static long prepareDiscard(RoutingContext ctx, long readable) {
		HttpServerRequest request = ctx.request();
		if (request.isEnded())
			return -1;

		long declared;
		try {
			String header = request.getHeader("Content-Length");
			declared = header == null ? -1 : Long.parseLong(header.trim());
		} catch (NumberFormatException e) {
			declared = -1;
		}

		if (declared >= 0 && declared <= readable)
			return declared;

		ctx.response().putHeader("Connection", "close");
		return MAX_DISCARD_SIZE;
	}

	/**
	 * Reads and throws away what is left of a rejected request body, so that the request ends and
	 * its connection is released. Past {@code allowance} bytes the body is abandoned instead and the
	 * connection closed, once the client has had time to read the answer.
	 * <p>
	 * This is what Vert.x's own {@code BodyHandler} does with a body it has rejected: it keeps
	 * reading and drops the data.
	 *
	 * @param ctx       the routing context of the rejected request
	 * @param allowance how many bytes to read before giving up, as {@link #prepareDiscard} settled
	 */
	public static void discard(RoutingContext ctx, long allowance) {
		HttpServerRequest request = ctx.request();
		if (allowance < 0 || request.isEnded())
			return;

		AtomicLong budget = new AtomicLong(allowance);
		request.handler(buf -> {
			if (budget.addAndGet(-buf.length()) < 0) {
				request.pause();
				ctx.vertx().setTimer(DISCARD_LINGER, id -> request.connection().close());
			}
		});
		request.resume();
	}
}
