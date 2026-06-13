package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidValueException extends KadException {
	private static final long serialVersionUID = 5229550026295245992L;

	public InvalidValueException() {
		super(ErrorCode.InvalidValue.value());
	}

	public InvalidValueException(String message) {
		super(ErrorCode.InvalidValue.value(), message);
	}

	public InvalidValueException(String message, Throwable cause) {
		super(ErrorCode.InvalidValue.value(), message, cause);
	}

	public InvalidValueException(Throwable cause) {
		super(ErrorCode.InvalidValue.value(), cause);
	}
}