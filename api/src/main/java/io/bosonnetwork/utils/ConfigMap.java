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

package io.bosonnetwork.utils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.bosonnetwork.Id;

/**
 * A wrapper around a {@code Map<String, Object>} that provides type-safe configuration value retrieval.
 * <p>
 * This class offers convenient methods to retrieve configuration values as specific types (String, Number,
 * Integer, Long, Boolean, Duration, Port, etc.) with automatic type conversion and validation.
 * It also supports default values for optional configuration parameters.
 * </p>
 */
public class ConfigMap implements Map<String, Object> {
	private final Map<String, Object> map;

	/**
	 * Constructs a new ConfigMap wrapping the provided map.
	 *
	 * @param map the underlying map to wrap must not be null
	 * @throws NullPointerException if the map is null
	 */
	public ConfigMap(Map<String, Object> map) {
		Objects.requireNonNull(map);
		this.map = map;
	}

	/**
	 * Retrieves a string value for the specified key.
	 * <p>
	 * If the value is an Enum, returns its name. Otherwise, converts the value to string using toString().
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the string value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing
	 */
	public String getString(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing value - " + key);
		else if (val instanceof String s)
			return s;
		else if (val instanceof Enum<?> e)
			return e.name();
		else
			return val.toString();
	}

	/**
	 * Retrieves a string value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the string value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public String getString(String key, String def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getString(key) : def;
	}

	/**
	 * Retrieves a numeric value for the specified key.
	 * <p>
	 * Supports conversion from Boolean (true=1, false=0) and String (parsed as Double).
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the numeric value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing or the value cannot be converted to a number
	 */
	public Number getNumber(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing value - " + key);
		else if (val instanceof Number n)
			return n;
		else if (val instanceof Boolean b)
			return b ? 1 : 0;
		else if (val instanceof String s)
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid number value - " + key + ": " + val);
			}
		else
			throw new IllegalArgumentException("Invalid number value - " + key + ": " + val);
	}

	/**
	 * Retrieves a numeric value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the numeric value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public Number getNumber(String key, Number def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getNumber(key) : def;
	}

	/**
	 * Retrieves an integer value for the specified key.
	 * <p>
	 * Supports conversion from Number (using intValue()), Boolean (true=1, false=0), and String (parsed as Integer).
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the integer value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing or the value cannot be converted to an integer
	 */
	public int getInteger(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing value - " + key);
		else if (val instanceof Integer i)
			return i;  // Avoids unnecessary unbox/box
		else if (val instanceof Number n)
			return n.intValue();
		else if (val instanceof Boolean b)
			return b ? 1 : 0;
		else if (val instanceof String s)
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid integer value - " + key + ": " + val);
			}
		else
			throw new IllegalArgumentException("Invalid integer value - " + key + ": " + val);
	}

	/**
	 * Retrieves an integer value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the integer value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public int getInteger(String key, int def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getInteger(key) : def;
	}

	/**
	 * Retrieves a long value for the specified key.
	 * <p>
	 * Supports conversion from Number (using longValue()), Boolean (true=1L, false=0L), and String (parsed as Long).
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the long value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing or the value cannot be converted to a long integer
	 */
	public long getLong(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing value - " + key);
		else if (val instanceof Long l)
			return l;  // Avoids unnecessary unbox/box
		else if (val instanceof Number n)
			return n.longValue();
		else if (val instanceof Boolean b)
			return b ? 1L : 0L;
		else if (val instanceof String s)
			try {
				return Long.parseLong(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid long value - " + key + ": " + val);
			}
		else
			throw new IllegalArgumentException("Invalid long value - " + key + ": " + val);
	}

	/**
	 * Retrieves a long value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the long value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public long getLong(String key, long def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getLong(key) : def;
	}

	/**
	 * Retrieves a boolean value for the specified key.
	 * <p>
	 * Supports conversion from String ("true"/"false", case-insensitive) and Integer (0=false, 1=true).
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the boolean value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing or the value cannot be converted to a boolean
	 */
	public boolean getBoolean(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing value - " + key);
		else if (val instanceof Boolean b)
			return b;
		else if (val instanceof String s)
			return switch (s.toLowerCase()) {
				case "true" -> true;
				case "false" -> false;
				default -> throw new IllegalArgumentException("Invalid boolean value - " + key + ": " + val);
			};
		else if (val instanceof Integer i)
			return switch (i) {
				case 0 -> false;
				case 1 -> true;
				default -> throw new IllegalArgumentException("Invalid boolean value - " + key + ": " + val);
			};
		else
			throw new IllegalArgumentException("Invalid boolean value - " + key + ": " + val);
	}

	/**
	 * Retrieves a boolean value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the boolean value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public boolean getBoolean(String key, boolean def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getBoolean(key) : def;
	}

	/**
	 * Retrieves a {@link Path} object corresponding to the provided key.
	 * The method ensures the key is not null and attempts to retrieve a value from an internal map.
	 * If the value is a string representing a path and starts with '~', it resolves the path relative to the user's home directory.
	 * If the key is missing or the value is invalid, an exception is thrown.
	 *
	 * @param key the string key used to look up the value in the map; must not be null
	 * @return the resolved {@link Path} object corresponding to the provided key
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing from the map, or the value associated with it is not a valid path
	 */
	public Path getPath(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			throw new IllegalArgumentException("Missing value - " + key);
		} else if (val instanceof String s) {
			try {
				Path path = Path.of(s);
				if (path.startsWith("~"))
					path = Path.of(System.getProperty("user.home")).resolve(path.subpath(1, path.getNameCount()));
				return path;
			} catch (InvalidPathException e) {
				throw new IllegalArgumentException("Invalid path value - " + key + ": " + s, e);
			}
		} else {
			throw new IllegalArgumentException("Invalid path value - " + key + ": " + val);
		}
	}

	/**
	 * Retrieves the path associated with the specified key from the internal map. If the key is not present,
	 * the provided default path is returned.
	 *
	 * @param key the key whose associated path is to be returned; must not be null
	 * @param def the default path to return if the key is not present in the map
	 * @return the path associated with the specified key, or the default path if the key is not found
	 */
	public Path getPath(String key, Path def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getPath(key) : def;
	}

	/**
	 * Retrieves the size associated with the specified key and converts it into its long representation.
	 * The value corresponding to the key can be:
	 * - An Integer: returned as is.
	 * - A Long: returned as is.
	 * - A String: representing a plain number or a size with units (e.g., "k" for kilobytes, "m" for megabytes,
	 *   "g" for gigabytes, "b" for bytes). The method parses and converts it accordingly.
	 *
	 * @param key the key whose corresponding size value is to be retrieved and converted
	 * @return the size value as a long type
	 * @throws NullPointerException if the provided key is null
	 * @throws IllegalArgumentException if the key is missing in the map, or if the size value is invalid or
	 *         uses unsupported units
	 */
	public long getSize(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			throw new IllegalArgumentException("Missing value - " + key);
		} else if (val instanceof Integer i) {
			return i;
		} else if (val instanceof Long l) {
			return l;
		} else if (val instanceof String s) {
			int idx = s.length() - 1;
			final char specifier = s.charAt(idx);
			if (specifier >= '0' && specifier <= '9') {
				// no unit specified, assume a plain number
				try {
					return Long.parseLong(s);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Invalid size value - " + key + ": " + s, e);
				}
			} else {
				int weight = switch (Character.toLowerCase(specifier)) {
					case 'b' -> 1;
					case 'k' -> 1024;
					case 'm' -> 1024 * 1024;
					case 'g' -> 1024 * 1024 * 1024;
					default -> throw new IllegalArgumentException("Invalid size value - " + key + ": " + s +
							", units: b, k, m, g");
				};

				try {
					long size = Long.parseLong(s, 0, idx, 10);
					return size * weight;
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Invalid size value - " + key + ": " + s, e);
				}
			}
		} else {
			throw new IllegalArgumentException("Invalid size value - " + key + ": " + val);
		}
	}

	/**
	 * Retrieves the size associated with the specified key. If the key does not exist
	 * in the map, the default value provided is returned.
	 *
	 * @param key the key whose associated size is to be retrieved, must not be null
	 * @param def the default value to return if the key is not present in the map
	 * @return the size associated with the specified key if it exists, or the default value otherwise
	 */
	public long getSize(String key, long def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getSize(key) : def;
	}

	/**
	 * Retrieves a duration value for the specified key.
	 * <p>
	 * The value can be a long/integer (interpreted as milliseconds) or a human-friendly duration string.
	 *
	 * <p>
	 * Format: &lt;number&gt;&lt;unit&gt;
	 * <br>
	 * Supported units (case-sensitive):
	 * <ul>
	 *  <li>s - seconds</li>
	 *  <li>m - minutes</li>
	 *  <li>h - hours</li>
	 *  <li>d - days</li>
	 *  <li>w - weeks</li>
	 *  <li>M - months</li>
	 *  <li>y - years</li>
	 * </ul>
	 *
	 * @param key the configuration key, key must not be null
	 * @return the parsed {@code Duration} object
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing or the value cannot be parsed as a duration
	 */
	public Duration getDuration(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			throw new IllegalArgumentException("Missing value - " + key);
		} else if (val instanceof Integer i) {
			return Duration.ofMillis(i);
		} else if (val instanceof Long l) {
			return Duration.ofMillis(l);
		} else if (val instanceof String s) {
			int idx = s.length() - 1;
			final char specifier = s.charAt(idx);
			final TemporalUnit unit = switch (specifier) {
				case 's' -> ChronoUnit.SECONDS;
				case 'm' -> ChronoUnit.MINUTES;
				case 'h' -> ChronoUnit.HOURS;
				case 'd' -> ChronoUnit.DAYS;
				case 'w' -> ChronoUnit.WEEKS;
				case 'M' -> ChronoUnit.MONTHS;
				case 'y' -> ChronoUnit.YEARS;
				default -> throw new IllegalArgumentException("Invalid duration value - " + key + ": " + s +
						", units: s, m, h, d, w, M, y");
			};

			try {
				long number = Long.parseLong(s, 0, idx, 10);
				return Duration.ofMillis(number * unit.getDuration().toMillis());
			} catch (Exception e) {
				throw new IllegalArgumentException("Invalid duration value - " + key + ": " + s, e);
			}
		} else {
			throw new IllegalArgumentException("Invalid duration value - " + key + ": " + val);
		}
	}

	/**
	 * Retrieves a duration value for the specified key or returns a default value if the key is not present.
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default value to return if the key is not present
	 * @return the duration value or the default value
	 * @throws NullPointerException if the key is null
	 */
	public Duration getDuration(String key, Duration def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getDuration(key) : def;
	}

	/**
	 * Retrieves a valid port number for the specified key.
	 * <p>
	 * The port must be in the valid range [0, 65535]. Supports conversion from Integer and String.
	 *
	 * @param key the configuration key, key must not be null
	 * @return the port number
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is missing, the value cannot be converted to an integer,
	 *                                  or the port is outside the valid range [0, 65535]
	 */
	public int getPort(String key) {
		Objects.requireNonNull(key);
		int port;
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing port number - " + key);
		else if (val instanceof Integer i)
			port = i;
		else if (val instanceof String s)
			try {
				port = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid port number - " + key + ": " + val);
			}
		else
			throw new IllegalArgumentException("Invalid port number - " + key + ": " + val);

		if (port < 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port number - " + key + ": " + port);
		return port;
	}

	/**
	 * Retrieves a valid port number for the specified key or returns a default value if the key is not present.
	 * <p>
	 * The port (including the default) must be in the valid range [0, 65535].
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @param def the default port number to return if the key is not present
	 * @return the port number or the default value
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the port is outside the valid range [0, 65535]
	 */
	public int getPort(String key, int def) {
		Objects.requireNonNull(key);
		int port = map.containsKey(key) ? getPort(key) : def;
		if (port < 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port number - " + key + ": " + port);
		return port;
	}

	/**
	 * Retrieves the Id associated with the specified key from the map.
	 * The key must not be null, and the corresponding value in the map must be a valid String
	 * representation of an Id. If the key is not present or the value is invalid, an exception
	 * is thrown.
	 *
	 * @param key the key whose associated Id is to be retrieved
	 * @return the Id associated with the specified key
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the key is not present in the map, or if the
	 * value associated with the key is not a valid String representation of an Id
	 */
	public Id getId(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null)
			throw new IllegalArgumentException("Missing Id value - " + key);
		else if (val instanceof String s)
			try {
				return Id.of(s);
			} catch (Exception e) {
				throw new IllegalArgumentException("Invalid Id value - " + key + ": " + val);
			}
		else
			throw new IllegalArgumentException("Invalid Id value - " + key + ": " + val);
	}

	/**
	 * Retrieves the identifier associated with the specified key. If the key is not found
	 * in the map, the provided default identifier is returned.
	 *
	 * @param key the key whose associated Id is to be retrieved
	 * @param def the default identifier to return if the key is not present in the map
	 * @return the identifier associated with the given key, or the default identifier if the key is not found
	 * @throws NullPointerException     if the key is null
	 * @throws IllegalArgumentException if the value associated with the key is not a valid String
	 *                                  representation of an Id
	 */
	public Id getId(String key, Id def) {
		Objects.requireNonNull(key);
		return map.containsKey(key) ? getId(key) : def;
	}

	/**
	 * Retrieves a nested configuration object for the specified key.
	 * <p>
	 * The value must be a Map, which will be wrapped in a new ConfigMap instance.
	 * </p>
	 *
	 * @param key the configuration key, key must not be null
	 * @return a ConfigMap wrapping the nested configuration, or null if the key is not present
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the value is not a Map
	 */
	public ConfigMap getObject(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			return null;
		} else if (val instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> subMap = (Map<String, Object>) m;
			return new ConfigMap(subMap);
		} else {
			throw new IllegalArgumentException("Invalid object value - " + key + ": " + val);
		}
	}

	/**
	 * Retrieves a list value for the specified key.
	 * <p>
	 * The value must be a List. The returned list is cast to the specified type parameter.
	 * </p>
	 *
	 * @param <T> the type of elements in the list
	 * @param key the configuration key, key must not be null
	 * @return the list value, or null if the key is not present
	 * @throws NullPointerException if the key is null
	 * @throws IllegalArgumentException if the value is not a List
	 */
	public <T> List<T> getList(String key) {
		Objects.requireNonNull(key);
		Object val = map.get(key);
		if (val == null) {
			return null;
		} else if (val instanceof List<?> l) {
			@SuppressWarnings("unchecked")
			List<T> lst = (List<T>) l;
			return lst;
		} else {
			throw new IllegalArgumentException("Invalid object value - " + key + ": " + val);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int size() {
		return map.size();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean containsKey(Object key) {
		return map.containsKey(key);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean containsValue(Object value) {
		return map.containsValue(value);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object get(Object key) {
		return map.get(key);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object put(String key, Object value) {
		return map.put(key, value);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object remove(Object key) {
		return map.remove(key);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void putAll(Map<? extends String, ?> m) {
		map.putAll(m);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void clear() {
		map.clear();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Set<String> keySet() {
		return map.keySet();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<Object> values() {
		return map.values();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Set<Entry<String, Object>> entrySet() {
		return map.entrySet();
	}
}