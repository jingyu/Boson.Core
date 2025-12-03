package io.bosonnetwork.database;

import java.util.Map;

/**
 * Helper class for building SQL LIMIT/OFFSET clauses safely.
 * <p/>
 * Example:
 * Pagination p = Pagination.page(3, 20); // pageIndex=3, pageSize=20
 * p.toSql();  // " OFFSET 40 LIMIT 20"
 */
public class Pagination {
	public static final Pagination NONE = new Pagination(0, 0);

	private final long offset;
	private final long limit;

	private Pagination(long offset, long limit) {
		if (offset < 0)
			throw new IllegalArgumentException("offset must be >= 0");

		if (limit < 0)
			throw new IllegalArgumentException("limit must be >= 0");

		this.offset = offset;
		this.limit = limit;
	}

	/**
	 * Create Pagination using explicit limit/offset.
	 *
	 * @param offset the number of rows to skip
	 * @param limit  the maximum number of rows to return
	 * @return a new Pagination instance
	 */
	public static Pagination of(long offset, long limit) {
		if (offset == 0 && limit == 0)
			return NONE;

		return new Pagination(offset, limit);
	}

	/**
	 * Create Pagination using 1-based page index and page size.
	 * <p>
	 * pageIndex = 1 -> first page
	 * </p>
	 *
	 * @param pageIndex the 1-based page index
	 * @param pageSize  the size of the page
	 * @return a new Pagination instance
	 */
	public static Pagination page(long pageIndex, long pageSize) {
		if (pageSize <= 0)
			throw new IllegalArgumentException("pageSize must be > 0");

		if (pageIndex <= 0)
			throw new IllegalArgumentException("pageIndex must be >= 1");

		long offset = (pageIndex - 1) * pageSize;
		return new Pagination(offset, pageSize);
	}

	/**
	 * Generates the SQL LIMIT/OFFSET clause.
	 *
	 * @return SQL fragment like " OFFSET 40 LIMIT 20".
	 * If offset and limit are both 0, returns "" (meaning no limit applied).
	 */
	public String toSql() {
		if (offset == 0 && limit == 0)
			return ""; // caller may omit OFFSET/LIMIT completely

		return " LIMIT " + limit + " OFFSET " + offset;
	}

	/**
	 * Generates a parameterized SQL LIMIT/OFFSET clause.
	 *
	 * @return A SQL fragment like " OFFSET #{offset} LIMIT #{limit}".
	 * If offset and limit are both 0, returns an empty string to indicate no limit is applied.
	 */
	public String toSqlTemplate() {
		if (offset == 0 && limit == 0)
			return ""; // caller may omit OFFSET/LIMIT completely

		return " LIMIT #{limit} OFFSET #{offset}";
	}

	/**
	 * Converts the pagination information into a map representation that can be used in Vert.x SqlTemplates.
	 * The map includes "offset" and "limit" keys if their values are non-zero.
	 * If both offset and limit are zero, an empty map is returned.
	 *
	 * @return a map containing the pagination parameters with keys "offset" and "limit",
	 * or an empty map if both values are zero.
	 */
	public Map<String, Object> getParams() {
		return (offset == 0 && limit == 0) ? Map.of() : Map.of("offset", offset, "limit", limit);
	}

	/**
	 * Identifies the type of pagination being used.
	 * If both offset and limit are 0, it returns "none", indicating no pagination.
	 * Otherwise, it returns "paginated", indicating a paginated query.
	 *
	 * @return a string representing the pagination type, either "none" or "paginated".
	 */
	public String identifier() {
		return (offset == 0 && limit == 0) ? "none" : "paginated";
	}

	/**
	 * Returns the offset (rows to skip).
	 *
	 * @return the offset
	 */
	public long offset() {
		return offset;
	}

	/**
	 * Returns the limit (max rows).
	 *
	 * @return the limit
	 */
	public long limit() {
		return limit;
	}

	/**
	 * Returns the current 1-based page index.
	 *
	 * @return the 1-based page index as an integer. If no limit is applied, it defaults to 1.
	 */
	public long page() {
		if (limit == 0)
			return 1; // For NONE or unlimited, treat as page 1

		return (offset / limit) + 1;
	}

	/**
	 * Returns the size of the page (same as limit).
	 *
	 * @return the page size
	 */
	public long pageSize() {
		return limit;
	}

	/**
	 * Returns the page size to be used, defaulting to the given size if no limit is applied.
	 * If the current limit is 0, the provided size is returned; otherwise, the limit is returned.
	 *
	 * @param size the default page size to use if no limit is set
	 * @return the page size, either the provided size or the current limit
	 */
	public long pageSizeOr(long size) {
		return limit > 0 ? limit : size;
	}
}