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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.database.SqlSafety;
import io.bosonnetwork.utils.ConfigMap;

/**
 * How a service reaches its persistence database.
 *
 * @param uri      the connection URI
 * @param poolSize the connection pool size, or {@code 0} to use the driver default
 * @param schema   the schema name, or {@code null} for the default schema; ignored by drivers that
 *                 have no notion of a schema
 */
public record DatabaseOptions(String uri, int poolSize, @Nullable String schema) implements ConfigOptions {
	/**
	 * Canonical constructor.
	 *
	 * @param uri      the connection URI
	 * @param poolSize the connection pool size, or {@code 0} for the driver default
	 * @param schema   the schema name, or {@code null} for the default schema
	 * @throws NullPointerException     if {@code uri} is null
	 * @throws IllegalArgumentException if {@code poolSize} is negative or {@code schema} is not a
	 *                                  safe identifier
	 */
	public DatabaseOptions {
		Objects.requireNonNull(uri, "uri");
		if (poolSize < 0)
			throw new IllegalArgumentException("Invalid poolSize: " + poolSize);
		schema = SqlSafety.validateSchema(schema);
	}

	static DatabaseOptions fromMap(@Nullable ConfigMap cm, String defaultUri, @Nullable Predicate<String> supportCheck) {
		Objects.requireNonNull(defaultUri, "defaultUri");

		String uri = cm == null || cm.isEmpty() ? defaultUri :
				Objects.requireNonNullElse(cm.getString("uri", defaultUri), defaultUri);

		// Check the URI whether it came from the document or from the service's own default, so that
		// a service cannot ship a default its driver does not actually support.
		if (supportCheck != null && !supportCheck.test(uri))
			throw new IllegalArgumentException("Database URI is not supported: " + uri);

		if (cm == null || cm.isEmpty())
			return new DatabaseOptions(uri, 0, null);

		return new DatabaseOptions(uri, cm.getNonNegativeInteger("poolSize", 0),
				cm.getString("schema", null));
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("uri", uri);
		if (poolSize != 0)
			map.put("poolSize", poolSize);
		if (schema != null)
			map.put("schema", schema);
		return map;
	}
}