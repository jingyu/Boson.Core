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
import picocli.CommandLine.Parameters;

import io.bosonnetwork.Id;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.shell.StorageCommand.ListPeerCommand;
import io.bosonnetwork.shell.StorageCommand.ListValueCommand;
import io.bosonnetwork.shell.StorageCommand.PeerCommand;
import io.bosonnetwork.shell.StorageCommand.ValueCommand;
import io.bosonnetwork.utils.vertx.VertxFuture;

/**
 * @hidden
 */
@Command(name = "storage", mixinStandardHelpOptions = true, version = "Boson storage 2.0",
	description = "Show the local data storage.", subcommands = {
			ListValueCommand.class,
			ValueCommand.class,
			ListPeerCommand.class,
			PeerCommand.class
		})
public class StorageCommand {
	/**
	 * @hidden
	 */
	@Command(name = "listvalue", mixinStandardHelpOptions = true, version = "Boson listvalue 2.0",
			description = "List values from the local storage.")
	public static class ListValueCommand implements Callable<Integer> {

		@Override
		public Integer call() throws Exception {
			DataStorage storage = Main.getBosonNode().getStorage();

			VertxFuture.of(storage.getValues().map(values -> {
				values.forEach(v -> {
					System.out.printf("%44s, %s\n", v.getId(), v.isMutable() ? "mutable" : "immutable");
				});
				System.out.println("Total " + values.size() + " values.");
				return null;
			})).get();

			return 0;
		}
	}

	/**
	 * @hidden
	 */
	@Command(name = "value", mixinStandardHelpOptions = true, version = "Boson value 2.0",
			description = "Display value from the local storage.")
	public static class ValueCommand implements Callable<Integer> {
		@Parameters(paramLabel = "ID", index = "0", description = "The value id.")
		private String id = null;

		@Override
		public Integer call() throws Exception {
			Id valueId = null;
			try {
				valueId = Id.of(id);
			} catch (Exception e) {
				System.out.println("Invalid id: " + id);
				return -1;
			}

			DataStorage storage = Main.getBosonNode().getStorage();
			VertxFuture.of(storage.getValue(valueId).map(value -> {
				if (value != null)
					System.out.println(value);
				else
					System.out.println("Value " + id + " not exists.");

				return null;
			})).get();

			return 0;
		}
	}

	/**
	 * @hidden
	 */
	@Command(name = "listpeer", mixinStandardHelpOptions = true, version = "Boson listpeer 2.0",
			description = "List peers from the local storage.")
	public static class ListPeerCommand implements Callable<Integer> {

		@Override
		public Integer call() throws Exception {
			DataStorage storage = Main.getBosonNode().getStorage();

			VertxFuture.of(storage.getPeers().map(peers -> {
				peers.forEach(p -> {
					System.out.printf("%s:%s\n", p.getId(), p.getNodeId());
				});
				System.out.println("Total " + peers.size() + " peers.");
				return null;
			})).get();

			return 0;
		}
	}

	/**
	 * @hidden
	 */
	@Command(name = "peer", mixinStandardHelpOptions = true, version = "Boson peer 2.0",
			description = "Display peer info from the local storage.")
	public static class PeerCommand implements Callable<Integer> {
		@Parameters(paramLabel = "ID", index = "0", description = "The peer id.")
		private String id = null;

		@Override
		public Integer call() throws Exception {
			Id peerId = null;
			try {
				peerId = Id.of(id);
			} catch (Exception e) {
				System.out.println("Invalid id: " + id);
				return -1;
			}

			DataStorage storage = Main.getBosonNode().getStorage();

			VertxFuture.of(storage.getPeers(peerId).map(peers -> {
				peers.forEach(System.out::println);
				System.out.println("Total " + peers.size() + " peers.");
				return null;
			})).get();

			return 0;
		}
	}
}