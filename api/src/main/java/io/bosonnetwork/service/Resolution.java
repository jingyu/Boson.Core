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

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * What {@link FederationContext#resolve(io.bosonnetwork.Id) resolving} a peer found: the service, if its
 * announcement could be found and verified, and whether its node is admitted.
 * <p>
 * The service is absent exactly when the reason is {@link Admission.Reason#UNKNOWN_PEER UNKNOWN_PEER},
 * {@link Admission.Reason#UNVERIFIED_PEER UNVERIFIED_PEER} or {@link Admission.Reason#DISABLED DISABLED}.
 * Otherwise it is present whatever the decision, so that a caller refused can still say which node
 * refused it.
 *
 * @param service   the verified service, or {@code null}
 * @param admission whether the service's node is admitted
 */
public record Resolution(@Nullable FederatedService service, Admission admission) {
	/**
	 * Creates a resolution.
	 *
	 * @param service   the verified service, or {@code null}
	 * @param admission whether the service's node is admitted
	 * @throws IllegalArgumentException if the service is absent for a reason that needs one, or present
	 *                                  for one that doesn't
	 */
	public Resolution {
		Objects.requireNonNull(admission, "admission");
		if ((service == null) != isWithoutService(admission.reason()))
			throw new IllegalArgumentException("Service " + (service == null ? "missing" : "unexpected") +
					" for " + admission.reason());
	}

	private static boolean isWithoutService(Admission.Reason reason) {
		return switch (reason) {
			case UNKNOWN_PEER, UNVERIFIED_PEER, DISABLED -> true;
			default -> false;
		};
	}

	/**
	 * Returns the verified service.
	 *
	 * @return an {@link Optional} with the service, or empty if none was found or verified
	 */
	public Optional<FederatedService> findService() {
		return Optional.ofNullable(service);
	}

	/**
	 * Tells whether the peer was found and its node is admitted, so that traffic may be exchanged.
	 *
	 * @return {@code true} if there is a service and the admission allows it
	 */
	public boolean allowed() {
		return service != null && admission.allowed();
	}
}
