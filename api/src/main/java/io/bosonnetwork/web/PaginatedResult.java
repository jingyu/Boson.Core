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

package io.bosonnetwork.web;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a paginated result set.
 *
 * @param <T> the type of items in the result set
 */
public class PaginatedResult<T> {
	@JsonProperty("page")
	private final long page;
	@JsonProperty("pageSize")
	private final long pageSize;
	@JsonProperty("totalPages")
	private final long totalPages;
	@JsonProperty("totalItems")
	private final long totalItems;
	@JsonProperty("items")
	private final List<T> items;

	/**
	 * Creates a new paginated result.
	 *
	 * @param page the current page number
	 * @param pageSize the number of items per page
	 * @param totalPages the total number of pages
	 * @param totalItems the total number of items
	 * @param items the items in the current page
	 */
	@JsonCreator
	protected PaginatedResult(@JsonProperty(value = "page", required = true) long page,
	                          @JsonProperty(value = "pageSize", required = true) long pageSize,
	                          @JsonProperty(value = "totalPages", required = true) long totalPages,
	                          @JsonProperty(value = "totalItems", required = true) long totalItems,
	                          @JsonProperty(value = "items") List<T> items) {
		this.page = page;
		this.pageSize = pageSize;
		this.totalPages = totalPages;
		this.totalItems = totalItems;
		this.items = items == null || items.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(items);
	}

	private PaginatedResult(long page, long pageSize, long totalItems, List<T> items) {
		this.page = page;
		this.pageSize = pageSize;
		this.totalPages = pageSize > 0 ? (totalItems + pageSize - 1) / pageSize : 0;
		this.totalItems = totalItems;
		this.items = items == null || items.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(items);
	}

	/**
	 * Creates a new paginated result.
	 *
	 * @param <T> the type of items
	 * @param page the current page number
	 * @param pageSize the number of items per page
	 * @param totalItems the total number of items
	 * @param items the items in the current page
	 * @return a new paginated result
	 */
	public static <T> PaginatedResult<T> of(long page, long pageSize, long totalItems, List<T> items) {
		return new PaginatedResult<>(page, pageSize, totalItems, items);
	}

	/**
	 * Returns the current page number.
	 *
	 * @return the page number
	 */
	public long page() {
		return page;
	}

	/**
	 * Returns the number of items per page.
	 *
	 * @return the page size
	 */
	public long pageSize() {
		return pageSize;
	}

	/**
	 * Returns the total number of pages.
	 *
	 * @return the total pages
	 */
	public long totalPages() {
		return totalPages;
	}

	/**
	 * Returns the total number of items.
	 *
	 * @return the total items
	 */
	public long totalItems() {
		return totalItems;
	}

	/**
	 * Returns the items in the current page.
	 *
	 * @return the items
	 */
	public List<T> items() {
		return items;
	}
}