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

package io.bosonnetwork.kademlia.metrics;

import io.vertx.core.net.SocketAddress;

import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.metrics.Metrics;

public interface DHTMetrics extends Metrics {
	enum Reason {
		INVALID,
		BANNED,
		SUSPICIOUS,
		THROTTLED,
		INCONSISTENT,
		NO_MATCHED_CALL
	}

	/**
	 * Called when bytes have been read
	 *
	 * @param remoteAddress the remote address which this socket received bytes from
	 * @param numberOfBytes the number of bytes read
	 */
	default void bytesRead(SocketAddress remoteAddress, long numberOfBytes) {
	}

	default void bytesDropped(SocketAddress remoteAddress, long numberOfBytes) {
	}

	/**
	 * Called when bytes have been written
	 *
	 * @param remoteAddress the remote address which bytes are being written to
	 * @param numberOfBytes the number of bytes written
	 */
	default void bytesWritten(SocketAddress remoteAddress, long numberOfBytes) {
	}

	default void messageReceived(SocketAddress remoteAddress) {
	}

	default void messageDropped(SocketAddress remoteAddress, Reason reason) {
	}

	default void messageSent(SocketAddress remoteAddress) {
	}

	default void messageSendFailed(SocketAddress remoteAddress, Throwable error) {
	}

	// requestReceived - process - responseSent
	default void requestReceived(Message request) {
	}

	default void responseSent(Message response) {
	}

	// requestSent - wanting for response - responseReceived | responseTimeout
	default void requestSent(Message request) {
	}

	default void responseReceived(Message response) {
	}

	default void responseTimeout(Message request) {
	}

	default void verifiedLossRateUpdate(double rate) {
	}

	default void unverifiedLossRateUpdate(double rate) {
	}

	default void throttledInbound(String host) {
	}

	default void throttledOutbound(String host, int delay) {
	}

	/**
	 * Called when exceptions occur for a specific connection.
	 *
	 * @param error the exception that occurred
	 */
	default void exceptionOccurred(Throwable error) {
	}
}