package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidPeer extends KadException {
	private static final long serialVersionUID = 4811539908484276220L;

	public InvalidPeer() {
		super(ErrorCode.InvalidPeer.value());
	}

	public InvalidPeer(String message) {
		super(ErrorCode.InvalidPeer.value(), message);
	}

	public InvalidPeer(String message, Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), message, cause);
	}

	public InvalidPeer(Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), cause);
	}
}