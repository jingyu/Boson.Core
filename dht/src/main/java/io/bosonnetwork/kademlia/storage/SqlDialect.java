package io.bosonnetwork.kademlia.storage;

public interface SqlDialect {
	default String upsertValue() {
		return """
				INSERT INTO valores (
					id, public_key, private_key, recipient, nonce, signature,
					sequence_number, data, persistent, created, updated
				) VALUES (
					#{id}, #{publicKey}, #{privateKey}, #{recipient}, #{nonce}, #{signature},
					#{sequenceNumber}, #{data}, #{persistent}, #{created}, #{updated}
				) ON CONFLICT(id) DO UPDATE SET
					public_key = excluded.public_key,
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE valores.private_key
					END,
					recipient = excluded.recipient,
					nonce = excluded.nonce,
					signature = excluded.signature,
					sequence_number = excluded.sequence_number,
					data = excluded.data,
					persistent = excluded.persistent,
					updated = excluded.updated
				""";
	}

	default String selectValueById() {
		return "SELECT * FROM valores WHERE id = #{id}";
	}

	default String selectValuesByPersistentAndAnnouncedBefore() {
		return """
				SELECT * FROM valores
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id
				""";
	}

	default String selectValuesByPersistentAndAnnouncedBeforePaginated() {
		return """
				SELECT * FROM valores
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id
					LIMIT #{limit} OFFSET #{offset}
				""";

	}

	default String selectAllValues() {
		return "SELECT * FROM valores ORDER BY updated DESC, id";
	}

	default String selectAllValuesPaginated() {
		return "SELECT * FROM valores ORDER BY updated DESC, id LIMIT #{limit} OFFSET #{offset}";
	}

	default String updateValueAnnouncedById() {
		return "UPDATE valores SET updated = #{updated} WHERE id = #{id}";
	}

	default String deleteValueById() {
		return "DELETE FROM valores WHERE id = #{id}";
	}

	default String deleteNonPersistentValuesAnnouncedBefore() {
		return "DELETE FROM valores WHERE persistent = FALSE AND updated < #{updatedBefore}";
	}

	default String upsertPeer() {
		return """
				INSERT INTO peers (
					id, node_id, private_key, origin, port, alternative_uri, signature,
					persistent, created, updated
				) VALUES (
					#{id}, #{nodeId}, #{privateKey}, #{origin}, #{port}, #{alternativeUri}, #{signature},
					#{persistent}, #{created}, #{updated}
				) ON CONFLICT(id, node_id) DO UPDATE SET
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE peers.private_key
					END,
					origin = excluded.origin,
					port = excluded.port,
					alternative_uri = excluded.alternative_uri,
					signature = excluded.signature,
					persistent = excluded.persistent,
					updated = excluded.updated
				""";
	}

	default String selectPeerByIdAndNodeId() {
		return "SELECT * FROM peers WHERE id = #{id} AND node_id = #{nodeId}";
	}

	default String selectPeersById() {
		return "SELECT * FROM peers WHERE id = #{id} ORDER BY updated DESC, node_id";
	}

	default String selectPeersByPersistentAndAnnouncedBefore() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id, node_id
				""";
	}

	default String selectPeersByPersistentAndAnnouncedBeforePaginated() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id, node_id
					LIMIT #{limit} OFFSET #{offset}
				""";
	}

	default String selectAllPeers() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, node_id";
	}

	default String selectAllPeersPaginated() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, node_id LIMIT #{limit} OFFSET #{offset}";
	}

	default String updatePeerAnnouncedByIdAndNodeId() {
		return "UPDATE peers SET updated = #{updated} WHERE id = #{id} AND node_id = #{nodeId}";
	}

	default String deletePeerByIdAndNodeId() {
		return "DELETE FROM peers WHERE id = #{id} AND node_id = #{nodeId}";
	}

	default String deletePeersById() {
		return "DELETE FROM peers WHERE id = #{id}";
	}

	default String deleteNonPersistentPeersAnnouncedBefore() {
		return "DELETE FROM peers WHERE persistent = FALSE AND updated < #{updatedBefore}";
	}
}