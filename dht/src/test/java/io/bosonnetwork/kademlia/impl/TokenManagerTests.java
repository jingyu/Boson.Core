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

package io.bosonnetwork.kademlia.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

/**
 * Tests for the anti-spoofing token that gates STORE_VALUE and ANNOUNCE_PEER.
 * <p>
 * The property being pinned is that a token is bound to all four of sender id, address, port and
 * target: accepting one that is not is what would let an attacker announce against an address it does
 * not hold. The rotation half matters just as much in the other direction - a token that stops
 * verifying too early rejects a well-behaved node whose announce was in flight across the boundary,
 * which is why the previous window is honoured at all.
 * </p>
 */
public class TokenManagerTests {
	private static final int PORT = 39001;

	private TokenManager tokenManager;
	private Id nodeId;
	private Id targetId;
	private InetAddress address;

	@BeforeEach
	void setup() throws UnknownHostException {
		tokenManager = new TokenManager();
		nodeId = Id.random();
		targetId = Id.random();
		address = InetAddress.getByName("192.168.1.100");
	}

	private int generate() {
		return tokenManager.generateToken(nodeId, address, PORT, targetId);
	}

	private boolean verify(int token) {
		return tokenManager.verifyToken(token, nodeId, address, PORT, targetId);
	}

	/**
	 * A rotation boundary, expressed as a time far enough past the last one to cross it. The manager
	 * stamps itself from the wall clock in its constructor, so the offsets are taken from now rather
	 * than from a captured start value.
	 */
	private static long afterRotations(int rotations) {
		return System.currentTimeMillis() + rotations * (TokenManager.TOKEN_TIMEOUT + 1000L);
	}

	@Test
	@DisplayName("a freshly generated token verifies")
	void generatedTokenVerifies() {
		assertTrue(verify(generate()));
	}

	@Test
	@DisplayName("the token is stable while the window is")
	void tokenIsStableWithinWindow() {
		assertEquals(generate(), generate());
	}

	@Test
	@DisplayName("an arbitrary token does not verify")
	void arbitraryTokenDoesNotVerify() {
		int token = generate();
		// Not a random int: that would pass by luck once every 4 billion runs. Perturbing the real
		// token also tests the comparison rather than the generator.
		assertFalse(verify(token + 1));
		assertFalse(verify(~token));
		assertFalse(verify(0));
	}

	@Test
	@DisplayName("the token is bound to the sender id")
	void tokenIsBoundToNodeId() {
		int token = generate();
		assertFalse(tokenManager.verifyToken(token, Id.random(), address, PORT, targetId));
	}

	@Test
	@DisplayName("the token is bound to the sender address")
	void tokenIsBoundToAddress() throws UnknownHostException {
		int token = generate();
		InetAddress other = InetAddress.getByName("192.168.1.101");
		assertFalse(tokenManager.verifyToken(token, nodeId, other, PORT, targetId));
	}

	@Test
	@DisplayName("the token is bound to the sender port")
	void tokenIsBoundToPort() {
		int token = generate();
		assertFalse(tokenManager.verifyToken(token, nodeId, address, PORT + 1, targetId));
	}

	@Test
	@DisplayName("the token is bound to the target id")
	void tokenIsBoundToTarget() {
		int token = generate();
		assertFalse(tokenManager.verifyToken(token, nodeId, address, PORT, Id.random()));
	}

	@Test
	@DisplayName("tokens are bound to the session secret, not derivable from the inputs")
	void tokenIsBoundToSessionSecret() {
		int token = generate();
		// Same four inputs, different manager: the secret is what the sender cannot compute around.
		TokenManager other = new TokenManager();
		assertFalse(other.verifyToken(token, nodeId, address, PORT, targetId));
		assertNotEquals(token, other.generateToken(nodeId, address, PORT, targetId));
	}

	@Test
	@DisplayName("an IPv6 sender is bound the same way")
	void ipv6IsBound() throws UnknownHostException {
		InetAddress v6 = InetAddress.getByName("2001:db8::1");
		InetAddress otherV6 = InetAddress.getByName("2001:db8::2");

		int token = tokenManager.generateToken(nodeId, v6, PORT, targetId);
		assertTrue(tokenManager.verifyToken(token, nodeId, v6, PORT, targetId));
		assertFalse(tokenManager.verifyToken(token, nodeId, otherV6, PORT, targetId));
	}

