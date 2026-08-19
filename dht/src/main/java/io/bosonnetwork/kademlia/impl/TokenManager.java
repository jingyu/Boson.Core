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
	private final int foldedToken;
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

		int t;
		do {
			t = Random.secureRandom().nextInt();
		} while (t == 0);
		foldedToken = t;
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
		// The first four-byte slice of the digest that is not zero, taken flat.
		//
		// Scanned rather than cut once, because zero is reserved to mean "no token" and must never be
		// issued as one: the digest holds eight slices, so seven further chances to avoid the fold below
		// come free. The common case breaks on the first iteration and is the same value it always was.
		//
		// This used to pick a starting offset out of digest[0] and read four bytes from there, wrapping at
		// the end of the digest, which cost entropy rather than adding any. For four of the 32 offsets the
		// wrap brought digest[0] back inside the token itself, and in those branches digest[0] is pinned by
		// the token value instead of ranging freely - so a token whose bytes fit all four of those patterns
		// turned up about five times more often than a uniform draw, and guessing one of those is the best
		// blind guess there is. Roughly 30 bits of the 32, given away for nothing.
		//
		// Nothing rested on the offset being hard to predict, either. The digest is keyed on the session
		// secret, and that is the whole of the token's strength; where in the digest it was cut from was
		// never a secret and never needed to be.
		int token = 0;
		for (int i = 0; i <= digest.length - Integer.BYTES; i += Integer.BYTES) {
			token = ((digest[i] & 0xff) << 24) |
					((digest[i + 1] & 0xff) << 16) |
					((digest[i + 2] & 0xff) << 8) |
					(digest[i + 3] & 0xff);
			if (token != 0)
				break;
		}

		return nonZero(token);
	}

	/**
	 * Maps a token of 0 onto this manager's folded value, leaving every other value alone.
	 * <p>
	 * Zero is reserved to mean "no token" wherever a token travels, so it must never be issued as one.
	 * The scan in {@link #generateToken} is what keeps that from arising - every one of the digest's
	 * eight slices would have to be zero, which is to say the whole digest - and this is the backstop
	 * that makes the guarantee unconditional rather than merely overwhelming. Since {@link #verifyToken}
	 * recomputes through the same instance, the two sides cannot drift apart over it.
	 * </p>
	 * <p>
	 * The folded value is drawn at random over the whole non-zero range, once per manager, and rotates
	 * on restart along with the session secret. Nothing an attacker can reach depends on what it is: the
	 * branch needs an all-zero digest, and the token's strength is the session secret keying that digest
	 * rather than anything about this value. It is unguessable because there is no reason for it to be
	 * anything else, not because a guess would buy something.
	 * </p>
	 * <p>
	 * Package-private because no input reaches the zero branch on purpose - a caller would have to search
	 * for an all-zero digest - so a test pins it here rather than pretending to reach it.
	 * </p>
	 *
	 * @param token the token cut from the digest
	 * @return the token, or this manager's folded value if it was 0
	 */
	int nonZero(int token) {
		return token != 0 ? token : foldedToken;
	}

	public int generateToken(Id nodeId, InetSocketAddress address, Id targetId) {
		return generateToken(nodeId, address.getAddress(), address.getPort(), targetId, timestamp.get());
	}

	public int generateToken(Id nodeId, InetAddress address, int port, Id targetId) {
		return generateToken(nodeId, address, port, targetId, timestamp.get());
	}

	public boolean verifyToken(int token, Id nodeId, InetAddress address, int port, Id targetId) {
		// Zero is the reserved "no token" value and is never issued, so a request carrying it is one that
		// carries none. Belt and braces while nonZero holds - a recomputed token is never zero either, so
		// the comparison below would refuse it anyway - and stated here so the rule the protocol makes
		// normative is enforced where a reader looks for it, not left resting on how tokens are derived.
		if (token == 0)
			return false;

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
