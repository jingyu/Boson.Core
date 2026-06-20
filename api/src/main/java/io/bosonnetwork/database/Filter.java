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

import static io.bosonnetwork.database.SqlSafety.validateColumn;
import static io.bosonnetwork.database.SqlSafety.validateParamName;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

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
		return new Binary(validateColumn(column), "=", paramName, value);
	}

	/**
	 * Creates an equality filter (column = #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the equality condition
	 */
	public static Filter eq(String column, Object value) {
		return eq(column, defaultParamName(column, "eq"), value);
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
		return new Binary(validateColumn(column), "<>", paramName, value);
	}

	/**
	 * Creates a non-equality filter (column &lt;&gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the non-equality condition
	 */
	public static Filter ne(String column, Object value) {
		return ne(column, defaultParamName(column, "ne"), value);
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
		return new Binary(validateColumn(column), "<", paramName, value);
	}

	/**
	 * Creates a less-than filter (column &lt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the less-than condition
	 */
	public static Filter lt(String column, Object value) {
		return lt(column, defaultParamName(column, "lt"), value);
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
		return new Binary(validateColumn(column), "<=", paramName, value);
	}

	/**
	 * Creates a less-than-or-equal filter (column &lt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the less-than-or-equal condition
	 */
	public static Filter lte(String column, Object value) {
		return lte(column, defaultParamName(column, "lte"), value);
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
		return new Binary(validateColumn(column), ">", paramName, value);
	}

	/**
	 * Creates a greater-than filter (column &gt; #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than condition
	 */
	public static Filter gt(String column, Object value) {
		return gt(column, defaultParamName(column, "gt"), value);
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
		return new Binary(validateColumn(column), ">=", paramName, value);
	}

	/**
	 * Creates a greater-than-or-equal filter (column &gt;= #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the greater-than-or-equal condition
	 */
	public static Filter gte(String column, Object value) {
		return gte(column, defaultParamName(column, "gte"), value);
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
		return new Binary(validateColumn(column), "LIKE", paramName, value);
	}

	/**
	 * Creates a LIKE filter (column LIKE #{paramName}).
	 *
	 * @param column    the database column name
	 * @param value     the value to bind
	 * @return a Filter representing the <code>LIKE</code> condition
	 */
	public static Filter like(String column, Object value) {
		return like(column, defaultParamName(column, "like"), value);
	}

	/**
	 * Creates a filter checking if the column is NULL.
	 *
	 * @param column the database column name
	 * @return a Filter representing the IS NULL condition
	 */
	public static Filter isNull(String column) {
		Objects.requireNonNull(column);
		return new Unary(validateColumn(column), "IS NULL");
	}

	/**
	 * Creates a filter checking if the column is NOT NULL.
	 *
	 * @param column the database column name
	 * @return a Filter representing the IS NOT NULL condition
	 */
	public static Filter isNotNull(String column) {
		Objects.requireNonNull(column);
		return new Unary(validateColumn(column), "IS NOT NULL");
	}

	/**
	 * Creates an IN filter (column IN (#{paramName1}, #{paramName2}, ...)).
	 *
	 * @param column the database column name
	 * @param params the collection of parameters to bind
	 * @return a Filter representing the IN condition
	 */
	public static Filter in(String column, @Nullable Map<String, Object> params) {
		Objects.requireNonNull(column);
		column = validateColumn(column);
		if (params == null || params.isEmpty()) // empty IN always false
			return new Raw(" 1 = 0");

		params.keySet().forEach(SqlSafety::validateParamName);

		return new In(column, Collections.unmodifiableMap(params));
	}

	/**
	 * Combines multiple filters with the AND operator.
	 *
	 * @param filters the filters to combine
	 * @return a Filter representing the conjunction of the given filters
	 */
	public static Filter and(Filter... filters) {
		//noinspection ConstantConditions
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
		//noinspection ConstantConditions
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
	 * Derives a safe default bind-parameter name from a column name and the operator. A qualified
	 * column such as {@code table.col} is mapped to {@code table_col} so it is a valid parameter
	 * token, and the operator is appended (e.g. {@code col_gte}) so that distinct operators on the
	 * same column do not collide when combined - for example
	 * {@code and(gte("ts", lo), lte("ts", hi))} yields the distinct names {@code ts_gte} and
	 * {@code ts_lte}. Combining two filters that use the <em>same</em> column and operator still
	 * collides; use the explicit {@code paramName} overload to disambiguate those.
	 *
	 * @param column the column name (already validated by the caller)
	 * @param op     the operator suffix (e.g. {@code "eq"}, {@code "gte"})
	 * @return a valid parameter name, or {@code null} if {@code column} is {@code null}
	 */
	private static String defaultParamName(String column, String op) {
		Objects.requireNonNull(column);
		Objects.requireNonNull(op);
		return column.replace('.', '_') + '_' + op;
	}

	/**
	 * WARNING: raw() is not parameter-safe. Use at your own risk.
	 */
	private static class Raw extends Filter {
		private final @Nullable String sql;

		private Raw(@Nullable String sql) {
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
			this.paramName = validateParamName(paramName);
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
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
							(a, b) -> {
								throw new IllegalStateException("Duplicate bind-parameter name in combined filter; " +
										"use the explicit paramName overload to disambiguate conditions on the same column");
							}));
		}
	}
}