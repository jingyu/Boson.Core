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

import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Hex;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * @hidden
 */
@Command(name = "announcepeer", mixinStandardHelpOptions = true, version = "Boson announcepeer 2.0",
		description = "Announce a service peer.")
public class AnnouncePeerCommand implements Callable<Integer> {
	@Option(names = { "-p", "--persistent" }, description = "Persistent peer, default is false.")
	private boolean persistent = false;

	@Option(names = { "-l", "--localOnly" }, description = "Only store the value in the local node")
	private boolean localOnly = false;

	@Option(names = {"-k", "--private-key"}, description = "The private key.")
	private String privateKey = null;

   	@Option(names = {"-a", "--authenticated"}, description = "Authenticated peer info.")
	private boolean authenticated = false;

	@Option(names = {"-e", "--extra"}, description = "The extra information(json format is prefered).")
	private String extra = null;

	@Parameters(paramLabel = "ENDPOINT", index = "0", description = "The peer endpoint URI/URL.")
	private String endpoint = null;


	@Override
	public Integer call() throws Exception {
		Signature.KeyPair keypair = null;
		try {
			if (privateKey != null)
				keypair = Signature.KeyPair.fromPrivateKey(Hex.decode(privateKey));
		} catch (Exception e) {
			System.out.println("Invalid private key: " + privateKey + ", " + e.getMessage());
			return -1;
		}

		if (endpoint == null) {
			System.out.println("Endpoint is required.");
			return -1;
		}

		byte[] extraData = null;
		if (extra != null) {
			try {
				extraData = Json.toBytes(Json.parse(extra));
			} catch (Exception e) {
				System.out.println("Extra data is not json, treat as byte string");
				extraData = extra.getBytes();
			}
		}

		PeerInfo.Builder pb = PeerInfo.builder().endpoint(endpoint);
		if (keypair != null)
			pb.key(keypair);
		if (authenticated)
			pb.node(Main.getBosonNode());
		if (extraData != null)
			pb.extra(extraData);
		PeerInfo peer = pb.build();

		if (localOnly)
			VertxFuture.of(Main.getBosonNode().getStorage().putPeer(peer)).get();
		else
			Main.getBosonNode().announcePeer(peer, persistent).get();

		System.out.println("Peer " + peer.getId() + " announced with private key " +
				Hex.encode(peer.getPrivateKey()));

		return 0;
	}
}