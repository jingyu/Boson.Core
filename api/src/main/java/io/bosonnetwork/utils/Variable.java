package io.bosonnetwork.utils;

import java.util.Objects;

public class Variable<T> {
	private T value;

	public Variable() {}

	public Variable(T value) {
		this.value = value;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(value);
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof Variable that)
			return Objects.equals(this.value, that.value);

		return false;
	}

	@Override
	public String toString() {
		return Objects.toString(value);
	}
}
