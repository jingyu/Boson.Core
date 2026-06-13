package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

public class InvalidPeerException extends KadException {
	private static final long serialVersionUID = 4811539908484276220L;

	public InvalidPeerException() {
		super(ErrorCode.InvalidPeer.value());
	}

	public InvalidPeerException(String message) {
		super(ErrorCode.InvalidPeer.value(), message);
	}

	public InvalidPeerException(String message, Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), message, cause);
	}

	public InvalidPeerException(Throwable cause) {
		super(ErrorCode.InvalidPeer.value(), cause);
	}
}