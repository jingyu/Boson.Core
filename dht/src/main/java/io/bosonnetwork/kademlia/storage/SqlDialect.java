package io.bosonnetwork.kademlia.storage;

public interface SqlDialect {
	default String upsertValue() {
		return """
				INSERT INTO valores (
					id, public_key, private_key, recipient, nonce, sequence_number,
					signature, data, persistent, created, updated
				) VALUES (
					#{id}, #{publicKey}, #{privateKey}, #{recipient}, #{nonce}, #{sequenceNumber},
					#{signature}, #{data}, #{persistent}, #{created}, #{updated}
				) ON CONFLICT(id) DO UPDATE SET
					-- Content is replaced only when the incoming value is newer (higher sequence number);
					-- otherwise it is preserved. The `updated` timestamp is always refreshed so that
					-- re-announcing an existing value (same sequence, or immutable values with seq 0)
					-- keeps it alive against purge() - see Kademlia republish (paper 2.5).
					public_key = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.public_key ELSE valores.public_key END,
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE valores.private_key
					END,
					recipient = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.recipient ELSE valores.recipient END,
					nonce = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.nonce ELSE valores.nonce END,
					signature = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.signature ELSE valores.signature END,
					data = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.data ELSE valores.data END,
					sequence_number = CASE WHEN valores.sequence_number < excluded.sequence_number THEN excluded.sequence_number ELSE valores.sequence_number END,
					-- Once persistent, stay persistent: an inbound (non-persistent) re-store must not downgrade it.
					persistent = valores.persistent OR excluded.persistent,
					updated = excluded.updated
				""";
	}

	default String selectValue() {
		return "SELECT * FROM valores WHERE id = #{id}";
	}

	/**
	 * Values due for a re-announce, <b>least recently announced first</b>.
	 * <p>
	 * The order is the opposite of the listing queries below, and deliberately so: this one feeds the
	 * re-announce, which serves a bounded number of items per cycle, so whatever sorts first is what
	 * gets served and the rest wait for the next cycle. {@code updated} is the announced time, and an
	 * item is dropped by the rest of the network once it is not re-announced within
	 * {@code MAX_VALUE_AGE} - so the item that must go first is the one closest to that, which is the
	 * oldest, not the newest.
	 * </p>
	 * <p>
	 * Ascending also makes the rotation free and self-correcting, the same way {@code lastRefresh}
	 * does for bucket maintenance. A served item has its announced time set to now and sorts to the
	 * back; an item whose announce failed keeps its old timestamp - {@code updateValueAnnouncedTime}
	 * runs on success only - and stays at the front, which is where something closer to expiring than
	 * everything else belongs.
	 * </p>
	 */
	default String selectValuesByPersistentAndAnnouncedBefore() {
		return """
				SELECT * FROM valores
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated ASC, id
				""";
	}

	/** @see #selectValuesByPersistentAndAnnouncedBefore() for why this orders ascending. */
	default String selectValuesByPersistentAndAnnouncedBeforePaginated() {
		return """
				SELECT * FROM valores
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated ASC, id
					LIMIT #{limit} OFFSET #{offset}
				""";

	}

	default String selectAllValues() {
		return "SELECT * FROM valores ORDER BY updated DESC, id";
	}

	default String selectAllValuesPaginated() {
		return "SELECT * FROM valores ORDER BY updated DESC, id LIMIT #{limit} OFFSET #{offset}";
	}

	default String updateValueAnnounced() {
		return "UPDATE valores SET updated = #{updated} WHERE id = #{id}";
	}

	default String deleteValue() {
		return "DELETE FROM valores WHERE id = #{id}";
	}

	default String deleteNonPersistentValuesAnnouncedBefore() {
		return "DELETE FROM valores WHERE persistent = FALSE AND updated < #{updatedBefore}";
	}

	default String upsertPeer() {
		return """
				INSERT INTO peers (
					id, fingerprint, private_key, sequence_number, node_id, node_signature,
					signature, endpoint, extra, persistent, created, updated
				) VALUES (
					#{id}, #{fingerprint}, #{privateKey}, #{sequenceNumber}, #{nodeId}, #{nodeSignature},
					#{signature}, #{endpoint}, #{extra}, #{persistent}, #{created}, #{updated}
				) ON CONFLICT(id, fingerprint) DO UPDATE SET
					-- Content is replaced only when the incoming peer is newer (higher sequence number);
					-- the `updated` timestamp is always refreshed so re-announcing keeps it alive against purge().
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE peers.private_key
					END,
					node_id = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.node_id ELSE peers.node_id END,
					node_signature = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.node_signature ELSE peers.node_signature END,
					signature = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.signature ELSE peers.signature END,
					endpoint = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.endpoint ELSE peers.endpoint END,
					extra = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.extra ELSE peers.extra END,
					sequence_number = CASE WHEN peers.sequence_number < excluded.sequence_number THEN excluded.sequence_number ELSE peers.sequence_number END,
					-- Once persistent, stay persistent: an inbound (non-persistent) re-store must not downgrade it.
					persistent = peers.persistent OR excluded.persistent,
					updated = excluded.updated
				""";
	}

	default String selectPeer() {
		return "SELECT * FROM peers WHERE id = #{id} AND fingerprint = #{fingerprint}";
	}

	default String selectPeersById() {
		return "SELECT * FROM peers WHERE id = #{id} ORDER BY updated DESC, fingerprint";
	}

	default String selectPeersByIdAndSequenceNumberWithLimit() {
		return """
				SELECT *
				FROM peers
				WHERE id = #{id} and sequence_number >= #{expectedSequenceNumber}
				ORDER BY sequence_number DESC, updated DESC, fingerprint
				LIMIT #{limit}
				""";
	}

	default String selectPeersByIdAndNodeId() {
		return """
				SELECT *
				FROM peers
				WHERE id = #{id} AND node_id = #{nodeId}
				ORDER BY updated DESC, fingerprint
				""";
	}

	/** Peers due for a re-announce, least recently announced first.
	 *  @see #selectValuesByPersistentAndAnnouncedBefore() for why this orders ascending. */
	default String selectPeersByPersistentAndAnnouncedBefore() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated ASC, id, fingerprint
				""";
	}

	/** @see #selectValuesByPersistentAndAnnouncedBefore() for why this orders ascending. */
	default String selectPeersByPersistentAndAnnouncedBeforePaginated() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated ASC, id, fingerprint
					LIMIT #{limit} OFFSET #{offset}
				""";
	}

	default String selectAllPeers() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, fingerprint";
	}

	default String selectAllPeersPaginated() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, node_id LIMIT #{limit} OFFSET #{offset}";
	}

	default String updatePeerAnnounced() {
		return "UPDATE peers SET updated = #{updated} WHERE id = #{id} AND fingerprint = #{fingerprint}";
	}

	default String deletePeer() {
		return "DELETE FROM peers WHERE id = #{id} AND fingerprint = #{fingerprint}";
	}

	default String deletePeersById() {
		return "DELETE FROM peers WHERE id = #{id}";
	}

	default String deleteNonPersistentPeersAnnouncedBefore() {
		return "DELETE FROM peers WHERE persistent = FALSE AND updated < #{updatedBefore}";
	}
}