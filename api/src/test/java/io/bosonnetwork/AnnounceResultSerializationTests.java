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

package io.bosonnetwork;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.json.Json;

/**
 * The wire form of {@link AnnounceResult}, as the web gateway service and client exchange it.
 * <p>
 * Both encodings are covered because they disagree about how an {@link Id} is written - Base58 text in
 * JSON, raw binary in CBOR - and a codec that gets that wrong still round-trips through itself.
 * </p>
 */
public class AnnounceResultSerializationTests {
	private static final Id ACKNOWLEDGED = Id.random();
	private static final Id REFUSED = Id.random();
	private static final Id SILENT = Id.random();
	private static final Id UNSENT = Id.random();

	/** One of each outcome: with a full cause, with a message-less cause, and with none at all. */
	private static AnnounceResult mixed() {
		return AnnounceResult.of(List.of(
				new AnnounceResult.Target(ACKNOWLEDGED, AnnounceResult.Outcome.ACKNOWLEDGED, null),
				new AnnounceResult.Target(REFUSED, AnnounceResult.Outcome.REFUSED,
						new AnnounceResult.Cause(301, "Sequence number not expected")),
				new AnnounceResult.Target(SILENT, AnnounceResult.Outcome.TIMED_OUT, null),
				new AnnounceResult.Target(UNSENT, AnnounceResult.Outcome.NOT_SENT,
						new AnnounceResult.Cause(1, null))));
	}

	private static JsonNode tree(String json) {
		try {
			return Json.objectMapper().readTree(json);
		} catch (Exception e) {
			throw new AssertionError("the codec did not produce readable JSON", e);
		}
	}

