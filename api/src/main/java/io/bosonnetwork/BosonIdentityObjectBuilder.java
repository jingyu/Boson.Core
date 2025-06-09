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

package io.bosonnetwork;

import static java.text.Normalizer.Form.NFC;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public abstract class BosonIdentityObjectBuilder<T> {
	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
	protected final Identity identity;

	protected BosonIdentityObjectBuilder(Identity identity) {
		this.identity = identity;
	}

	protected Date trimMillis(Date date) {
		Calendar cal = Calendar.getInstance(UTC);
		cal.setTime(date);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	protected Date now() {
		Calendar cal = Calendar.getInstance(UTC);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	@SuppressWarnings("unchecked")
	protected <T> T normalize(T value) {
		if (value instanceof String s)
			return (T) Normalizer.normalize(s, NFC);

		if (value instanceof Map) {
			Map<Object, Object> map = new LinkedHashMap<>();
			for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet())
				map.put(normalize(entry.getKey()), normalize(entry.getValue()));

			return (T) map;
		}

		if (value instanceof List) {
			List<Object> list = new ArrayList<>();
			for (Object o : (List<Object>) value)
				list.add(normalize(o));

			return (T) list;
		}

		if (value instanceof Object[]) {
			Object[] array = new Object[((Object[]) value).length];
			for (int i = 0; i < array.length; i++)
				array[i] = normalize(((Object[]) value)[i]);

			return (T) array;
		}

		return value;
	}

	public abstract T build();
}