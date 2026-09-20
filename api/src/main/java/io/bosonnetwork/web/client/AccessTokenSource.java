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

package io.bosonnetwork.web.client;

import java.time.Instant;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;

/**
 * Supplies the access tokens a client authenticates its requests with, and says what to do when one
 * is refused.
 * <p>
 * A client asks for a token before each request and sends it as a bearer token. If the answer is
 * {@code 401}, it tells the source, which decides whether another token could do better: a token the
 * source issues itself may be retried once with a corrected clock, while a token the server issued is
 * the server's to judge, and its refusal is final.
 */
public interface AccessTokenSource {
	/**
	 * Returns a token to authenticate the next request with. Implementations are expected to cache
	 * one and renew it before it expires, so that this costs nothing per request.
	 *
	 * @return a future completing with the token
	 */
	Future<String> token();

	/**
	 * Tells the source that a request carrying one of its tokens was refused with {@code 401}.
	 *
	 * @param token      the token that was refused
	 * @param serverDate the server's clock when it answered, read from the {@code Date} header of the
	 *                   refusal ({@link io.bosonnetwork.web.HttpDate#parse}), or {@code null} if the
	 *                   answer carried no readable date
	 * @return {@code true} if a new token could succeed where this one failed, in which case the
	 *         caller repeats the request once with a freshly requested token; {@code false} if the
	 *         refusal is final
	 */
	boolean rejected(String token, @Nullable Instant serverDate);
}
