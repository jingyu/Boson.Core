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

package io.bosonnetwork.database;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Validation helpers for SQL identifiers (column names, bind-parameter names, schema names).
 * <p>
 * These identifiers are interpolated directly into SQL/templates (only bound <em>values</em> are
 * parameterized), so they must be restricted to safe characters to prevent SQL injection. Used by
 * the query builders ({@link Filter}, {@link Ordering}) and by schema/configuration handling.
 */
public final class SqlSafety {
	// Letters, digits and underscore; optionally a single qualifying dot (e.g. "table.column").
	private static final Pattern COLUMN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?$");
	// Bind-parameter token: letters, digits and underscore.
	private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
	// Schema name: a lowercase letter followed by up to 31 lowercase letters, digits or underscores.
	private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

	private SqlSafety() {
	}

	/**
	 * Validates a SQL column name (optionally qualified, e.g. {@code table.column}) and returns it,
	 * so callers can validate and assign in one expression.
	 *
	 * @param column the column name; must not be null
	 * @return the validated column name
	 * @throws IllegalArgumentException if the column name is null or not a safe identifier
	 */
	public static String validateColumn(String column) {
		if (column == null || !COLUMN.matcher(column).matches())
			throw new IllegalArgumentException("Invalid SQL column name: " + column);
		return column;
	}

	/**
	 * Validates a bind-parameter name (the token used inside a {@code #{...}} placeholder) and
	 * returns it, so callers can validate and assign in one expression.
	 *
	 * @param paramName the parameter name; must not be null
	 * @return the validated parameter name
	 * @throws IllegalArgumentException if the parameter name is null or not a safe identifier
	 */
	public static String validateParamName(String paramName) {
		if (paramName == null || !PARAM_NAME.matcher(paramName).matches())
			throw new IllegalArgumentException("Invalid SQL parameter name: " + paramName);
		return paramName;
	}

	/**
	 * Validates and normalizes an optional SQL schema name. A {@code null} or empty name is treated
	 * as "no schema" and mapped to {@code null}; any other value must be a valid schema identifier
	 * and is returned unchanged.
	 *
	 * @param schema the schema name, may be {@code null} or empty
	 * @return the validated schema name, or {@code null} if the input was null or empty
	 * @throws IllegalArgumentException if a non-empty schema name is not a safe identifier
	 */
	public static @Nullable String validateSchema(@Nullable String schema) {
		if (schema == null || schema.isEmpty())
			return null;

		if (!SCHEMA_NAME.matcher(schema).matches())
			throw new IllegalArgumentException("Invalid schema name: " + schema);

		return schema;
	}
}