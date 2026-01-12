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

package io.bosonnetwork.json.internal;

import java.io.IOException;
import java.util.Date;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Serializer for {@link java.util.Date} objects.
 * <p>
 * Handles serialization of Date objects to either ISO8601 string format (for text formats like JSON/YAML)
 * or to epoch milliseconds (for binary formats like CBOR), depending on the output format.
 */
public class DateSerializer extends StdSerializer<Date> {
	private static final long serialVersionUID = 4759684498722016230L;

	/**
	 * Default constructor.
	 */
	public DateSerializer() {
		this(Date.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public DateSerializer(Class<Date> t) {
		super(t);
	}

	/**
	 * Serializes the date.
	 *
	 * @param value    the date to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(Date value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		if (DataFormat.isBinary(gen))
			gen.writeNumber(value.getTime());
		else
			gen.writeString(DateFormat.getDefault().format(value));
	}
}