package io.bosonnetwork.database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A helper class for building SQL ORDER BY clauses safely.
 * Supports multiple fields and prevents SQL injection by validating column names.
 * <p>
 * Example:
 * Ordering order = Ordering.by("name").asc()
 *                          .then("created").desc();
 * <br/>
 * String sql = order.toSql(); // " ORDER BY name ASC, created DESC"
 * </p>
 */
public class Ordering {
	/** Special instance representing no ordering. Method toSql() returns an empty string. */
	public static final Ordering NONE = new Ordering(Collections.emptyList());

	private final List<Field> fields;

	/** Sort direction. */
	public enum Direction {
		/** Ascending. */
		ASC,
		/** Descending. */
		DESC
	}

	/**
	 * Represents a column and its sorting direction in an SQL ORDER BY clause.
	 * A Field is used to specify the sorting criteria for a query. Each Field contains:
	 * - A column, representing the name of the database column to sort by.
	 * - A direction, indicating whether the sorting should be ascending or descending.
	 *
	 * @param column    representing the name of the database column to sort by.
	 * @param direction indicating whether the sorting should be ascending or descending.
	 */
	public record Field(String column, Direction direction) {
	}

	private Ordering(List<Field> fields) {
		this.fields = Collections.unmodifiableList(fields);
	}


	/**
	 * Start an ordering chain.
	 *
	 * @param column    the name of the column to sort by
	 * @param direction the direction to sort in, either ASC or DESC
	 * @return a new Builder instance configured with the specified column and direction
	 */
	public static Builder by(String column, Direction direction) {
		return new Builder(column, direction);
	}

	/**
	 * Start an ordering chain.
	 *
	 * @param column the first column to sort by with default direction ASC
	 * @return a new Builder instance configured with the specified column and direction
	 */
	public static Builder by(String column) {
		return new Builder(column, Direction.ASC);
	}

	/**
	 * Generates the SQL ORDER BY clause.
	 *
	 * @return SQL order by subclause like " ORDER BY name ASC, created DESC", or empty string if no fields.
	 */
	public String toSql() {
		if (fields.isEmpty()) return "";

		StringBuilder sb = new StringBuilder(" ORDER BY ");
		for (int i = 0; i < fields.size(); i++) {
			Field f = fields.get(i);
			sb.append(f.column).append(" ").append(f.direction);
			if (i < fields.size() - 1) {
				sb.append(", ");
			}
		}
		return sb.toString();
	}

	/**
	 * Generates a unique identifier string representing the ordering configuration
	 * based on the fields. If no fields are present, it returns "none".
	 *
	 * @return a string in the format "orderBy_<column>_<direction>_..." or "none" if no fields are defined
	 */
	public String identifier() {
		if (fields.isEmpty())
			return "none";

		return fields.stream().map(f -> f.column + '_' + f.direction)
				.collect(Collectors.joining("_", "orderBy_", ""));
	}

	/**
	 * Checks if the ordering instance contains no fields.
	 *
	 * @return true if the ordering has no fields; false otherwise.
	 */
	public boolean isEmpty() {
		return fields.isEmpty();
	}

	/**
	 * Returns the list of fields in this ordering.
	 *
	 * @return the list of fields
	 */
	public List<Field> fields() {
		return fields;
	}

	/**
	 * Builder for {@link Ordering}.
	 */
	public static final class Builder {
		private final List<Field> list = new ArrayList<>();

		private Builder(String column, Direction direction) {
			Objects.requireNonNull(column);
			validateColumn(column);
			list.add(new Field(column, direction));  // default
		}

		/**
		 * Sets the direction of the current field to ascending.
		 *
		 * @return this builder
		 */
		public Builder asc() {
			update(Direction.ASC);
			return this;
		}

		/**
		 * Sets the direction of the current field to descending.
		 *
		 * @return this builder
		 */
		public Builder desc() {
			update(Direction.DESC);
			return this;
		}

		/**
		 * Add a new field ordering after the previous one.
		 *
		 * @param column the next column to sort by
		 * @param direction the next direction to sort in, either ASC or DESC
		 * @return this builder
		 */
		public Builder then(String column, Direction direction) {
			Objects.requireNonNull(column);
			validateColumn(column);
			list.add(new Field(column, direction));
			return this;
		}

		/**
		 * Add a new field ordering after the previous one.
		 *
		 * @param column the next column to sort by with default direction ASC
		 * @return this builder
		 */
		public Builder then(String column) {
			return then(column, Direction.ASC); // default direction
		}

		private void update(Direction dir) {
			int last = list.size() - 1;
			Field current = list.get(last);
			list.set(last, new Field(current.column, dir));
		}

		/**
		 * Builds the {@link Ordering} instance.
		 *
		 * @return the new Ordering instance
		 */
		public Ordering build() {
			return new Ordering(list);
		}
	}

	private static void validateColumn(String column) {
		// Only letters, digits, and underscore allowed (safe for SQL identifiers)
		if (!column.matches("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)?$"))
			throw new IllegalArgumentException("Invalid SQL column name: " + column);
	}
}