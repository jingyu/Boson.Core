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

package io.bosonnetwork.service;

/**
 * Enum representing the authorization roles assigned to an authenticated principal.
 * <p>
 * A role describes <em>what kind of principal</em> is authenticated, derived on the server
 * from the resolved client identity. Roles are populated into the Vert.x
 * {@code User.authorizations()} (as {@code RoleBasedAuthorization}s) and enforced by route
 * {@code AuthorizationHandler}s. They are a server-internal vocabulary, distinct from the
 * over-the-wire {@link AccessScope} token scope values.
 */
public enum Role {
	/**
	 * Role for a standard client principal (a user or one of the user's devices). Grants access to
	 * non-administrative client APIs.
	 */
	CLIENT("client"),
	/**
	 * Role for an administrative user. Granted only when the resolved {@link ClientUser} reports
	 * {@link ClientUser#isAdmin()}; grants access to administrative APIs.
	 */
	ADMIN("admin"),
	/**
	 * Role for a federation peer (a super node or a federated service). Grants access to
	 * inter-node / inter-service federation APIs.
	 */
	FEDERATION("federation");

	private final String value;

	Role(String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return value;
	}
}
