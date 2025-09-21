package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidToken extends KadException {
	private static final long serialVersionUID = -1757382613249878579L;

	public InvalidToken() {
		super(ErrorCode.InvalidToken.value());
	}

	public InvalidToken(String message) {
		super(ErrorCode.InvalidToken.value(), message);
	}

	public InvalidToken(String message, Throwable cause) {
		super(ErrorCode.InvalidToken.value(), message, cause);
	}

	public InvalidToken(Throwable cause) {
		super(ErrorCode.InvalidToken.value(), cause);
	}
}