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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;

/**
 * Deserializer for {@link AnnounceResult} objects.
 * <p>
 * Reads the format written by {@link AnnounceResultSerializer}: an object carrying a {@code targets}
 * array, each entry an {@code id}, an {@code outcome}, and a {@code cause} where there is one. Ids are
 * accepted as Base58 text or as binary, whichever the format carries; outcomes are matched without
 * regard to case.
 * </p>
 * <p>
 * The aggregate is not read because it is not written - {@link AnnounceResult#of} recomputes it from the
 * targets, which is what keeps a decoded result from being able to disagree with itself.
 * </p>
 * <p>
 * An unrecognised outcome is an error rather than a default. The alternative is to quietly map an outcome
 * this build does not know onto one it does, which would report a publish as something other than what
 * happened; failing says plainly that the two ends are not the same version.
 * </p>
 */
public class AnnounceResultDeserializer extends StdDeserializer<AnnounceResult> {
	private static final long serialVersionUID = 4058947516654301884L;

	/**
	 * Default constructor.
	 */
	public AnnounceResultDeserializer() {
		this(AnnounceResult.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public AnnounceResultDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link AnnounceResult}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized announce result
	 * @throws IOException if deserialization fails
	 */
	@Override
	public AnnounceResult deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, AnnounceResult.class, "Invalid AnnounceResult, should be an object");

		List<AnnounceResult.Target> targets = null;

		while (p.nextToken() != JsonToken.END_OBJECT) {
			String fieldName = p.currentName();
			JsonToken token = p.nextToken();
			if ("targets".equals(fieldName)) {
				if (token != JsonToken.VALUE_NULL)
					targets = parseTargets(p);
			} else {
				p.skipChildren();
			}
		}

		// An absent targets array is not the same as an empty one and is not accepted as it: an empty
		// array says the publish found nobody to ask, which is a real outcome a caller acts on, and
		// inventing it from a message that never carried the field would be reporting an answer that
		// was never given.
		if (targets == null)
			throw MismatchedInputException.from(p, AnnounceResult.class, "Invalid AnnounceResult: missing targets");

		return AnnounceResult.of(targets);
	}

	private static List<AnnounceResult.Target> parseTargets(JsonParser p) throws IOException {
		if (p.currentToken() != JsonToken.START_ARRAY)
			throw MismatchedInputException.from(p, AnnounceResult.class, "Invalid AnnounceResult: targets should be an array");

		List<AnnounceResult.Target> targets = new ArrayList<>();
		while (p.nextToken() != JsonToken.END_ARRAY)
			targets.add(parseTarget(p));

		return targets;
	}

	private static AnnounceResult.Target parseTarget(JsonParser p) throws IOException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, AnnounceResult.Target.class, "Invalid target, should be an object");

		final boolean binaryFormat = DataFormat.isBinary(p);

		Id nodeId = null;
		AnnounceResult.Outcome outcome = null;
		AnnounceResult.Cause cause = null;

		while (p.nextToken() != JsonToken.END_OBJECT) {
			String fieldName = p.currentName();
			JsonToken token = p.nextToken();
			switch (fieldName) {
				case "id":
					if (token != JsonToken.VALUE_NULL)
						nodeId = binaryFormat || token != JsonToken.VALUE_STRING ?
								Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "outcome":
					if (token != JsonToken.VALUE_NULL)
						outcome = parseOutcome(p, p.getValueAsString());
					break;
				case "cause":
					if (token != JsonToken.VALUE_NULL)
						cause = parseCause(p);
					break;
				default:
					p.skipChildren();
			}
		}

		if (nodeId == null)
			throw MismatchedInputException.from(p, AnnounceResult.Target.class, "Invalid target: missing id");
		if (outcome == null)
			throw MismatchedInputException.from(p, AnnounceResult.Target.class, "Invalid target: missing outcome");

		return new AnnounceResult.Target(nodeId, outcome, cause);
	}

	private static AnnounceResult.Outcome parseOutcome(JsonParser p, String name) throws IOException {
		try {
			return AnnounceResult.Outcome.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw MismatchedInputException.from(p, AnnounceResult.Outcome.class, "Invalid target: unknown outcome " + name);
		}
	}

	private static AnnounceResult.Cause parseCause(JsonParser p) throws IOException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, AnnounceResult.Cause.class, "Invalid cause, should be an object");

		Integer code = null;
		String message = null;

		while (p.nextToken() != JsonToken.END_OBJECT) {
			String fieldName = p.currentName();
			JsonToken token = p.nextToken();
			switch (fieldName) {
				case "code":
					if (token != JsonToken.VALUE_NULL)
						code = p.getIntValue();
					break;
				case "message":
					if (token != JsonToken.VALUE_NULL)
						message = p.getValueAsString();
					break;
				default:
					p.skipChildren();
			}
		}

		// Boxed only so that its absence can be told from a legitimate zero, and rejected: a cause the
		// sender wrote down carries a code, and defaulting a missing one would put ErrorCode.Success on
		// a target that failed.
		if (code == null)
			throw MismatchedInputException.from(p, AnnounceResult.Cause.class, "Invalid cause: missing code");

		return new AnnounceResult.Cause(code, message);
	}
}
