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

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

/**
 * Dates every answer by the node's clock, as RFC 9110 asks of a server that has one. Vert.x sends no
 * {@code Date} header of its own, so a service that wants one mounts this.
 * <p>
 * Clients that issue their own access tokens depend on it: a self-issued token is only as good as the
 * clock it is dated by, and when the node rejects one, this header is how the client learns that its
 * clock is off and by how much (see
 * {@link io.bosonnetwork.web.client.SelfIssuedAccessTokens}).
 * <p>
 * <strong>Mount it first, on a route of its own</strong>, ahead of the rate limiter and the
 * authentication handler:
 * <pre>{@code router.route().handler(ServerDateHandler.create());}</pre>
 * The answers that most need the date are the ones a later handler ends by itself - the 401 that
 * reveals the skew, and the 429s - and a handler mounted after them never runs for those. A route of
 * its own, rather than the first handler of an existing route, because Vert.x orders handlers by kind
 * within a route and refuses a plain handler ahead of a security-policy handler such as
 * {@code CorsHandler}.
 */
public final class ServerDateHandler implements Handler<RoutingContext> {
	private static final ServerDateHandler INSTANCE = new ServerDateHandler();

	private ServerDateHandler() {
	}

	/**
	 * Returns the handler.
	 *
	 * @return the handler; it holds no state, so one instance serves every route
	 */
	public static ServerDateHandler create() {
		return INSTANCE;
	}

	@Override
	public void handle(RoutingContext ctx) {
		// At headers end rather than now: the date is then the one the answer is actually sent with,
		// however long the request took, and a handler that set its own is left alone.
		ctx.addHeadersEndHandler(v -> {
			if (ctx.response().headers().get(HttpHeaders.DATE) == null)
				ctx.response().putHeader(HttpHeaders.DATE, HttpDate.current());
		});
		ctx.next();
	}
}
