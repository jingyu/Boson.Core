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

package io.bosonnetwork.kademlia.impl;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.net.SocketAddress;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;

/**
 * @hidden
 */
public class TokenManager {
	public static final int	TOKEN_TIMEOUT = 5 * 60 * 1000;	// 5 minutes

	private final byte[] sessionSecret;
	private final AtomicLong timestamp;
	private volatile long previousTimestamp;

	public TokenManager() {
		this.sessionSecret = new byte[32];
		Random.secureRandom().nextBytes(sessionSecret);
		timestamp = new AtomicLong(System.currentTimeMillis());
	}

	public void updateTokenTimestamps() {
		long current = timestamp.get();
		long now = System.currentTimeMillis();
		while (now - current > TOKEN_TIMEOUT) {
			if (timestamp.compareAndSet(current, now)) {
				previousTimestamp = current;
				break;
			}
			current = timestamp.get();
		}
	}

	private int generateToken(Id nodeId, InetAddress address, int port, Id targetId, long timestamp) {
		MessageDigest sha256 = Hash.sha256();
		sha256.update(nodeId.bytes());
		sha256.update(address.getAddress());
		sha256.update(ByteBuffer.allocate(Short.BYTES).putShort((short)port).array());
		sha256.update(targetId.bytes());
		sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array());
		sha256.update(sessionSecret);
		byte[] digest = sha256.digest();
		int pos = (digest[0] & 0xff) & 0x1f; // mod 32
		return ((digest[pos] & 0xff) << 24) |
				((digest[(pos + 1) & 0x1f] & 0xff) << 16) |
				((digest[(pos + 2) & 0x1f] & 0xff) << 8) |
				(digest[(pos + 3) & 0x1f] & 0xff);
	}

	public int generateToken(Id nodeId, InetSocketAddress address, Id targetId) {
		return generateToken(nodeId, address.getAddress(), address.getPort(), targetId, timestamp.get());
	}

	public int generateToken(Id nodeId, SocketAddress address, Id targetId) {
		try {
			return generateToken(nodeId, InetAddress.getByName(address.hostAddress()), address.port(), targetId, timestamp.get());
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException(e);
		}
	}

	public int generateToken(Id nodeId, InetAddress address, int port, Id targetId) {
		return generateToken(nodeId, address, port, targetId, timestamp.get());
	}

	public boolean verifyToken(int token, Id nodeId, InetAddress address, int port, Id targetId) {
		int currentToken = generateToken(nodeId, address, port, targetId, timestamp.get());
		if (token == currentToken)
			return true;

		int previousToken = generateToken(nodeId, address, port, targetId, previousTimestamp);
		return token == previousToken;
	}

	public boolean verifyToken(int token, Id nodeId, InetSocketAddress address, Id targetId) {
		return verifyToken(token, nodeId, address.getAddress(), address.getPort(), targetId);
	}

	public boolean verifyToken(int token, Id nodeId, SocketAddress address, Id targetId) {
		try {
			return verifyToken(token, nodeId, InetAddress.getByName(address.hostAddress()), address.port(), targetId);
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException(e);
		}
	}
}