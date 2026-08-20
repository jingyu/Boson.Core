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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.vertx.core.Promise;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.AnnounceFailedException;
import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.kademlia.exceptions.InvalidTokenException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpectedException;

/**
 * How a set of per-node answers is reported to the caller of a publish.
 * <p>
 * The interesting half is the cause. {@link AnnounceResult} carries a refusal as the code and message the
 * wire actually delivered, and it is here - in the module that owns the error taxonomy - that a code
 * becomes the exception type a caller names in a {@code catch}. Nothing else in the codebase performs
 * that mapping on an announce, so nothing else can be relied on to keep it working.
 * </p>
 */
public class CompleteAnnounceTests {
	private static AnnounceResult.Target refused(int code, String message) {
		return new AnnounceResult.Target(Id.random(), AnnounceResult.Outcome.REFUSED,
				new AnnounceResult.Cause(code, message));
	}

	private static Throwable failureOf(AnnounceResult result) {
		Promise<AnnounceResult> promise = Promise.promise();
		DHT.completeAnnounce(promise, result, "Value was not stored");
		assertTrue(promise.future().failed(), "a publish nobody took is reported as a failure");
		return promise.future().cause();
	}

	@Test
	void testAUnanimousRefusalIsRebuiltAsTheTypeACallerCatches() {
		// The reason the result carries a code at all: a caller that supplied a sequence number is
		// asking whether it lost the compare-and-set, and the answer has to arrive as something it can
		// catch rather than as a number it has to look up.
		AnnounceResult result = AnnounceResult.of(List.of(
				refused(ErrorCode.CasFail.value(), "stale, expected 7"),
				refused(ErrorCode.CasFail.value(), "sequence number is not expected")));

		Throwable failure = failureOf(result);
		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class, failure);
		assertInstanceOf(SequenceNotExpectedException.class, afe.getCause());
		assertSame(result, afe.getResult(), "the per-node detail travels with it either way");
	}

	@Test
	void testAMixedRefusalCarriesNoTypedCause() {
		// No single node gets to decide what the caller catches.
		AnnounceResult result = AnnounceResult.of(List.of(
				refused(ErrorCode.CasFail.value(), "stale, expected 7"),
				refused(ErrorCode.InvalidToken.value(), "Invalid token")));

		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class, failureOf(result));
		assertNull(afe.getCause(), "disagreement is not a network verdict");
		assertSame(result, afe.getResult());
	}

	@Test
	void testAnUnrecognisedCodeStillFailsWithItsCode() {
		AnnounceResult result = AnnounceResult.of(List.of(refused(4242, "something new")));

		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class, failureOf(result));
		assertEquals(4242, assertInstanceOf(io.bosonnetwork.kademlia.exceptions.KadException.class,
				afe.getCause()).getCode());
	}

	@Test
	void testSilenceIsNotARefusal() {
		AnnounceResult result = AnnounceResult.of(List.of(
				new AnnounceResult.Target(Id.random(), AnnounceResult.Outcome.TIMED_OUT, null)));

		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class, failureOf(result));
		assertNull(afe.getCause(), "a node that never answered claimed nothing");
	}

	@Test
	void testAnythingShortOfTotalRefusalCompletes() {
		// One node refusing must not fail the publish - that would hand any of the closest nodes a veto.
		AnnounceResult partial = AnnounceResult.of(List.of(
				new AnnounceResult.Target(Id.random(), AnnounceResult.Outcome.ACKNOWLEDGED, null),
				refused(ErrorCode.InvalidValue.value(), "Invalid value")));

		Promise<AnnounceResult> promise = Promise.promise();
		DHT.completeAnnounce(promise, partial, "Value was not stored");
		assertTrue(promise.future().succeeded());
		assertSame(partial, promise.future().result());

		// Nor must finding nobody to ask, which is the ordinary state of a node still bootstrapping.
		Promise<AnnounceResult> empty = Promise.promise();
		DHT.completeAnnounce(empty, AnnounceResult.of(List.of()), "Value was not stored");
		assertTrue(empty.future().succeeded());
	}

	@Test
	void testTheFailureMessageNamesThePayloadAndTheResult() {
		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class,
				failureOf(AnnounceResult.of(List.of(refused(ErrorCode.InvalidToken.value(), "Invalid token")))));
		assertTrue(afe.getMessage().startsWith("Value was not stored: "), afe.getMessage());
	}

	@Test
	void testAnInvalidTokenIsAlsoRebuilt() {
		AnnounceFailedException afe = assertInstanceOf(AnnounceFailedException.class,
				failureOf(AnnounceResult.of(List.of(
						refused(ErrorCode.InvalidToken.value(), "Invalid token"),
						refused(ErrorCode.InvalidToken.value(), "Invalid token for STORE VALUE request")))));
		assertInstanceOf(InvalidTokenException.class, afe.getCause());
	}
}
