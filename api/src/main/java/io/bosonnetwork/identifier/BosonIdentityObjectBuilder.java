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

import static java.text.Normalizer.Form.NFC;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Base class for all Boson identity object builders.
 * <p>
 * Provides common utilities for building Boson objects such as Credentials,
 * Verifiable Presentations (Vouches), and DID Documents. Key features include:
 * <ul>
 *   <li>Storing the {@link Identity} of the builder's subject.</li>
 *   <li>Trimming milliseconds from {@link Date} values in UTC for consistency.</li>
 *   <li>Normalizing strings and nested structures (maps, lists, arrays) using NFC.</li>
 * </ul>
 *
 * @param <T> The type of object being built by the subclass.
 */
public abstract class BosonIdentityObjectBuilder<T> {
	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
	/** The identity of the object subject, used for signing and identification. */
	protected final Identity identity;

	/**
	 * Constructs a new BosonIdentityObjectBuilder for the given subject identity.
	 *
	 * @param identity the subject identity
	 */
	protected BosonIdentityObjectBuilder(Identity identity) {
		this.identity = identity;
	}

	/**
	 * Returns a new {@link Date} with milliseconds set to zero in UTC.
	 * <p>
	 * Useful for normalizing timestamps to second precision.
	 *
	 * @param date the original Date
	 * @return a new Date with milliseconds trimmed
	 */
	protected Date trimMillis(Date date) {
		Calendar cal = Calendar.getInstance(UTC);
		cal.setTime(date);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	/**
	 * Returns the current UTC time with milliseconds trimmed.
	 *
	 * @return the current UTC Date with zeroed milliseconds
	 */
	protected Date now() {
		Calendar cal = Calendar.getInstance(UTC);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	/**
	 * Recursively normalizes strings and nested structures using NFC form.
	 * <p>
	 * Supports {@link String}, {@link Map}, {@link List}, and arrays of objects.
	 *
	 * @param value the value to normalize
	 * @param <R> the type of the value
	 * @return the normalized value
	 */
	@SuppressWarnings("unchecked")
	protected <R> R normalize(R value) {
		if (value instanceof String s)
			return (R) Normalizer.normalize(s, NFC);

		if (value instanceof Map) {
			Map<Object, Object> map = new LinkedHashMap<>();
			for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet())
				map.put(normalize(entry.getKey()), normalize(entry.getValue()));

			return (R) map;
		}

		if (value instanceof List) {
			List<Object> list = new ArrayList<>();
			for (Object o : (List<Object>) value)
				list.add(normalize(o));

			return (R) list;
		}

		if (value instanceof Object[]) {
			Object[] array = new Object[((Object[]) value).length];
			for (int i = 0; i < array.length; i++)
				array[i] = normalize(((Object[]) value)[i]);

			return (R) array;
		}

		return value;
	}

	/**
	 * Normalizes the given subject ID into a valid {@code DIDURL}, ensuring compliance
	 * with the required format and constraints.
	 * <p>
	 * If the ID is not in the expected DID scheme format, it creates a {@code DIDURL}
	 * using the provided subject and the raw ID string. If the ID is in the correct
	 * format, it verifies that the subject part matches the provided subject and that
	 * a fragment is included. Fails with an exception if these validations are not satisfied.
	 *
	 * @param subject the {@code Id} of the subject to which the ID should be related
	 * @param id a raw string representing the ID to be normalized
	 * @return the normalized {@code DIDURL} instance
	 * @throws IllegalStateException if the ID is null, empty, invalid, or fails validation
	 */
	protected DIDURL normalizeSubjectId(Id subject, String id) {
		Objects.requireNonNull(subject, "subject must not be null");
		Objects.requireNonNull(id, "id must not be null");
		if (id.isEmpty())
			throw new IllegalStateException("id must not be empty");

		if (!id.startsWith(DIDConstants.DID_SCHEME + ":"))
			return new DIDURL(subject, null, null, id);

		DIDURL idUrl = DIDURL.create(id);
		if (!Objects.equals(idUrl.getId(), subject))
			throw new IllegalStateException("id DID URL subject part must be DID "
					+ subject.toDIDString() + ": " + id);

		if (idUrl.getFragment() == null)
			throw new IllegalStateException("id DID URL must include a fragment: " + id);

		return idUrl;
	}

	/**
	 * Constructs the final object.
	 *
	 * @return the built object of type {@code T}
	 */
	public abstract T build();
}