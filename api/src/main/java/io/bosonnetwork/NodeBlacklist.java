package io.bosonnetwork;

public interface NodeBlacklist {
	/**
	 * Checks if the specified host is banned.
	 *
	 * @param host The IP host or hostname to check.
	 * @return true if the host is banned, false otherwise.
	 */
	boolean isBanned(String host);

	/**
	 * Checks if the specified ID is banned.
	 *
	 * @param id The ID to check.
	 * @return true if the ID is banned, false otherwise.
	 */
	boolean isBanned(Id id);

	/**
	 * Checks if the specified host or ID is banned.
	 *
	 * @param id   The ID to check.
	 * @param host The IP host or hostname to check.
	 * @return true if the host or ID is banned, false otherwise.
	 */
	default boolean isBanned(Id id, String host) {
		return isBanned(id) || isBanned(host);
	}

	/**
	 * Adds a host to the blacklist.
	 *
	 * @param host The IP host or hostname to ban.
	 */
	void ban(String host);

	/**
	 * Adds an ID to the blacklist.
	 *
	 * @param id The ID to ban.
	 */
	void ban(Id id);
}