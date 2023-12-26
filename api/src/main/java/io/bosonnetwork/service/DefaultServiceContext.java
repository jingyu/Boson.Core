package io.bosonnetwork.service;

import java.util.HashMap;
import java.util.Map;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;
import io.bosonnetwork.access.AccessManager;

/**
 * Default {@link ServiceContext} implementation.
 */
public class DefaultServiceContext implements ServiceContext {
	private Node node;
	private AccessManager accessManager;
	private Map<String, Object> configuration;
	private Map<String, Object> properties;

	/**
	 * Creates a new {@link ServiceContext} instance.
	 *
	 * @param node the host Boson node.
	 * @param accessManager the {@link io.bosonnetwork.access.AccessManager} instance that
	 *        provided by the host node.
	 * @param configuration the configuration data of the service.
	 */
	public DefaultServiceContext(Node node, AccessManager accessManager, Map<String, Object> configuration) {
		this.node = node;
		this.accessManager = accessManager != null ? accessManager : AccessManager.getDefault();
		this.configuration = configuration;
		this.properties = new HashMap<>();
	}

	@Override
	public Node getNode() {
		return node;
	}

	@Override
	public Id getNodeId() {
		return node.getId();
	}

	@Override
	public AccessManager getAccessManager() {
		return accessManager;
	}

	@Override
	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	@Override
	public Object setProperty(String name, Object value) {
		return properties.put(name, value);
	}

	@Override
	public Object getProperty(String name) {
		return properties.get(name);
	}
}
