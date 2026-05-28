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
 * Service provider interface for creating {@link BosonService} instances.
 * <p>
 * Implementations are discovered at runtime via the {@link java.util.ServiceLoader}
 * mechanism and keyed by their {@link #getType() type}, which lets the Boson super node
 * select and instantiate the configured layer2 services without referencing concrete
 * implementation class names. Providers register themselves through a
 * {@code META-INF/services/io.bosonnetwork.service.BosonServiceFactory} entry, or a
 * {@code provides io.bosonnetwork.service.BosonServiceFactory with ...} declaration when
 * running on the Java module path.
 */
public interface BosonServiceFactory {
	/**
	 * The unique identifier for the service type produced by this factory.
	 * <p>
	 * Must equal the {@link BosonService#getType() type} of the services it creates.
	 *
	 * @return the unique type identifier string.
	 */
	String getType();

	/**
	 * Creates a new, uninitialized {@link BosonService} instance.
	 * <p>
	 * The returned service is not yet initialized; the caller is responsible for invoking
	 * {@link BosonService#init(ServiceContext)} and {@link BosonService#start()}.
	 *
	 * @return a new {@link BosonService} instance.
	 */
	BosonService create();
}