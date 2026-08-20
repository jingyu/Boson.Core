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
import java.util.Locale;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;

/**
 * Serializer for {@link AnnounceResult} objects.
 * <p>
 * Encodes the result as an object carrying a single {@code targets} array, one entry per node the
 * publish considered:
 * </p>
 * <pre>
 * {
 *   "targets": [
 *     {"id": "node_id", "outcome": "acknowledged"},
 *     {"id": "node_id", "outcome": "refused", "cause": {"code": 1234, "message": "detail"}}
 *   ]
 * }
 * </pre>
 * <p>
 * Only the per-node answers travel. The aggregate - status, acknowledgement count, whether anything was
 * announced - is derived from them on both sides, so writing it would be writing the same fact twice and
 * inviting the two copies to disagree. Both degenerate cases survive the round trip on the array alone:
 * an empty array is {@link AnnounceResult.Status#NO_TARGETS}, and a non-empty one with no acknowledgement
 * is {@link AnnounceResult.Status#FAILED}.
 * </p>
 * <p>
 * The targets are an array rather than an object keyed by id, and must stay one: a dual-stack publish
 * merges two address families that reach the same node under the same {@link Id}, so two entries can
 * legitimately share an id.
 * </p>
 * <p>
 * Ids follow the convention of the other Boson codecs - Base58 text in JSON and YAML, Base64URL binary in
 * CBOR. Outcomes are written in lower case, matching how the enums already on the gateway API are spelled.
 * A cause is omitted where there is none, and so is its message.
 * </p>
 */
public class AnnounceResultSerializer extends StdSerializer<AnnounceResult> {
	private static final long serialVersionUID = -3054413096952654823L;

	/**
	 * Default constructor.
	 */
	public AnnounceResultSerializer() {
		this(AnnounceResult.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public AnnounceResultSerializer(Class<AnnounceResult> t) {
		super(t);
	}

	/**
	 * Serializes the {@link AnnounceResult}.
	 *
	 * @param value    the announce result to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(AnnounceResult value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		final boolean binaryFormat = DataFormat.isBinary(gen);

		gen.writeStartObject();
		gen.writeArrayFieldStart("targets");

		for (AnnounceResult.Target target : value.targets()) {
			gen.writeStartObject();

			if (binaryFormat) {
				gen.writeFieldName("id");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, target.nodeId().bytesUnsafe(), 0, Id.BYTES);
			} else {
				gen.writeStringField("id", target.nodeId().toBase58String());
			}

			gen.writeStringField("outcome", target.outcome().name().toLowerCase(Locale.ROOT));

			AnnounceResult.Cause cause = target.cause();
			if (cause != null) {
				gen.writeObjectFieldStart("cause");
				gen.writeNumberField("code", cause.code());
				if (cause.message() != null)
					gen.writeStringField("message", cause.message());
				gen.writeEndObject();
			}

			gen.writeEndObject();
		}

		gen.writeEndArray();
		gen.writeEndObject();
	}
}
