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

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SQL WHERE clause builder with safe parameter binding.
 * Supports AND / OR composition and multiple operators.
 */
public class Filter {
	/** A filter that represents no condition (always true). */
	public static final Filter NONE = new Filter();

	private Filter() {}

	/**
	 * Creates a filter from a raw SQL string.
	 * <p>
	 * <b>WARNING:</b> This method does not perform parameter binding or validation.
	 * Use with caution to avoid SQL injection vulnerabilities.
	 * </p>
	 *
	 * @param sql the raw SQL string
	 * @return a Filter containing the raw SQL
	 */
	public static Filter raw(String sql) {
		return new Raw(sql);
	}

	/**
	 * Creates an equality filter (column = #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the equality condition
	 */
	public static Filter eq(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, "=", paramName, value);
	}

	/**
	 * Creates an equality filter (column = #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the equality condition
	 */
	public static Filter eq(String column, Object value) {
		return eq(column, column, value);
	}

	/**
	 * Creates a non-equality filter (column &lt;&gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the non-equality condition
	 */
	public static Filter ne(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, "<>", paramName, value);
	}

	/**
	 * Creates a non-equality filter (column &lt;&gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the non-equality condition
	 */
	public static Filter ne(String column, Object value) {
		return ne(column, column, value);
	}

	/**
	 * Creates a less-than filter (column &lt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the less-than condition
	 */
	public static Filter lt(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, "<", paramName, value);
	}

	/**
	 * Creates a less-than filter (column &lt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the less-than condition
	 */
	public static Filter lt(String column, Object value) {
		return lt(column, column, value);
	}

	/**
	 * Creates a less-than-or-equal filter (column &lt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the less-than-or-equal condition
	 */
	public static Filter lte(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, "<=", paramName, value);
	}

	/**
	 * Creates a less-than-or-equal filter (column &lt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the less-than-or-equal condition
	 */
	public static Filter lte(String column, Object value) {
		return lte(column, column, value);
	}

	/**
	 * Creates a greater-than filter (column &gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than condition
	 */
	public static Filter gt(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, ">", paramName, value);
	}

	/**
	 * Creates a greater-than filter (column &gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than condition
	 */
	public static Filter gt(String column, Object value) {
		return gt(column, column, value);
	}

	/**
	 * Creates a greater-than-or-equal filter (column &gt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than-or-equal condition
	 */
	public static Filter gte(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, ">=", paramName, value);
	}

	/**
	 * Creates a greater-than-or-equal filter (column &gt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than-or-equal condition
	 */
	public static Filter gte(String column, Object value) {
		return gte(column, column, value);
	}

	/**
	 * Creates a LIKE filter (column LIKE #{paramName}).
	 *
	 * @param column    the database column name
	 * @param paramName the parameter name to bind
	 * @param value     the value to bind
	 * @return a Filter representing the <code>LIKE</code> condition
	 */
	public static Filter like(String column, String paramName, Object value) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(paramName);
		validateColumn(column);
		return new Binary(column, "LIKE", paramName, value);
	}

	/**
	 * Creates a LIKE filter (column LIKE #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the <code>LIKE</code> condition
	 */
	public static Filter like(String column, Object value) {
		return like(column, column, value);
	}

	/**
	 * Creates a filter checking if the column is NULL.
	 *
	 * @param column the database column name
	 * @return a Filter representing the IS NULL condition
	 */
	public static Filter isNull(String column) {
		Objects.requireNonNull(column);
		validateColumn(column);
		return new Unary(column, "IS NULL");
	}

	/**
	 * Creates a filter checking if the column is NOT NULL.
	 *
	 * @param column the database column name
	 * @return a Filter representing the IS NOT NULL condition
	 */
	public static Filter isNotNull(String column) {
		Objects.requireNonNull(column);
		validateColumn(column);
		return new Unary(column, "IS NOT NULL");
	}

	/**
	 * Creates an IN filter (column IN (#{paramName1}, #{paramName2}, ...)).
	 *
	 * @param column the database column name
	 * @param params the collection of parameters to bind
	 * @return a Filter representing the IN condition
	 */
	public static Filter in(String column, Map<String, Object> params) {
		Objects.requireNonNull(column);
		validateColumn(column);
		if (params == null || params.isEmpty()) // empty IN always false
			return new Raw(" 1 = 0");

		return new In(column, Collections.unmodifiableMap(params));
	}

	/**
	 * Combines multiple filters with the AND operator.
	 *
	 * @param filters the filters to combine
	 * @return a Filter representing the conjunction of the given filters
	 */
	public static Filter and(Filter... filters) {
		if (filters == null || filters.length == 0)
			return Filter.NONE;

		if (filters.length == 1)
			return filters[0];

		return new Combine("AND", filters);
	}

	/**
	 * Combines multiple filters with the OR operator.
	 *
	 * @param filters the filters to combine
	 * @return a Filter representing the disjunction of the given filters
	 */
	public static Filter or(Filter... filters) {
		if (filters == null || filters.length == 0)
			return Filter.NONE;

		if (filters.length == 1)
			return filters[0];

		return new Combine("OR", filters);
	}

	/**
	 * Generates the SQL string for this filter.
	 *
	 * @return the SQL string
	 */
	public String toSqlTemplate() {
		return " 1 = 1";
	}

	/**
	 * Checks if this filter is empty (i.e., represents no condition).
	 *
	 * @return true if the filter is empty, false otherwise
	 */
	public boolean isEmpty() {
		return true;
	}


	/**
	 * Returns the parameter bindings for this filter.
	 *
	 * @return a map of parameter names to their values
	 */
	public Map<String, Object> getParams() {
		return Map.of();
	}

	/**
	 * Validates that the column name contains only safe characters.
	 *
	 * @param column the column name to validate
	 * @throws IllegalArgumentException if the column name is invalid
	 */
	private static void validateColumn(String column) {
		// Only letters, digits, and underscore allowed (safe for SQL identifiers)
		if (!column.matches("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?$"))
			throw new IllegalArgumentException("Invalid SQL column name: " + column);
	}

	/**
	 * WARNING: raw() is not parameter-safe. Use at your own risk.
	 */
	private static class Raw extends Filter {
		private final String sql;

		private Raw(String sql) {
			this.sql = sql;
		}

		@Override
		public String toSqlTemplate() {
			return sql == null ? "" : sql;
		}

		@Override
		public boolean isEmpty() {
			return sql == null || sql.isEmpty();
		}
	}

	private static class Unary extends Filter {
		private final String column;
		private final String operator;

		private Unary(String column, String operator) {
			this.column = column;
			this.operator = operator;
		}

		@Override
		public String toSqlTemplate() {
			return " " + column + " " + operator;
		}

		@Override
		public boolean isEmpty() {
			return false;
		}
	}

	private static class Binary extends Filter {
		private final String column;
		private final String operator;
		private final String paramName;
		private final Object value;

		private Binary(String column, String operator, String paramName, Object value) {
			this.column = column;
			this.operator = operator;
			this.paramName = paramName;
			this.value = value;
		}

		@Override
		public String toSqlTemplate() {
			return " " + column + " " + operator + " #{" + paramName + "}";
		}

		@Override
		public boolean isEmpty() {
			return false;
		}

		@Override
		public Map<String, Object> getParams() {
			return Map.of(paramName, value);
		}
	}

	private static class In extends Filter {
		private final String column;
		private final Map<String, Object> params;

		private In(String column, Map<String, Object> params) {
			this.column = column;
			this.params = params;
		}

		@Override
		public String toSqlTemplate() {
			return " " + column + params.keySet().stream()
					.map(n -> "#{" + n + '}')
					.collect(Collectors.joining(", ", " IN (", ")"));
		}

		@Override
		public boolean isEmpty() {
			return false;
		}

		@Override
		public Map<String, Object> getParams() {
			return params;
		}
	}

	private static class Combine extends Filter {
		private final String op;
		private final Filter[] filters;

		private Combine(String op, Filter[] filters) {
			this.op = op;
			this.filters = filters;
		}

		@Override
		public String toSqlTemplate() {
			return Arrays.stream(filters)
					.map(Filter::toSqlTemplate)
					.collect(Collectors.joining(" " + op, " (", ")"));
		}

		@Override
		public boolean isEmpty() {
			return false;
		}

		@Override
		public Map<String, Object> getParams() {
			return Arrays.stream(filters)
					.map(Filter::getParams)
					.flatMap(m -> m.entrySet().stream())
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		}
	}
}