	/** Whether {@code needle} appears anywhere in {@code haystack}. */
	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j])
					continue outer;
			}
			return true;
		}
		return false;
	}

	@Test
	void testJsonRoundTrip() {
		AnnounceResult result = mixed();
		AnnounceResult parsed = Json.parse(Json.toString(result), AnnounceResult.class);

		// Compared on the targets rather than on the object: they are the only thing carried, and the
		// aggregate below is asserted separately precisely because it is recomputed and not read.
		assertEquals(result.targets(), parsed.targets());
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, parsed.status());
		assertEquals(1, parsed.acknowledged());
		assertTrue(parsed.isAnnounced());
	}

	@Test
	void testCborRoundTrip() {
		AnnounceResult result = mixed();
		AnnounceResult parsed = Json.parse(Json.toBytes(result), AnnounceResult.class);

		assertEquals(result.targets(), parsed.targets());
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, parsed.status());
		assertEquals(1, parsed.acknowledged());
	}

	@Test
	void testJsonWritesIdsAsBase58AndOutcomesInLowerCase() {
		JsonNode root = tree(Json.toString(mixed()));

		JsonNode targets = root.get("targets");
		assertEquals(4, targets.size());
		assertEquals(ACKNOWLEDGED.toBase58String(), targets.get(0).get("id").asText());
		assertEquals("acknowledged", targets.get(0).get("outcome").asText());
		assertEquals("refused", targets.get(1).get("outcome").asText());
		assertEquals("timed_out", targets.get(2).get("outcome").asText());
		assertEquals("not_sent", targets.get(3).get("outcome").asText());
	}

	@Test
	void testCborWritesIdsAsBinary() {
		// The bytes themselves, not a Base58 rendering of them: an id written as text would still decode
		// through our own reader, so the encoding has to be checked against the buffer.
		byte[] cbor = Json.toBytes(mixed());
		assertTrue(contains(cbor, ACKNOWLEDGED.bytesUnsafe()), "the id should be on the wire as raw bytes");
		assertFalse(new String(cbor, ISO_8859_1).contains(ACKNOWLEDGED.toBase58String()),
				"CBOR should not carry the Base58 form");
	}

	@Test
	void testACauseIsOmittedWhereThereIsNoneAndSoIsItsMessage() {
		JsonNode targets = tree(Json.toString(mixed())).get("targets");

		assertFalse(targets.get(0).has("cause"), "an acknowledgement has nothing to explain");
		assertFalse(targets.get(2).has("cause"), "a silence has nothing to explain");

		JsonNode cause = targets.get(1).get("cause");
		assertEquals(301, cause.get("code").asInt());
		assertEquals("Sequence number not expected", cause.get("message").asText());

		JsonNode messageless = targets.get(3).get("cause");
		assertEquals(1, messageless.get("code").asInt());
		assertFalse(messageless.has("message"), "a null message is left out rather than written as null");
	}

	@Test
	void testAnEmptyResultSurvivesAsNoTargets() {
		// The distinction the format has to preserve without carrying a status: nobody was asked, which
		// is not an error, versus everybody refused, which is.
		AnnounceResult empty = AnnounceResult.of(List.of());
		assertEquals(AnnounceResult.Status.NO_TARGETS, empty.status());

		AnnounceResult json = Json.parse(Json.toString(empty), AnnounceResult.class);
		assertEquals(AnnounceResult.Status.NO_TARGETS, json.status());
		assertFalse(json.isFailure());

		AnnounceResult cbor = Json.parse(Json.toBytes(empty), AnnounceResult.class);
		assertEquals(AnnounceResult.Status.NO_TARGETS, cbor.status());
	}

	@Test
	void testAllRefusedSurvivesAsFailed() {
		AnnounceResult failed = AnnounceResult.of(List.of(
				new AnnounceResult.Target(REFUSED, AnnounceResult.Outcome.REFUSED,
						new AnnounceResult.Cause(400, "Invalid token"))));

		AnnounceResult parsed = Json.parse(Json.toString(failed), AnnounceResult.class);
		assertEquals(AnnounceResult.Status.FAILED, parsed.status());
		assertTrue(parsed.isFailure());
		assertFalse(parsed.isAnnounced());
	}

	@Test
	void testTheSameNodeTwiceSurvivesAsTwoEntries() {
		// A dual-stack publish merges two address families, and the same node answers under one id in
		// both. Keying the format on the id would silently drop half of that.
		AnnounceResult merged = AnnounceResult.of(List.of(
				new AnnounceResult.Target(REFUSED, AnnounceResult.Outcome.ACKNOWLEDGED, null),
				new AnnounceResult.Target(REFUSED, AnnounceResult.Outcome.TIMED_OUT, null)));

		AnnounceResult parsed = Json.parse(Json.toString(merged), AnnounceResult.class);
		assertEquals(2, parsed.targets().size());
		assertEquals(AnnounceResult.Status.PARTIAL_SUCCESS, parsed.status());
	}

	@Test
	void testUnanimousRefusalSurvivesTheRoundTrip() {
		AnnounceResult agreed = AnnounceResult.of(List.of(
				new AnnounceResult.Target(REFUSED, AnnounceResult.Outcome.REFUSED,
						new AnnounceResult.Cause(301, "stale, expected 7")),
				new AnnounceResult.Target(UNSENT, AnnounceResult.Outcome.REFUSED,
						new AnnounceResult.Cause(301, "sequence number is not expected"))));

		AnnounceResult parsed = Json.parse(Json.toString(agreed), AnnounceResult.class);
		AnnounceResult.Cause unanimous = parsed.unanimousRefusal();
		assertNotNull(unanimous, "agreement is on the code, not on each node's wording");
		assertEquals(301, unanimous.code());
	}

	@Test
	void testAnUnknownOutcomeIsRejected() {
		// Rather than defaulting: an outcome this build does not know, quietly mapped onto one it does,
		// would report a publish as something other than what happened.
		String json = "{\"targets\":[{\"id\":\"" + REFUSED.toBase58String() + "\",\"outcome\":\"deferred\"}]}";
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Json.parse(json, AnnounceResult.class));
		assertTrue(e.getCause().getMessage().contains("unknown outcome deferred"), e.getCause().getMessage());
	}

	@Test
	void testOutcomesAreAcceptedWithoutRegardToCase() {
		String json = "{\"targets\":[{\"id\":\"" + REFUSED.toBase58String() + "\",\"outcome\":\"ACKNOWLEDGED\"}]}";
		AnnounceResult parsed = Json.parse(json, AnnounceResult.class);
		assertEquals(AnnounceResult.Outcome.ACKNOWLEDGED, parsed.targets().get(0).outcome());
	}

	@Test
	void testAMissingTargetsArrayIsNotAnEmptyOne() {
		// "Nobody was asked" is an answer a caller acts on, and must not be invented from a message that
		// never carried the field.
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{}", AnnounceResult.class));
	}

	@Test
	void testACauseWithoutACodeIsRejected() {
		// Defaulting the code would put ErrorCode.Success on a target that failed.
		String json = "{\"targets\":[{\"id\":\"" + REFUSED.toBase58String() +
				"\",\"outcome\":\"refused\",\"cause\":{\"message\":\"no code here\"}}]}";
		assertThrows(IllegalArgumentException.class, () -> Json.parse(json, AnnounceResult.class));
	}

	@Test
	void testATargetWithoutAnIdOrAnOutcomeIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> Json.parse("{\"targets\":[{\"outcome\":\"refused\"}]}", AnnounceResult.class));
		assertThrows(IllegalArgumentException.class,
				() -> Json.parse("{\"targets\":[{\"id\":\"" + REFUSED.toBase58String() + "\"}]}", AnnounceResult.class));
	}

	@Test
	void testUnknownFieldsAreSkipped() {
		// Room for the format to grow a field without every existing reader having to be updated first.
		String json = "{\"status\":\"partial_success\",\"targets\":[{\"id\":\"" + REFUSED.toBase58String() +
				"\",\"outcome\":\"refused\",\"cause\":{\"code\":401,\"detail\":\"ignored\"},\"rtt\":42}]}";
		AnnounceResult parsed = Json.parse(json, AnnounceResult.class);
		assertEquals(1, parsed.targets().size());
		assertEquals(401, parsed.targets().get(0).cause().code());
		assertNull(parsed.targets().get(0).cause().message());
	}
}
