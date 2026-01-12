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
					public_key = excluded.public_key,
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE valores.private_key
					END,
					recipient = excluded.recipient,
					nonce = excluded.nonce,
					sequence_number = excluded.sequence_number,
					signature = excluded.signature,
					data = excluded.data,
					persistent = excluded.persistent,
					updated = excluded.updated
				WHERE valores.sequence_number < excluded.sequence_number
				""";
	}

	default String selectValue() {
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
					id, fingerprint, private_key, nonce, sequence_number, node_id, node_signature,
					signature, endpoint, extra, persistent, created, updated
				) VALUES (
					#{id}, #{fingerprint}, #{privateKey}, #{nonce}, #{sequenceNumber}, #{nodeId}, #{nodeSignature},
					#{signature}, #{endpoint}, #{extra}, #{persistent}, #{created}, #{updated}
				) ON CONFLICT(id, fingerprint) DO UPDATE SET
					private_key = CASE
						WHEN excluded.private_key IS NOT NULL
						THEN excluded.private_key
						ELSE peers.private_key
					END,
					nonce = excluded.nonce,
					sequence_number = excluded.sequence_number,
					node_id = excluded.node_id,
					node_signature = excluded.node_signature,
					signature = excluded.signature,
					endpoint = excluded.endpoint,
					extra = excluded.extra,
					persistent = excluded.persistent,
					updated = excluded.updated
				WHERE peers.sequence_number < excluded.sequence_number
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

	default String selectPeersByPersistentAndAnnouncedBefore() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id, fingerprint
				""";
	}

	default String selectPeersByPersistentAndAnnouncedBeforePaginated() {
		return """
				SELECT * FROM peers
					WHERE persistent = #{persistent} AND updated <= #{updatedBefore}
					ORDER BY updated DESC, id, fingerprint
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