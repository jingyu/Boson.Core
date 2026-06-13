package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class ProtocolException extends KadException {
	private static final long serialVersionUID = 351835645866350822L;

	public ProtocolException() {
		super(ErrorCode.ProtocolError.value());
	}

	public ProtocolException(String message) {
		super(ErrorCode.ProtocolError.value(), message);
	}

	public ProtocolException(String message, Throwable cause) {
		super(ErrorCode.ProtocolError.value(), message, cause);
	}

	public ProtocolException(Throwable cause) {
		super(ErrorCode.ProtocolError.value(), cause);
	}
}