	@Test
	@DisplayName("the InetSocketAddress overloads agree with the address/port form")
	void socketAddressOverloadsAgree() {
		InetSocketAddress socketAddress = new InetSocketAddress(address, PORT);

		int token = tokenManager.generateToken(nodeId, socketAddress, targetId);
		assertEquals(generate(), token);
		assertTrue(tokenManager.verifyToken(token, nodeId, socketAddress, targetId));
		assertTrue(verify(token));
	}

	@Test
	@DisplayName("the rotation is checked several times per window")
	void rotationIsCheckedMoreOftenThanItRotates() {
		// The rotation happens on the first check that finds the window expired, so a check interval
		// equal to the timeout leaves the overshoot up to the timer's jitter - a window lasting anywhere
		// between one and two timeouts. This is the relationship that bounds it; the exact ratio is free.
		assertTrue(TokenManager.ROTATION_CHECK_INTERVAL < TokenManager.TOKEN_TIMEOUT);
	}

	@Test
	@DisplayName("no rotation happens inside the timeout")
	void noRotationInsideTimeout() {
		int token = generate();

		tokenManager.updateTokenTimestamps(System.currentTimeMillis());

		// Same window, so the same token is still the one being issued.
		assertEquals(token, generate());
		assertTrue(verify(token));
	}

	@Test
	@DisplayName("a token from the previous window still verifies after one rotation")
	void previousWindowTokenStillVerifies() {
		int previous = generate();

		tokenManager.updateTokenTimestamps(afterRotations(1));

		int current = generate();
		// The rotation has to actually issue something new, or the fallback below proves nothing.
		assertNotEquals(previous, current);
		assertTrue(verify(current));
		assertTrue(verify(previous), "a token issued in the previous window must still be accepted");
	}

	@Test
	@DisplayName("a token stops verifying after two rotations")
	void tokenExpiresAfterTwoRotations() {
		int first = generate();

		tokenManager.updateTokenTimestamps(afterRotations(1));
		int second = generate();

		tokenManager.updateTokenTimestamps(afterRotations(2));
		int third = generate();

		assertTrue(verify(third));
		assertTrue(verify(second), "the window that just closed is the fallback");
		assertFalse(verify(first), "two windows back must no longer be accepted");
	}

	@Test
	@DisplayName("zero is never issued as a token")
	void zeroIsNeverIssued() {
		// The branch itself is not reachable from any input a caller can choose - producing it would mean
		// finding an all-zero digest - so the fold is pinned where the decision is rather than through it.
		assertNotEquals(0, tokenManager.nonZero(0), "zero is reserved to mean \"no token\" and must not be issued");
		assertEquals(1, tokenManager.nonZero(1), "and a token of one is a token, not the fold");
		assertEquals(0x87654321, tokenManager.nonZero(0x87654321));
		assertEquals(-1, tokenManager.nonZero(-1), "the token is 32 bits of digest, sign included");
	}

	@Test
	@DisplayName("a token of zero is refused")
	void zeroIsRefused() {
		// A request carrying zero is one carrying no token at all. Refused for that reason rather than
		// left to fail the comparison, which is a distinction only a broken or hostile peer will notice.
		assertFalse(verify(0));
	}

	@Test
	@DisplayName("rotation keeps the binding to the four inputs")
	void rotationKeepsBinding() {
		int token = generate();
		tokenManager.updateTokenTimestamps(afterRotations(1));

		// The previous-window fallback must not be a hole in the binding: it re-derives the token from
		// the same four inputs, so a mismatched one has to fail against both windows.
		assertTrue(verify(token));
		assertFalse(tokenManager.verifyToken(token, Id.random(), address, PORT, targetId));
		assertFalse(tokenManager.verifyToken(token, nodeId, address, PORT + 1, targetId));
		assertFalse(tokenManager.verifyToken(token, nodeId, address, PORT, Id.random()));
	}
}
