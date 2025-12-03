package io.bosonnetwork.kademlia.impl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;

import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.storage.InMemoryStorage;

public class SimpleNodeConfiguration implements NodeConfiguration {
	private final Vertx vertx;
	private final String host4;
	private final String host6;
	private final int port;
	private final String privateKey;
	private final Path dataPath;
	private final String storageURL;
	private final ArrayList<NodeInfo> bootstrapNodes;
	private final boolean enableSpamThrottling;
	private final boolean enableSuspiciousNodeDetector;
	private final boolean enableMetrics;
	private final boolean enableDeveloperMode;

	public SimpleNodeConfiguration(NodeConfiguration config) {
		this.host4 = config.host4();
		this.host6 = config.host6();
		this.port = config.port();
		this.privateKey = config.privateKey();
		this.dataPath = config.dataPath();
		this.storageURL = config.storageURL() != null ? config.storageURL() : InMemoryStorage.STORAGE_URI;
		this.bootstrapNodes = new ArrayList<>(config.bootstrapNodes() != null ? config.bootstrapNodes() : Collections.emptyList());
		this.enableSpamThrottling = config.enableSpamThrottling();
		this.enableSuspiciousNodeDetector = config.enableSuspiciousNodeDetector();
		this.enableMetrics = config.enableMetrics();
		this.enableDeveloperMode = config.enableDeveloperMode();

		this.vertx = config.vertx() != null ? config.vertx() : createDefaultVertx(enableMetrics);
	}

	private static Vertx createDefaultVertx(boolean enableMetrics) {
		VertxOptions options = new VertxOptions();
		if (enableMetrics) {
			options.setMetricsOptions(
					new MicrometerMetricsOptions()
							.setPrometheusOptions(new VertxPrometheusOptions()
									.setEnabled(true)
									//.setPublishQuantiles(true)
									.setStartEmbeddedServer(true)
									.setEmbeddedServerOptions(new HttpServerOptions().setPort(8080))
									.setEmbeddedServerEndpoint("/metrics"))
							.setEnabled(true));
		}

		return Vertx.vertx(options);
	}

	@Override
	public Vertx vertx() {
		return vertx;
	}

	@Override
	public String host4() {
		return host4;
	}

	@Override
	public String host6() {
		return host6;
	}

	@Override
	public int port() {
		return port;
	}

	@Override
	public String privateKey() {
		return privateKey;
	}

	@Override
	public Path dataPath() {
		return dataPath;
	}

	@Override
	public String storageURL() {
		return storageURL;
	}

	@Override
	public Collection<NodeInfo> bootstrapNodes() {
		return Collections.unmodifiableList(bootstrapNodes);
	}

	@Override
	public boolean enableMetrics() {
		return enableMetrics;
	}

	@Override
	public boolean enableSpamThrottling() {
		return enableSpamThrottling;
	}

	@Override
	public boolean enableSuspiciousNodeDetector() {
		return enableSuspiciousNodeDetector;
	}

	@Override
	public boolean enableDeveloperMode() {
		return enableDeveloperMode;
	}
}