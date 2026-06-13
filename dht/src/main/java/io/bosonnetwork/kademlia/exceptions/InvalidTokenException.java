package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidTokenException extends KadException {
	private static final long serialVersionUID = -1757382613249878579L;

	public InvalidTokenException() {
		super(ErrorCode.InvalidToken.value());
	}

	public InvalidTokenException(String message) {
		super(ErrorCode.InvalidToken.value(), message);
	}

	public InvalidTokenException(String message, Throwable cause) {
		super(ErrorCode.InvalidToken.value(), message, cause);
	}

	public InvalidTokenException(Throwable cause) {
		super(ErrorCode.InvalidToken.value(), cause);
	}
}