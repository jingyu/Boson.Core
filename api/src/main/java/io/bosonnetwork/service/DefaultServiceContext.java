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

package io.bosonnetwork.service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.vertx.core.Vertx;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;

/**
 * Default implementation of the {@link ServiceContext} interface.
 * <p>
 * This class provides a standard implementation for accessing service context information,
 * including the Vert.x instance, the Boson node, authentication/authorization components,
 * federation details, configuration, and data persistence paths.
 */
public class DefaultServiceContext implements ServiceContext {
	private final Vertx vertx;
	private final Node node;
	private final ClientAuthenticator clientAuthenticator;
	private final ClientAuthorizer clientAuthorizer;
	private final FederationAuthenticator federationAuthenticator;
	private final Federation federation;
	private final Map<String, Object> configuration;
	private final Path dataDir;
	private Map<Object, Object> properties;

	/**
	 * Creates a new {@link ServiceContext} instance.
	 *
	 * @param vertx                   the Vert.x instance to be used
	 * @param node                    the host Boson node
	 * @param clientAuthenticator     the authenticator for client connections
	 * @param clientAuthorizer        the authorizer for client requests
	 * @param federationAuthenticator the authenticator for federation communications
	 * @param federation              the federation instance
	 * @param configuration           the configuration data for the service
	 * @param dataDir                 the path to the persistence data directory, or {@code null} if not available
	 */
	public DefaultServiceContext(Vertx vertx, Node node, ClientAuthenticator clientAuthenticator, ClientAuthorizer clientAuthorizer,
								 FederationAuthenticator federationAuthenticator, Federation federation,
								 Map<String, Object> configuration, Path dataDir) {
		this.vertx = vertx;
		this.node = node;
		this.clientAuthenticator = clientAuthenticator;
		this.clientAuthorizer = clientAuthorizer;
		this.federationAuthenticator = federationAuthenticator;
		this.federation = federation;
		this.configuration = configuration;
		this.dataDir = dataDir;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Vertx getVertx() {
		return vertx;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Node getNode() {
		return node;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Id getNodeId() {
		return node.getId();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Path getDataDir() {
		return dataDir;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ClientAuthenticator getClientAuthenticator() {
		return clientAuthenticator;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ClientAuthorizer getClientAuthorizer() {
		return clientAuthorizer;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public FederationAuthenticator getFederationAuthenticator() {
		return federationAuthenticator;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Federation getFederation() {
		return federation;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<String, Object> getConfiguration() {
		return configuration;
	}

	private Map<Object, Object> properties() {
		return properties == null ? properties = new HashMap<>() : properties;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object setProperty(Object key, Object value) {
		return properties().put(key, value);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(Object key) {
		return (T) properties().get(key);
	}
}