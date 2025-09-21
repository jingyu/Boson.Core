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

import io.bosonnetwork.Network;
import io.bosonnetwork.kademlia.impl.DHT;
import io.bosonnetwork.utils.vertx.VertxFuture;

/**
 * @hidden
 */
@Command(name = "routingtable", mixinStandardHelpOptions = true, version = "Boson routingtable 2.0",
		description = "Display the routing tables.")
public class RoutingTableCommand implements Callable<Integer> {
	@Override
	public Integer call() throws Exception {
		DHT dht4 = Main.getBosonNode().getDHT(Network.IPv4);
		if (dht4 != null) {
			System.out.println("Routing table for IPv4: ");
			VertxFuture.of(dht4.dumpRoutingTable(System.out)).get();
			System.out.println();
		}

		DHT dht6 = Main.getBosonNode().getDHT(Network.IPv6);
		if (dht6 != null) {
			System.out.println("Routing table for IPv6: ");
			VertxFuture.of(dht6.dumpRoutingTable(System.out)).get();
			System.out.println();
		}

		return 0;
	}
}