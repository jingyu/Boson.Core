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

package io.bosonnetwork.kademlia.protocol;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.kademlia.exceptions.KadException;

@JsonPropertyOrder({"c", "m"})
public class Error implements Message.Body {
	@JsonProperty("c")
	private final int code;
	@JsonProperty("m")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final String message;

	@JsonCreator()
	public Error(@JsonProperty(value = "c", required = true) int code,
				 @JsonProperty("m") String message) {
		this.code = code;
		this.message = message;
	}

	@Override
	public Message.Type getType() {
		return Message.Type.ERROR;
	}

	public int getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}

	public KadException getCause() {
		// TODO: convert the error message to the corresponding KadException
		return null;
	}

	@Override
	public int hashCode() {
		return Objects.hash(code, message);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Error that)
			return code == that.code && Objects.equals(message, that.message);

		return false;
	}
}