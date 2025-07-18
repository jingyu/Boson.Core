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

package io.bosonnetwork.kademlia;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;

/**
 * @hidden
 */
public class TokenManager {
	private final AtomicLong timestamp = new AtomicLong();
	private volatile long previousTimestamp;
	private final byte[] sessionSecret = new byte[32];

	TokenManager() {
		Random.secureRandom().nextBytes(sessionSecret);
	}

	void updateTokenTimestamps() {
		long current = timestamp.get();
		long now = System.nanoTime();
		while (TimeUnit.NANOSECONDS.toMillis(now - current) > Constants.TOKEN_TIMEOUT) {
			if (timestamp.compareAndSet(current, now)) {
				previousTimestamp = current;
				break;
			}
			current = timestamp.get();
		}
	}

	private static int generateToken(Id nodeId, InetAddress ip, int port, Id targetId, long timestamp, byte[] sessionSecret) {
		byte[] tokData = new byte[Id.BYTES * 2 + ip.getAddress().length + 2 + 8 + sessionSecret.length];

		// nodeId + ip + port + targetId + timestamp + sessionSecret
		ByteBuffer bb = ByteBuffer.wrap(tokData);
		bb.put(nodeId.bytes());
		bb.put(ip.getAddress());
		bb.putShort((short)port);
		bb.put(targetId.bytes());
		bb.putLong(timestamp);
		bb.put(sessionSecret);

		byte[] digest = Hash.sha256().digest(tokData);
		int pos = (digest[0] & 0xff) & 0x1f; // mod 32
		return ((digest[pos] & 0xff) << 24) |
				((digest[(pos + 1) & 0x1f] & 0xff) << 16) |
				((digest[(pos + 2) & 0x1f] & 0xff) << 8) |
				(digest[(pos + 3) & 0x1f] & 0xff);
	}

	public int generateToken(Id nodeId, InetSocketAddress addr, Id targetId) {
		updateTokenTimestamps();
		return generateToken(nodeId, addr.getAddress(), addr.getPort(), targetId, timestamp.get(), sessionSecret);
	}

	public int generateToken(Id nodeId, InetAddress ip, int port, Id targetId) {
		updateTokenTimestamps();
		return generateToken(nodeId, ip, port, targetId, timestamp.get(), sessionSecret);
	}

	public boolean verifyToken(int token, Id nodeId, InetAddress ip, int port, Id targetId) {
		updateTokenTimestamps();
		int currentToken = generateToken(nodeId, ip, port, targetId, timestamp.get(), sessionSecret);
		if (token == currentToken)
			return true;

		int previousToken = generateToken(nodeId, ip, port, targetId, previousTimestamp, sessionSecret);
		return token == previousToken;
	}

	public boolean verifyToken(int token, Id nodeId, InetSocketAddress addr, Id targetId) {
		return verifyToken(token, nodeId, addr.getAddress(), addr.getPort(), targetId);
	}
}