/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.security;

import io.vertx.core.net.SocketAddress;

import io.bosonnetwork.Id;

/**
 * Disabled SuspiciousNodeDetector implementation.
 * @see SuspiciousNodeDetector
 */
public class DisabledSuspiciousNodeDetector implements SuspiciousNodeDetector {
	protected DisabledSuspiciousNodeDetector() {
	}

	@Override
	public boolean isSuspicious(SocketAddress addr, Id expected) {
		return false;
	}

	@Override
	public boolean isSuspicious(SocketAddress addr) {
		return false;
	}

	@Override
	public boolean isBanned(String host) {
		return false;
	}

	@Override
	public boolean isBanned(SocketAddress addr) {
		return false;
	}

	@Override
	public Id lastKnownId(SocketAddress addr) {
		return null;
	}

	@Override
	public void observe(SocketAddress addr, Id id) {
	}

	@Override
	public void malformedMessage(SocketAddress addr) {
	}

	@Override
	public void inconsistent(SocketAddress addr, Id id) {
	}

	@Override
	public long getObservedSize() {
		return 0;
	}

	@Override
	public long getBannedSize() {
		return 0;
	}

	@Override
	public void purge() {
	}

	@Override
	public void clear() {
	}
}