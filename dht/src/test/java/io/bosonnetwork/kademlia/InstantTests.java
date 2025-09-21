package io.bosonnetwork.kademlia;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.micrometer.backends.BackendRegistries;
import org.slf4j.Logger;

import io.bosonnetwork.crypto.Random;

public class InstantTests {
	private static final int CLIENT_INSTANCES = 30;
	private static final int TOTAL_MESSAGES = 10000;

	static class EchoServer extends AbstractVerticle {
		private static final int PORT = 1234;
		private static final String HOST = "0.0.0.0";

		private static final Logger log = org.slf4j.LoggerFactory.getLogger(EchoServer.class);

		private DatagramSocket socket;

		private MeterRegistry registry;
		private Timer sendTimer;
		private DistributionSummary packetSizeSummary;

		private int count = 0;
		private long begin = 0;
		private long end = 0;

		@Override
		public void start(Promise<Void> startPromise) throws Exception {
			registry = BackendRegistries.getDefaultNow();
			sendTimer = Timer.builder("boson_datagram_send_time")
					.description("Time to send a packet")
					.tag("module", "DHT")
					.publishPercentiles(0.5, 0.95, 0.99)
					.register(registry);
			packetSizeSummary = DistributionSummary.builder("boson_datagram_packet_size_bytes")
					.description("Size of sent packets")
					.tag("module", "DHT")
					.publishPercentiles(0.5, 0.95, 0.99)
					.register(registry);

			socket = vertx.createDatagramSocket(new DatagramSocketOptions()
					.setSendBufferSize(1024 * 1024)
					.setReceiveBufferSize(1024 * 1024)
					.setTrafficClass(0x10));

			// Set up the packet handler
			socket.handler(packet -> {
				if (count == 0)
					begin = System.currentTimeMillis();

				SocketAddress sender = packet.sender();
				echo(packet.data(), packet.sender());
			});

			socket.exceptionHandler(e -> log.error("Socket exception", e));

			// Bind the socket to the specified host and port
			socket.listen(PORT, HOST).onComplete(ar -> {
				if (ar.succeeded()) {
					log.info("UDP Echo Server listening on {}:{}", HOST, PORT);
					startPromise.complete();
				} else {
					log.error("Failed to bind server on {}:{}", HOST, PORT, ar.cause());
					startPromise.fail(ar.cause());
				}
			});
		}

		@Override
		public void stop(Promise<Void> stopPromise) throws Exception {
			socket.close().onComplete(ar -> {
				log.info("UDP Echo Server stopped");
				stopPromise.complete();
			});
		}

		private void echo(Buffer data, SocketAddress addr) {
			context.runOnContext(v -> {
				long startTime = System.nanoTime();
				packetSizeSummary.record(data.length());

				socket.send(data, addr.port(), addr.host()).onComplete(ar -> {
					sendTimer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

					if (ar.succeeded()) {
						++count;
						//log.info("Echoed packet to {}, total echoed: {}", addr, count);
						//if (count % 1000 == 0)
						//	log.info("Echoed {} packets", count);

						if (count == CLIENT_INSTANCES * TOTAL_MESSAGES) {
							end = System.currentTimeMillis();
							System.out.println(">>>>>>>>>>>>>>>> " + (end - begin) + " ms");
						}
					} else {
						log.error("Failed to send packet to {}", addr, ar.cause());
					}
				});
			});
		}

		public static void main(String[] args) {
			VertxOptions options = new VertxOptions().setMetricsOptions(
					new MicrometerMetricsOptions()
							.setPrometheusOptions(new VertxPrometheusOptions()
									.setEnabled(true)
									//.setPublishQuantiles(true)
									.setStartEmbeddedServer(true)
									.setEmbeddedServerOptions(new HttpServerOptions().setPort(8080))
									.setEmbeddedServerEndpoint("/metrics"))
							.setEnabled(true));

			Vertx vertx = Vertx.vertx(options);
			vertx.deployVerticle(new EchoServer()).onComplete(ar -> {
				if (ar.succeeded()) {
					log.info("Echo server deployed successfully");
				} else {
					log.error("Failed to deploy Echo server", ar.cause());
				}
			});
		}
	}

	public static class EchoClient extends AbstractVerticle {
		private static final int SEND_DELAY_MS = 1;

		private static final String SERVER_HOST = "127.0.0.1";
		private static final int SERVER_PORT = 1234;
		private static final Logger log = org.slf4j.LoggerFactory.getLogger(EchoClient.class);

		private DatagramSocket socket;
		private int totalSent = 0;
		private int totalReceived = 0;

		public EchoClient() {
		}

		@Override
		public void start(Promise<Void> startPromise) throws Exception {
			socket = vertx.createDatagramSocket(new DatagramSocketOptions()
					.setSendBufferSize(1024 * 1024)
					.setReceiveBufferSize(1024 * 1024));

			socket.handler(packet -> {
				++totalReceived;
				//log.info("Received response from {}, total received: {}/{}", packet.sender(), totalReceived, TOTAL_MESSAGES);
				if (totalReceived == TOTAL_MESSAGES) {
					log.info("Finished receiving messages! Total received {} messages", totalReceived);
					undeployIfFinished();
				}
			});

			socket.exceptionHandler(e -> log.error("Socket exception", e));

			log.info("UDP Echo client {} started", deploymentID());

			context.runOnContext(this::sendMessage);
			startPromise.complete();
		}

		@Override
		public void stop(Promise<Void> stopPromise) throws Exception {
			socket.close().onComplete(ar -> {
				vertx.close();
				log.info("UDP Echo client stopped");
				stopPromise.complete();
			});
		}

		private void sendMessage(Void arg) {
			byte[] message = Random.randomBytes(Random.random().nextInt(32, 1024));

			socket.send(Buffer.buffer(message), SERVER_PORT, SERVER_HOST).onComplete(ar -> {
				if (ar.succeeded()) {
					++totalSent;
					//log.info("Message sent successfully to server {}/{}", totalSent, TOTAL_MESSAGES);

					if (totalSent < TOTAL_MESSAGES) {
						vertx.setTimer(SEND_DELAY_MS, id -> context.runOnContext(this::sendMessage));
					} else {
						log.info("Finished sending messages! Total sent {} messages", totalSent);
					}
				} else {
					log.error("Failed to send message", ar.cause());
				}
			});
		}

		private void undeployIfFinished() {
			vertx.sharedData().getLocalCounter("ECHO_CLIENT_FINISHED").onSuccess(counter -> {
				counter.incrementAndGet().onSuccess(v -> {
					if (v == CLIENT_INSTANCES)
						vertx.undeploy(deploymentID());
				}).onFailure(e -> {
					log.error("Failed to increment counter", e);
				});
			}).onFailure(e -> {
				log.error("Failed to get shared counter", e);
			});
		}

		public static void main(String[] args) {
			Vertx vertx = Vertx.vertx();

			DeploymentOptions options = new DeploymentOptions()
					.setInstances(CLIENT_INSTANCES);

			vertx.deployVerticle(EchoClient.class, options).onComplete(ar -> {
				if (ar.succeeded()) {
					log.info("Echo client[{} instances] deployed successfully", CLIENT_INSTANCES);
				} else {
					log.error("Failed to deploy Echo client", ar.cause());
					vertx.close();
				}
			});
		}
	}
}