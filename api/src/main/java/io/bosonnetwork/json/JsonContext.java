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

package io.bosonnetwork.json;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.cfg.ContextAttributes;

/**
 * A context object for customizing JSON serialization and deserialization.
 * <p>
 * This class extends {@link Impl} and provides a convenient, immutable,
 * and type-safe way to manage shared (global) and per-call (thread-local) attributes for Jackson operations.
 * <p>
 * Use static factory methods such as {@link #empty()} and {@link #shared(Object, Object)}
 * to construct a context instance with the desired attributes. Use instance methods to query or derive
 * new contexts with added/removed attributes.
 * <p>
 * Shared attributes are visible to all serialization/deserialization operations that use this context,
 * while per-call attributes are specific to a single operation or thread.
 */
public class JsonContext extends ContextAttributes.Impl {
	private static final long serialVersionUID = -385397772721358918L;

	/**
	 * Constructs a new {@code JsonContext} with the specified shared attributes and an empty per-call attribute map.
	 *
	 * @param shared the shared (global) attribute map
	 */
	protected JsonContext(Map<?, ?> shared) {
		super(shared, new HashMap<>());
	}

	/**
	 * Constructs a new {@code JsonContext} with the specified shared and per-call (non-shared) attributes.
	 *
	 * @param shared    the shared (global) attribute map
	 * @param nonShared the per-call (thread-local) attribute map
	 */
	protected JsonContext(Map<?, ?> shared, Map<Object, Object> nonShared) {
		super(shared, nonShared);
	}

	/**
	 * Returns an empty {@code JsonContext} with no shared or per-call attributes.
	 *
	 * @return an empty context
	 */
	public static JsonContext empty() {
		return new JsonContext(Map.of());
	}

	/**
	 * Returns a per-call {@code JsonContext} with the given attribute key and value.
	 * The attribute will only be visible to the current serialization/deserialization operation.
	 *
	 * @param key   the attribute key
	 * @param value the attribute value
	 * @return a per-call context with the specified attribute
	 */
	public static JsonContext perCall(Object key, Object value) {
		Map<Object, Object> m = new HashMap<>();
		m.put(key, value);
		return new JsonContext(Map.of(), m);
	}

	/**
	 * Returns a per-call {@code JsonContext} with two attribute key/value pairs.
	 *
	 * @param key1   the first attribute key
	 * @param value1 the first attribute value
	 * @param key2   the second attribute key
	 * @param value2 the second attribute value
	 * @return a per-call context with the specified attributes
	 */
	static JsonContext perCall(Object key1, Object value1, Object key2, Object value2) {
		Map<Object, Object> m = new HashMap<>();
		m.put(key1, value1);
		m.put(key2, value2);
		return new JsonContext(Map.of(), m);
	}

	/**
	 * Returns a per-call {@code JsonContext} with three attribute key/value pairs.
	 *
	 * @param key1   the first attribute key
	 * @param value1 the first attribute value
	 * @param key2   the second attribute key
	 * @param value2 the second attribute value
	 * @param key3   the third attribute key
	 * @param value3 the third attribute value
	 * @return a per-call context with the specified attributes
	 */
	static JsonContext perCall(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
		Map<Object, Object> m = new HashMap<>();
		m.put(key1, value1);
		m.put(key2, value2);
		m.put(key3, value3);
		return new JsonContext(Map.of(), m);
	}

	/**
	 * Returns a new {@code JsonContext} with the given shared attribute key and value.
	 * Shared attributes are visible to all serialization/deserialization operations using this context.
	 *
	 * @param key   the shared attribute key
	 * @param value the shared attribute value
	 * @return a context with the specified shared attribute
	 */
	public static JsonContext shared(Object key, Object value) {
		return new JsonContext(Map.of(key, value));
	}

	/**
	 * Returns a new {@code JsonContext} with two shared attribute key/value pairs.
	 *
	 * @param key1   the first shared attribute key
	 * @param value1 the first shared attribute value
	 * @param key2   the second shared attribute key
	 * @param value2 the second shared attribute value
	 * @return a context with the specified shared attributes
	 */
	public static JsonContext shared(Object key1, Object value1, Object key2, Object value2) {
		return new JsonContext(Map.of(key1, value1, key2, value2));
	}

	/**
	 * Returns a new {@code JsonContext} with three shared attribute key/value pairs.
	 *
	 * @param key1   the first shared attribute key
	 * @param value1 the first shared attribute value
	 * @param key2   the second shared attribute key
	 * @param value2 the second shared attribute value
	 * @param key3   the third shared attribute key
	 * @param value3 the third shared attribute value
	 * @return a context with the specified shared attributes
	 */
	public static JsonContext shared(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
		return new JsonContext(Map.of(key1, value1, key2, value2, key3, value3));
	}

	/**
	 * Returns the value of the attribute for the specified key.
	 * If the key is {@code JsonContext.class} or {@code ContextAttributes.class}, returns this context instance.
	 * Otherwise, delegates to the parent implementation.
	 *
	 * @param key the attribute key
	 * @return the attribute value, or {@code null} if not present
	 */
	@Override
	public Object getAttribute(Object key) {
		if (key == JsonContext.class || key == ContextAttributes.class)
			return this;

		return super.getAttribute(key);
	}

	/**
	 * Returns {@code true} if this context contains no shared or per-call attributes.
	 *
	 * @return {@code true} if empty; {@code false} otherwise
	 */
	public boolean isEmpty() {
		return _shared.isEmpty() && _nonShared.isEmpty();
	}

	/**
	 * Returns a new {@code JsonContext} with the given shared attribute key and value, replacing any previous value.
	 * Shared attributes are visible to all operations using this context.
	 *
	 * @param key   the shared attribute key
	 * @param value the shared attribute value
	 * @return a new context with the updated shared attribute
	 */
	@Override
	public JsonContext withSharedAttribute(Object key, Object value) {
		if (_shared.isEmpty()) {
			return new JsonContext(Map.of(key, value));
		} else {
			Map<Object, Object> newShared = new HashMap<>(_shared);
			newShared.put(key, value);
			return new JsonContext(newShared);
		}
	}

	/**
	 * Returns a new {@code JsonContext} with the specified shared attributes, replacing all previous shared attributes.
	 *
	 * @param attributes the shared attributes to set (maybe {@code null} or empty)
	 * @return a new context with the specified shared attributes
	 */
	@Override
	public JsonContext withSharedAttributes(Map<?, ?> attributes) {
		return new JsonContext(attributes == null || attributes.isEmpty() ? Map.of() : attributes);
	}

	/**
	 * Returns a new {@code JsonContext} without the specified shared attribute key.
	 * If the key does not exist, returns this context instance.
	 *
	 * @param key the shared attribute key to remove
	 * @return a new context without the specified shared attribute
	 */
	@Override
	public JsonContext withoutSharedAttribute(Object key) {
		if (_shared.isEmpty() || !_shared.containsKey(key))
			return this;

		if (_shared.size() == 1)
			return empty();

		Map<Object, Object> newShared = new HashMap<>(_shared);
		newShared.remove(key);
		return new JsonContext(newShared);
	}
}