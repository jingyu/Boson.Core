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
import java.text.ParseException;
import java.util.Date;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserializer for {@link java.util.Date} objects.
 * <p>
 * Handles deserialization of Date objects from either ISO8601/RFC3339 string format or from epoch milliseconds,
 * depending on the input format. In text formats, expects ISO8601 or RFC3339 strings; in binary formats, expects
 * epoch milliseconds.
 */
public class DateDeserializer extends StdDeserializer<Date> {
	private static final long serialVersionUID = -4252894239212420927L;

	/**
	 * Default constructor.
	 */
	public DateDeserializer() {
		this(Date.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public DateDeserializer(Class<?> t) {
		super(t);
	}

	/**
	 * Deserializes the date.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized date
	 * @throws IOException if deserialization fails
	 */
	@Override
	public Date deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		if (p.getCurrentToken() == JsonToken.VALUE_NUMBER_INT) {
			// Binary format: epoch milliseconds
			return new Date(p.getValueAsLong());
		} else {
			// Text format: RFC3339 / ISO8601 format
			if (!p.getCurrentToken().equals(JsonToken.VALUE_STRING))
				throw ctx.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, "Invalid datetime string");

			final String dateStr = p.getValueAsString();
			try {
				return DateFormat.getDefault().parse(dateStr);
			} catch (ParseException ignore) {
			}

			// Fail-back to ISO 8601 format.
			try {
				return DateFormat.getFailback().parse(dateStr);
			} catch (ParseException e) {
				throw ctx.weirdStringException(p.getText(),
						Date.class, "Invalid datetime string");
			}
		}
	}
}