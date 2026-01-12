package io.bosonnetwork.kademlia;

import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.DefaultNodeConfiguration;
import io.bosonnetwork.Network;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.json.Json;

public class TestNodeLauncher {
	private static final Path dataPath = Path.of(System.getProperty("java.io.tmpdir"), "boson", "KademliaNode");
	private static Vertx vertx;
	private static Signature.KeyPair nodeKey;
	private static Node node;

	private static NodeConfiguration loadConfiguration() throws Exception {
		try (InputStream s = TestNodeLauncher.class.getResourceAsStream("/testNode.yaml")) {
			Map<String, Object> map = Json.yamlMapper().readValue(s, Json.mapType());
			// fix the host
			if (map.containsKey("host4"))
				map.put("host4", Objects.requireNonNull(AddressUtils.getDefaultRouteAddress(Inet4Address.class)).getHostAddress());

			// fix the dataDir
			map.put("dataDir", dataPath.toAbsolutePath().toString());

			return DefaultNodeConfiguration.fromMap(map);
		} catch (Exception e) {
			System.err.println("Failed to load configuration file: " + e.getMessage());
			throw e;
		}
	}

	public static void main(String[] args) {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (node != null) {
				System.out.println("Shutting down the Boson Kademlia node ...");
				node.stop().thenRun(() ->
						System.out.println("Boson node stopped.")
				).join();

				// Cannot chain vertx.close() to the above future because closing Vert.x will terminate its event loop,
				// preventing any pending future handlers from executing.
				System.out.print("Shutting down Vert.x gracefully...");
				vertx.close().toCompletionStage().toCompletableFuture().join();
				System.out.println("Done!");
			}
		}));

		vertx = Vertx.vertx(new VertxOptions()
				.setWorkerPoolSize(4)
				.setEventLoopPoolSize(4)
				.setPreferNativeTransport(true));

		try {
			NodeConfiguration config = loadConfiguration();
			node = Node.kadNode(config);
			node.addConnectionStatusListener(new ConnectionStatusListener() {
				@Override
				public void connected(Network network) {
					System.out.println("Kademlia node connected to " + network);
				}

				@Override
				public void disconnected(Network network) {
					System.out.println("Kademlia node disconnected from " + network);
				}
			});

			System.out.println("Starting the Boson Kademlia node ...");
			node.start().thenRun(() -> {
				System.out.printf("Started the Boson Kademlia node %s at %s:%d\n",
						node.getId(), config.host4(), config.port());
			}).join();
		} catch (Exception e) {
			e.printStackTrace(System.err);
			vertx.close();
		}
	}
}