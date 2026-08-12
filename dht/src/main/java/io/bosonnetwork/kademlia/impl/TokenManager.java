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
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Hash;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.utils.Bytes;

/**
 * Issues and verifies the anti-spoofing token that gates STORE_VALUE and ANNOUNCE_PEER.
 * <p>
 * REMARK: the address is taken as an {@link InetAddress}, never as a host string. There used to be
 * overloads accepting a Vert.x {@code SocketAddress}, which resolved it with
 * {@code InetAddress.getByName(address.hostAddress())} - a blocking DNS lookup on the event loop for any
 * address that was not already a literal. Callers on the message path have the parsed literal in hand
 * ({@code Message.getRemoteIpAddress()}), so the overloads are gone rather than guarded.
 * </p>
 *
 * @hidden
 */
public class TokenManager {
	public static final int	TOKEN_TIMEOUT = 5 * 60 * 1000;	// 5 minutes

	/**
	 * How often {@link #updateTokenTimestamps()} should be called.
	 * <p>
	 * Rotation happens on the first call that finds the window already expired, so the timer period is
	 * the granularity of the rotation, not the rotation period itself. Driving it at {@code TOKEN_TIMEOUT}
	 * made a window last between one and two timeouts depending on where the timer's own jitter fell;
	 * checking five times per window bounds that overshoot to a fifth of a window.
	 * </p>
	 */
	public static final int ROTATION_CHECK_INTERVAL = TOKEN_TIMEOUT / 5;	// 1 minute

	private final byte[] sessionSecret;
	private final AtomicLong timestamp;
	private volatile long previousTimestamp;

	public TokenManager() {
		this.sessionSecret = new byte[32];
		Random.secureRandom().nextBytes(sessionSecret);
		long now = System.currentTimeMillis();
		timestamp = new AtomicLong(now);
		// Seeded to the current window rather than left at 0: the previous-window fallback derives a
		// second acceptable token from this value, and until the first rotation that second token would
		// otherwise be the one keyed on timestamp 0 - a whole extra token accepted for no reason, from
		// startup onwards. Equal to timestamp means the fallback simply recomputes the current token.
		previousTimestamp = now;
	}

	public void updateTokenTimestamps() {
		updateTokenTimestamps(System.currentTimeMillis());
	}

	/**
	 * Rotates the token timestamps as of the given wall-clock time.
	 * <p>
	 * Package-private so a test can drive the rotation without waiting out {@link #TOKEN_TIMEOUT};
	 * production code calls the no-argument form.
	 * </p>
	 *
	 * @param now the current time in milliseconds.
	 */
	void updateTokenTimestamps(long now) {
		long current = timestamp.get();
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
		sha256.update(nodeId.bytesUnsafe());
		sha256.update(address.getAddress());
		sha256.update(Bytes.fromShort((short)port));
		sha256.update(targetId.bytesUnsafe());
		sha256.update(Bytes.fromLong(timestamp));
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
}