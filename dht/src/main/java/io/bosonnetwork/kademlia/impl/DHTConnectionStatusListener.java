package io.bosonnetwork.kademlia.impl;

/**
 * DHT connection status listener interface. Receives the connection status change events.
 */
public interface DHTConnectionStatusListener {
	/**
	 * Called when the Boson node is connecting to the Boson network.
	 *
	 * @param network the DHT network, IPv4 or IPv6.
	 */
	void connecting(Network network);

	/**
	 * Called when the Boson node connected to the Boson network.
	 *
	 * @param network the DHT network, IPv4 or IPv6.
	 */
	void connected(Network network);

	/**
	 * Called when the Boson node disconnected from the Boson network.
	 *
	 * @param network the DHT network, IPv4 or IPv6.
	 */
	void disconnected(Network network);
}