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

package io.bosonnetwork.identifier;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.cfg.ContextAttributes;

import io.bosonnetwork.json.Json;

/**
 * Abstract base class providing common JSON and CBOR serialization and deserialization
 * behavior for Boson objects following the W3C DID format.
 * <p>
 * This class uses Jackson ContextAttributes to ensure the correct W3C DID context is
 * applied during serialization and deserialization. It provides utility methods for
 * converting Boson DID objects to and from JSON/CBOR representations.
 * </p>
 */
abstract class W3CDIDFormat {
	/**
	 * The W3C DID context attributes used for per-call serialization and deserialization.
	 * <p>
	 * This context is injected into Jackson's ObjectMapper/CBORMapper at call time to ensure
	 * that the W3C DID format is applied for each serialization or parsing operation.
	 * </p>
	 */
	protected static final ContextAttributes w3cDIDContext = ContextAttributes.getEmpty()
			.withPerCallAttribute(DIDConstants.BOSON_ID_FORMAT_W3C, true);

	/**
	 * Serializes this object to a compact JSON string using the W3C DID context.
	 *
	 * @return JSON string representation of this object
	 * @throws IllegalStateException if the object cannot be serialized
	 */
	@Override
	public String toString() {
		try {
			// Serialize using the per-call W3C DID context
			return Json.objectMapper().writer(w3cDIDContext).writeValueAsString(this);
		} catch (JsonProcessingException e) {
			// Wrap serialization errors to avoid leaking implementation details
			throw new IllegalStateException("INTERNAL ERROR: " + this.getClass().getSimpleName() + " is not serializable", e);
		}
	}

	/**
	 * Serializes this object to a pretty-printed JSON string using the W3C DID context.
	 *
	 * @return Pretty-printed JSON string representation of this object
	 * @throws IllegalStateException if the object cannot be serialized
	 */
	public String toPrettyString() {
		try {
			// Serialize with pretty printer and W3C DID context
			return Json.objectMapper().writerWithDefaultPrettyPrinter().with(w3cDIDContext).writeValueAsString(this);
		} catch (JsonProcessingException e) {
			// Wrap serialization errors to avoid leaking implementation details
			throw new IllegalStateException("INTERNAL ERROR: " + this.getClass().getSimpleName() + " is not serializable", e);
		}
	}

	/**
	 * Serializes this object to CBOR bytes using the W3C DID context.
	 *
	 * @return CBOR-encoded byte array of this object
	 * @throws IllegalStateException if the object cannot be serialized
	 */
	public byte[] toBytes() {
		try {
			// Serialize using CBORMapper and W3C DID context
			return Json.cborMapper().writer(w3cDIDContext).writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			// Wrap serialization errors to avoid leaking implementation details
			throw new IllegalStateException("INTERNAL ERROR: " + this.getClass().getSimpleName() + " is not serializable", e);
		}
	}

	/**
	 * Parses a JSON string into an object of the given class.
	 *
	 * @param json  the JSON string to parse
	 * @param clazz the target class to deserialize into
	 * @param <R>   the type of the object to return
	 * @return the deserialized object
	 * @throws IllegalArgumentException if the JSON is invalid or cannot be parsed
	 */
	protected static <R> R parse(String json, Class<R> clazz) {
		try {
			// Parse using Jackson ObjectMapper (context not required for reading)
			return Json.objectMapper().readValue(json, clazz);
		} catch (IOException e) {
			// Wrap parsing errors to provide clear feedback for invalid input
			throw new IllegalArgumentException("Invalid JSON data for " + clazz.getSimpleName(), e);
		}
	}

	/**
	 * Parses a CBOR-encoded byte array into an object of the given class.
	 *
	 * @param cbor  the CBOR-encoded byte array to parse
	 * @param clazz the target class to deserialize into
	 * @param <R>   the type of the object to return
	 * @return the deserialized object
	 * @throws IllegalArgumentException if the CBOR is invalid or cannot be parsed
	 */
	protected static <R> R parse(byte[] cbor, Class<R> clazz) {
		try {
			// Parse using Jackson CBORMapper (context not required for reading)
			return Json.cborMapper().readValue(cbor, clazz);
		} catch (IOException e) {
			// Wrap parsing errors to provide clear feedback for invalid input
			throw new IllegalArgumentException("Invalid CBOR data for " + clazz.getSimpleName(), e);
		}
	}
}