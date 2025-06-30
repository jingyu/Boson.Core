/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.access;

import io.bosonnetwork.Id;

/**
 * Provides the access management capabilities. These capabilities include the access control
 * to the specified service, and also the detailed permissions.
 */
public interface AccessManager {
	/**
	 * Creates a default {@code AccessManager} implementation.
	 *
	 * @return the default {@code AccessManager} implementation.
	 */
	static AccessManager getDefault() {
		return new AccessManager() {
			@Override
			public boolean allow(Id subjectNode, String targetServiceId) {
				return true;
			}

			@Override
			public Permission getPermission(Id subjectNode, String targetServiceId) {
				return null;
			}
		};
	}

	/**
	 * Checks if the subject node allowed to access the service with the target service id.
	 *
	 * @param subjectNode the subject node id.
	 * @param targetServiceId the target service id.
	 * @return true if the subject node allowed to access the service, false otherwise.
	 */
	boolean allow(Id subjectNode, String targetServiceId);

	/**
	 * Gets the {@link Permission} of the subject node to the target service.
	 *
	 * @param subjectNode the subject node id.
	 * @param targetServiceId the target service id.
	 * @return the {@link Permission} object.
	 */
	Permission getPermission(Id subjectNode, String targetServiceId);
}