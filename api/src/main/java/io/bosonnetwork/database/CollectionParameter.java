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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A utility class that represents a collection-based parameter intended for use
 * in building templates and parameter mappings for Vert.x SqlTemplate.
 * <p>
 * Vert.x SqlTemplate does not support a collection as the parameter, so we need this
 * helper class for Vert.x SqlTemplate to support collection parameters.
 *
 * @param <T> the type of elements in the collection parameter
 */
public class CollectionParameter<T> {
	private final String name;
	private final Collection<T> values;

	/**
	 * Constructs a new CollectionParameter with the specified name and collection of values.
	 *
	 * @param name the name of the parameter; must not be null
	 * @param values the collection of values associated with the parameter; must not be null
	 * @throws NullPointerException if {@code name} or {@code values} is null
	 */
	public CollectionParameter(String name, Collection<T> values) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(values, "values");
		this.name = name;
		this.values = List.copyOf(values);
	}

	/**
	 * Generates a template string for the collection parameter, where each value in the
	 * collection is represented as a tokenized placeholder.
	 * The format of the template is "(#{name_0}, #{name_1}, ...)", where "name" is the
	 * name of the parameter and each index corresponds to a unique placeholder for a value.
	 *
	 * @return a formatted string representing the template for the collection parameter
	 */
	public String getTemplate() {
		return IntStream.range(0, values.size())
				.mapToObj(i -> "#{" + name + "_" + i + '}')
				.collect(Collectors.joining(", ", "(", ")"));
	}

	/**
	 * Creates a map of parameter placeholders and their corresponding values from the collection.
	 * Each key in the map is a unique placeholder in the format "name_index", where "name" is the
	 * parameter name and "index" is the zero-based index of the value. Each value in the map
	 * corresponds to a value from the collection.
	 *
	 * @return a map where keys are dynamically generated placeholders and values are the elements
	 *         of the collection
	 */
	public Map<String, Object> getParams() {
		Map<String, Object> params = new HashMap<>();
		int i = 0;
		for (T v : values)
			params.put(name + "_" + (i++), v);

		return params;
	}
}