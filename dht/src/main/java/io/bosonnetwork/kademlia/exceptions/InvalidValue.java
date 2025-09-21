package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidValue extends KadException {
	private static final long serialVersionUID = 5229550026295245992L;

	public InvalidValue() {
		super(ErrorCode.InvalidValue.value());
	}

	public InvalidValue(String message) {
		super(ErrorCode.InvalidValue.value(), message);
	}

	public InvalidValue(String message, Throwable cause) {
		super(ErrorCode.InvalidValue.value(), message, cause);
	}

	public InvalidValue(Throwable cause) {
		super(ErrorCode.InvalidValue.value(), cause);
	}
}