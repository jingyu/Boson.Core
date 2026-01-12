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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

/**
 * Utility class for identifying data formats being handled by JSON parsers and generators.
 * This class focuses on distinguishing between binary formats, specifically
 * CBOR (Concise Binary Object Representation), and non-binary formats such as JSON or TOML.
 */
public class DataFormat {
	/**
	 * Determines if the provided {@link JsonParser} is operating in a binary format (CBOR).
	 *
	 * @param p the {@code JsonParser} to check
	 * @return {@code true} if the parser is handling a binary format (CBOR), {@code false} otherwise
	 */
	public static boolean isBinary(JsonParser p) {
		// Now we only sport JSON, CBOR and TOML formats; CBOR is the only binary format
		return p instanceof CBORParser;
	}

	/**
	 * Determines if the provided {@link JsonGenerator} is operating in a binary format (CBOR).
	 *
	 * @param gen the {@code JsonGenerator} to check
	 * @return {@code true} if the generator is handling a binary format (CBOR), {@code false} otherwise
	 */
	public static boolean isBinary(JsonGenerator gen) {
		// Now we only sport JSON, CBOR and TOML formats; CBOR is the only binary format
		return gen instanceof CBORGenerator;
	}
}