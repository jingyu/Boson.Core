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

package io.bosonnetwork.kademlia.messages;

import java.io.IOException;

import io.bosonnetwork.kademlia.messages2.Message2;
import io.bosonnetwork.utils.Hex;
import io.bosonnetwork.utils.Json;

public abstract class MessageTests {
	public static int VERSION = 0x68690001;
	public static String VERSION_STR = "hi/1";

	protected static int TIMING_ITERATIONS = 1_000_000;
	protected static String DEFAULT_VERSION_STR = "Orca/1";

	protected void printMessage(Message msg, byte[] bin) throws IOException {
		System.out.println("======== " + msg.getType().name() + ":" + msg.getMethod().name());
		System.out.println("String: " +  msg.toString());
		System.out.println("   Hex: " + bin.length + "/" + msg.estimateSize() + " : " + Hex.encode(bin));
		System.out.println("  JSON: " + Json.objectMapper().writeValueAsString(Json.cborMapper().readTree(bin)));
		System.out.println();
	}

	protected void printMessage(Message2<?> msg) throws IOException {
		var cbor = msg.toBytes();
		var json = msg.toJson();

		System.out.println("======== " + msg.getType().name() + ":" + msg.getMethod().name());
		System.out.println("String: " +  msg);
		System.out.println("  CBOR: " + cbor.length + " : " + Hex.encode(cbor));
		System.out.println("*JSON*: " + Json.objectMapper().writeValueAsString(Json.cborMapper().readTree(cbor)));
		System.out.println("  JSON: " + json);
		System.out.println();
	}
}