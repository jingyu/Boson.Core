package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class ProtocolError extends KadException {
	private static final long serialVersionUID = 351835645866350822L;

	public ProtocolError() {
		super(ErrorCode.ProtocolError.value());
	}

	public ProtocolError(String message) {
		super(ErrorCode.InvalidPeer.value(), message);
	}

	public ProtocolError(String message, Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), message, cause);
	}

	public ProtocolError(Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), cause);
	}
}