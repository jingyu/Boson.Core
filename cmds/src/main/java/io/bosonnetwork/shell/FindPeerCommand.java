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

package io.bosonnetwork.shell;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.PeerInfo;

/**
 * @hidden
 */
@Command(name = "findpeer", mixinStandardHelpOptions = true, version = "Boson findpeer 2.0",
		description = "Find peer and show the candidate peers if exists.")
public class FindPeerCommand implements Callable<Integer> {
	@Option(names = { "-m", "--mode" }, description = "lookup mode: arbitrary, optimistic, conservative.")
	private String mode = "conservative";

	@Option(names = { "-s", "--expected-sequence-number" }, description = "expected sequence number of peers")
	private int expectedSequenceNumber = -1;

	@Option(names = { "-x", "--expected-count" }, description = "expected number of peers")
	private int expectedCount = 1;

	@Parameters(paramLabel = "ID", index = "0", description = "The peer id to be find.")
	private String id;

	@Override
	public Integer call() throws Exception {
		LookupOption option = null;
		try {
			option = LookupOption.valueOf(mode.toUpperCase());
		} catch (Exception e) {
			System.out.println("Unknown mode: " + mode);
			return -1;
		}

		Id peerId = Id.of(id);
		Main.getBosonNode().findPeer(peerId, expectedSequenceNumber, expectedCount, option).thenAccept(peers -> {
			if (!peers.isEmpty()) {
				for (PeerInfo p : peers)
					System.out.println(p);
			} else {
				System.out.println("Not found.");
			}
		}).get();

		return 0;
	}
}