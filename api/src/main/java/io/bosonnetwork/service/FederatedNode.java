/*
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

import io.bosonnetwork.Id;

/**
 * Interface representing a super node within the boson federation.
 * <p>
 * This interface provides access to the properties of a federated node, including its identity,
 * connection details, software information, and metadata such as trust status and reputation.
 */
public interface FederatedNode {
	/**
	 * Gets the unique identifier of the federated node.
	 *
	 * @return the node {@link Id}
	 */
	Id getId();

	/**
	 * Gets the hostname or IP address of the node.
	 *
	 * @return the host string
	 */
	String getHost();

	/**
	 * Gets the port number on which the node accepts connections.
	 *
	 * @return the port number
	 */
	int getPort();

	/**
	 * Gets the API endpoint URL for the node.
	 *
	 * @return the API endpoint string
	 */
	String getApiEndpoint();

	/**
	 * Gets the name of the software running on the node.
	 *
	 * @return the software name
	 */
	String getSoftware();

	/**
	 * Gets the version of the software running on the node.
	 *
	 * @return the software version
	 */
	String getVersion();

	/**
	 * Gets the display name of the node.
	 *
	 * @return the node name
	 */
	String getName();

	/**
	 * Gets the URL or identifier for the node's logo.
	 *
	 * @return the logo string
	 */
	String getLogo();

	/**
	 * Gets the website URL associated with the node.
	 *
	 * @return the website URL
	 */
	String getWebsite();

	/**
	 * Gets the contact information for the node administrator.
	 *
	 * @return the contact string
	 */
	String getContact();

	/**
	 * Gets the description of the node.
	 *
	 * @return the node description
	 */
	String getDescription();

	/**
	 * Checks if the node is considered trusted within the federation.
	 *
	 * @return {@code true} if the node is trusted, {@code false} otherwise
	 */
	boolean isTrusted();

	/**
	 * Gets the reputation score of the node.
	 *
	 * @return the reputation score as an integer
	 */
	int getReputation();

	/**
	 * Gets the timestamp when the node was added to the federation.
	 *
	 * @return the creation timestamp in milliseconds
	 */
	long getCreated();

	/**
	 * Gets the timestamp when the node information was last updated.
	 *
	 * @return the last update timestamp in milliseconds
	 */
	long getUpdated();
}