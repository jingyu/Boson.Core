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

package io.bosonnetwork.service.config;

import java.util.Map;

/**
 * A group of configuration settings that can be written back into a configuration document.
 * <p>
 * Implementations are immutable value types - typically records - that carry one self-contained
 * block of a service configuration, such as the endpoint a service listens on or how it reaches its
 * database. Each one owns both directions of its own mapping: a package-private {@code fromMap}
 * factory that reads and validates the block, and {@link #toMap()} that writes it back. Keeping the
 * pair together is what stops the same block from being parsed differently by different services.
 * <p>
 * {@link ServiceConfigMap} exposes the readers, and recognizes any {@code ConfigOptions} value
 * passed to {@link ServiceConfigMap#put(String, Object)}, so services never call {@code toMap()}
 * directly.
 *
 * @see ServiceConfigMap
 */
public interface ConfigOptions {
	/**
	 * Serializes these options as a configuration block, omitting entries that are at their default.
	 * <p>
	 * The result must be accepted by the corresponding {@code fromMap} factory and yield an equal
	 * value, so that a configuration document survives a round trip. An empty result means every
	 * setting is at its default; {@link ServiceConfigMap#put(String, Object)} drops the key entirely
	 * in that case rather than writing an empty block.
	 *
	 * @return a new, ordered map representing these options
	 */
	Map<String, Object> toMap();
}