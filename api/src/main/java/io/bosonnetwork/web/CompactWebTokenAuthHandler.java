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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.audit.Marker;
import io.vertx.ext.auth.audit.SecurityAudit;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.impl.HTTPAuthorizationHandler;
import io.vertx.ext.web.impl.RoutingContextInternal;

/**
 * An auth handler that provides Compact Web Token (CWT) authentication support.
 * <p>
 * This handler validates the CWT format, signature and optionally verifies that
 * the authenticated user has the required scopes.
 * </p>
 */
public class CompactWebTokenAuthHandler extends HTTPAuthorizationHandler<CompactWebTokenAuth> implements AuthenticationHandler {
	private final List<String> scopes;
	private String delimiter;

	private CompactWebTokenAuthHandler(CompactWebTokenAuth authProvider) {
		super(authProvider, Type.BEARER, null);
		this.scopes = new ArrayList<>();
		this.delimiter  = " ";
	}

	private CompactWebTokenAuthHandler(CompactWebTokenAuthHandler base, List<String> scopes, String delimiter) {
		super(base.authProvider, Type.BEARER, null);
		Objects.requireNonNull(scopes, "scopes cannot be null");
		this.scopes = scopes;
		Objects.requireNonNull(delimiter, "delimiter cannot be null");
		this.delimiter = delimiter;
	}

	/**
	 * Create a new CompactWebTokenAuthHandler with the given auth provider.
	 * 
	 * @param authProvider the CompactWebTokenAuth provider to use for authentication
	 * @return the auth handler
	 */
	public static CompactWebTokenAuthHandler create(CompactWebTokenAuth authProvider) {
		return new CompactWebTokenAuthHandler(authProvider);
	}

	/**
	 * Authenticates the user based on the provided token.
	 * 
	 * @param context the routing context
	 * @return a future containing the authenticated user
	 */
	@Override
	public Future<User> authenticate(RoutingContext context) {
		return parseAuthorization(context).compose(token -> {
			int segments = 0;
			for (int i = 0; i < token.length(); i++) {
				char c = token.charAt(i);
				if (c == '.') {
					if (++segments == 2)
						return Future.failedFuture(new HttpException(400, "Too many segments in token"));
					continue;
				}
				if (Character.isLetterOrDigit(c) || c == '-' || c == '_')
					continue;

				// invalid character
				return Future.failedFuture(new HttpException(400, "Invalid character in token: " + (int) c));
			}

			final TokenCredentials credentials = new TokenCredentials(token);
			final SecurityAudit audit = ((RoutingContextInternal) context).securityAudit();
			audit.credentials(credentials);

			return authProvider.authenticate(credentials)
					.andThen(op -> audit.audit(Marker.AUTHENTICATION, op.succeeded()))
					.recover(err -> Future.failedFuture(new HttpException(401, err.getMessage())));
		});
	}

	@SuppressWarnings("unchecked")
	private List<String> getScopesOrSearchMetadata(RoutingContext ctx) {
		if (!scopes.isEmpty())
			return scopes;

		final Route currentRoute = ctx.currentRoute();
		if (currentRoute == null)
			return Collections.emptyList();

		final Object value = currentRoute.metadata().get("scopes");
		if (value == null)
			return Collections.emptyList();

		if (value instanceof List<?> l)
			return (List<String>) l;
		else if (value instanceof String s) {
			return Collections.singletonList(s);
		}

		throw new IllegalStateException("Invalid type for scopes metadata: " + value.getClass().getName());
	}

	/**
	 * The default behavior for post-authentication.
	 * Verifies that the user has the required scopes.
	 * 
	 * @param ctx the routing context
	 */
	@Override
	public void postAuthentication(RoutingContext ctx) {
		final User user = ctx.user();
		if (user == null) {
			// bad state
			ctx.fail(403, new HttpException(403, "no user in the context"));
			return;
		}

		// the user is authenticated, however, the user may not have all the required scopes
		final List<String> scopes = getScopesOrSearchMetadata(ctx);

		if (!scopes.isEmpty()) {
			final String scope = user.principal().getString("scope");
			if (scope == null || scope.isEmpty()) {
				ctx.fail(new HttpException(403, "Invalid authorization token: scope claim is required"));
				return;
			}

			// Use a Set for faster lookups
			Set<String> target = new HashSet<>();
			Collections.addAll(target, scope.split(delimiter));

			if (target.isEmpty()) {
				ctx.fail(403, new HttpException(403, "Invalid authorization token: scope undefined"));
				return;
			}

			for (String scp : scopes) {
				if (!target.contains(scp)) {
					ctx.fail(403, new HttpException(403, "Invalid authorization token: mismatched scope"));
					return;
				}
			}
		}

		ctx.next();
	}

	/**
	 * Return a new handler with the specified required scope.
	 * 
	 * @param scope the required scope
	 * @return a new handler instance
	 */
	public CompactWebTokenAuthHandler withScope(String scope) {
		Objects.requireNonNull(scope, "scope cannot be null");
		List<String> updatedScopes = new ArrayList<>(this.scopes);
		updatedScopes.add(scope);
		return new CompactWebTokenAuthHandler(this, updatedScopes, delimiter);
	}

	/**
	 * Return a new handler with the specified required scopes.
	 * 
	 * @param scopes the list of required scopes
	 * @return a new handler instance
	 */
	public CompactWebTokenAuthHandler withScopes(List<String> scopes) {
		Objects.requireNonNull(scopes, "scopes cannot be null");
		return new CompactWebTokenAuthHandler(this, scopes, delimiter);
	}

	/**
	 * Sets the delimiter used to split the scope claim string.
	 * Default is space " ".
	 * 
	 * @param delimiter the delimiter string
	 * @return self
	 */
	public CompactWebTokenAuthHandler scopeDelimiter(String delimiter) {
		Objects.requireNonNull(delimiter, "delimiter cannot be null");
		this.delimiter = delimiter;
		return this;
	}
}