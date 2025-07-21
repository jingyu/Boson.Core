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

package io.bosonnetwork.kademlia.storage.deprecated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.IOError;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.InvalidSignature;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.NotOwnerException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;

/**
 * @hidden
 */
public class SQLiteStorage implements DataStorage {
	private static final int VERSION = 4;
	private static final String SET_USER_VERSION = "PRAGMA user_version = " + VERSION;
	private static final String GET_USER_VERSION = "PRAGMA user_version";

	private static final String CREATE_VALUES_TABLE = "CREATE TABLE IF NOT EXISTS valores(" +
			"id BLOB NOT NULL PRIMARY KEY, " +
			"persistent BOOLEAN NOT NULL DEFAULT FALSE, " +
			"publicKey BLOB, " +
			"privateKey BLOB, " +
			"recipient BLOB, " +
			"nonce BLOB, " +
			"signature BLOB, " +
			"sequenceNumber INTEGER, " +
			"data BLOB, " +
			"timestamp INTEGER NOT NULL, " +
			"announced INTEGER NOT NULL DEFAULT 0" +
		") WITHOUT ROWID";

	private static final String CREATE_VALUES_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_valores_timestamp ON valores(timestamp)";

	private static final String CREATE_PEERS_TABLE = "CREATE TABLE IF NOT EXISTS peers(" +
			"id BLOB NOT NULL, " +
			"nodeId BLOB NOT NULL, " +
			"origin BLOB, " +
			"persistent BOOLEAN NOT NULL DEFAULT FALSE, " +
			"privateKey BLOB, " +
			"port INTEGER NOT NULL, " +
			"alternativeURL VARCHAR(512), " +
			"signature BLOB NOT NULL, " +
			"timestamp INTEGER NOT NULL, " +
			"announced INTEGER NOT NULL DEFAULT 0, " +
			"PRIMARY KEY(id, nodeId)" +
		") WITHOUT ROWID";

	private static final String CREATE_PEERS_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_peers_timestamp ON peers(timestamp)";

	private static final String CREATE_PEERS_ID_INDEX =
			"CREATE INDEX IF NOT EXISTS idx_peers_id ON peers(id)";

	private static final String UPSERT_VALUE = "INSERT INTO valores(" +
			"id, persistent, publicKey, privateKey, recipient, nonce, signature, sequenceNumber, data, timestamp, announced) " +
			"VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET " +
			"publicKey=EXCLUDED.publicKey, privateKey=EXCLUDED.privateKey, " +
			"recipient=EXCLUDED.recipient, nonce=EXCLUDED.nonce, " +
			"signature=EXCLUDED.signature, sequenceNumber=EXCLUDED.sequenceNumber, " +
			"data=EXCLUDED.data, timestamp=EXCLUDED.timestamp";

	private static final String SELECT_VALUE = "SELECT * from valores " +
			"WHERE id = ? and timestamp >= ?";

	private static final String UPDATE_VALUE_LAST_ANNOUNCE = "UPDATE valores " +
			"SET timestamp=?, announced = ? WHERE id = ?";

	private static final String UPSERT_PEER = "INSERT INTO peers(" +
			"id, nodeId, origin, persistent, privateKey, port, alternativeURL, signature, timestamp, announced) " +
			"VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id, nodeId) DO UPDATE SET " +
			"origin=EXCLUDED.origin, persistent=EXCLUDED.persistent, privateKey=EXCLUDED.privateKey, " +
			"port=EXCLUDED.port, alternativeURL=EXCLUDED.alternativeURL, " +
			"signature=EXCLUDED.signature, timestamp=EXCLUDED.timestamp, " +
			"announced=EXCLUDED.announced";

	private static final String SELECT_PEER = "SELECT * from peers " +
			"WHERE id = ? and timestamp >= ? " +
			"ORDER BY RANDOM() LIMIT ?";

	private static final String SELECT_PEER_WITH_SRC = "SELECT * from peers " +
			"WHERE id = ? and nodeId = ? and timestamp >= ?";

	private static final String UPDATE_PEER_LAST_ANNOUNCE = "UPDATE peers " +
			"SET timestamp=?, announced = ? WHERE id = ? and nodeId = ?";

	private Connection connection;

	private ScheduledFuture<?> expireFuture;

	private static final Logger log = LoggerFactory.getLogger(SQLiteStorage.class);

	public static DataStorage open(Path path, ScheduledExecutorService scheduler) throws KadException {
		SQLiteStorage storage = new SQLiteStorage();
		storage.init(path, scheduler);
		return storage;
	}

	private void init(Path path, ScheduledExecutorService scheduler) throws KadException {
		if (path != null) {
			Path parent = path.getParent();
			if (Files.notExists(parent)) {
				try {
					Files.createDirectories(parent);
				} catch (IOException e) {
					throw new IOError("Failed to create the storage directory.", e);
				}
			}
		}

		// SQLite connection global initialization
		// According to the SQLite documentation, using single connection in
		// multiple threads is safe and efficient.
		// But java maybe using green thread on top of the system thread.
		// So we should use the thread local connection make sure the database
		// operations are safe.
		SQLiteDataSource ds = new SQLiteDataSource();

		// URL for memory db: https://www.sqlite.org/inmemorydb.html
		ds.setUrl("jdbc:sqlite:" + (path != null ? path.toString() : "file:node?mode=memory&cache=shared"));

		try {
			connection = ds.getConnection();
		} catch (SQLException e) {
			log.error("Failed to open the SQLite storage.", e);
			throw new IOError("Failed to open the SQLite storage", e);
		}

		int userVersion = getUserVersion();

		// Check and initialize the database schema.
		try (Statement stmt = getConnection().createStatement()) {
			// if we change the schema,
			// we should check the user version, do the schema update,
			// then increase the user_version;
			if (userVersion < 4) {
				stmt.executeUpdate("DROP INDEX IF EXISTS idx_valores_timestamp");
				stmt.executeUpdate("DROP TABLE IF EXISTS valores");

				stmt.executeUpdate("DROP INDEX IF EXISTS idx_peers_timestamp");
				stmt.executeUpdate("DROP INDEX IF EXISTS idx_peers_id");
				stmt.executeUpdate("DROP TABLE IF EXISTS peers");
			}

			stmt.executeUpdate(SET_USER_VERSION);
			stmt.executeUpdate(CREATE_VALUES_TABLE);
			stmt.executeUpdate(CREATE_VALUES_INDEX);
			stmt.executeUpdate(CREATE_PEERS_TABLE);
			stmt.executeUpdate(CREATE_PEERS_INDEX);
			stmt.executeUpdate(CREATE_PEERS_ID_INDEX);
		} catch (SQLException e) {
			log.error("Failed to open the SQLite storage: {}", e.getMessage(), e);
			throw new IOError("Failed to open the SQLite storage: " + e.getMessage(), e);
		}

		// Evict the expired entries from the local storage
		this.expireFuture = scheduler.scheduleWithFixedDelay(this::expire,
				0, Constants.STORAGE_EXPIRE_INTERVAL, TimeUnit.MILLISECONDS);

		log.info("SQLite storage opened: {}", path != null ? path : "MEMORY");
	}

	private Connection getConnection() {
		return connection;
	}

	@Override
	public void close() throws IOException {
		// none of the scheduled tasks should experience exceptions,
		// log them if they did
		try {
			expireFuture.cancel(false);
			expireFuture.get();
		} catch (ExecutionException | InterruptedException e) {
			log.error("Scheduled future error", e);
		} catch (CancellationException ignore) {
		}

		/*
		try {
			connection.close();
		} catch (SQLException e) {
			log.error("Failed to close the SQLite storage.", e);
			throw new IOException("Failed to close the SQLite storage.", e);
		}
		*/
	}

	public int getUserVersion() {
		int userVersion = 0;
		try (PreparedStatement stmt = getConnection().prepareStatement(GET_USER_VERSION)) {
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					userVersion = rs.getInt("user_version");
				}
			}
		} catch (SQLException e) {
			log.error("SQLite get user version an error: {}", e.getMessage(), e);
		}

		return userVersion;
	}


	@Override
	public Stream<Id> getAllValues() throws KadException {
		PreparedStatement stmt = null;
		ResultSet rs;

		try {
			stmt = getConnection().prepareStatement("SELECT id from valores WHERE timestamp >= ? ORDER BY id");
			stmt.closeOnCompletion();
			long when = System.currentTimeMillis() - Constants.MAX_VALUE_AGE;
			stmt.setLong(1, when);
			rs = stmt.executeQuery();
		} catch (SQLException e) {
			try {
				if (stmt != null)
					stmt.close();
			} catch (SQLException se) {
				log.error("SQLite storage encounter an error: {}", se.getMessage(), se);
			}

			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		final ResultSet idrs = rs;
		Stream<Id> s = StreamSupport.stream(new Spliterators.AbstractSpliterator<>(
				Long.MAX_VALUE, Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.ORDERED) {
			@Override
			public boolean tryAdvance(Consumer<? super Id> consumer) {
				try {
					if (!idrs.next())
						return false;

					byte[] binId = idrs.getBytes("id");
					consumer.accept(Id.of(binId));
					return true;
				} catch (SQLException e) {
					log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
					return false;
				}
			}
		}, false);

		return s.onClose(() -> {
			try {
				idrs.close();
			} catch (SQLException e) {
				log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			}
		});
	}

	@Override
	public Value getValue(Id valueId) throws KadException {
		try (PreparedStatement stmt = getConnection().prepareStatement(SELECT_VALUE)) {
			long when = System.currentTimeMillis() - Constants.MAX_VALUE_AGE;
			stmt.setBytes(1, valueId.bytes());
			stmt.setLong(2, when);

			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next())
					return null;

				byte[] v = rs.getBytes("publicKey");
				Id publicKey = v != null ? Id.of(v) : null;

				byte[] privateKey = rs.getBytes("privateKey");

				v = rs.getBytes("recipient");
				Id recipient = v != null ? Id.of(v) : null;

				byte[] nonce = rs.getBytes("nonce");
				byte[] signature = rs.getBytes("signature");
				int sequenceNumber = rs.getInt("sequenceNumber");
				byte[] data = rs.getBytes("data");

				return Value.of(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
			}
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public Value putValue(Value value, int expectedSeq, boolean persistent, boolean updateLastAnnounce) throws KadException {
		if (value.isMutable() && !value.isValid())
			throw new InvalidSignature("Value signature validation failed");

		Value old = getValue(value.getId());
		if (old != null && old.isMutable()) {
			if (!value.isMutable())
				throw new ImmutableSubstitutionFail("Can not replace mutable value with immutable is not supported");
			if (old.hasPrivateKey() && !value.hasPrivateKey())
				throw new NotOwnerException("Not the owner of the value");
			if (value.getSequenceNumber() < old.getSequenceNumber())
				throw new SequenceNotMonotonic("Sequence number less than current");
			if (expectedSeq >= 0 && old.getSequenceNumber() >= 0 && old.getSequenceNumber() != expectedSeq)
				throw new SequenceNotExpected("CAS failure");
		}

		try (PreparedStatement stmt = getConnection().prepareStatement(UPSERT_VALUE)) {
			stmt.setBytes(1, value.getId().bytes());

			stmt.setBoolean(2, persistent);

			if (value.getPublicKey() != null)
				stmt.setBytes(3, value.getPublicKey().bytes());
			else
				stmt.setNull(3, Types.BLOB);

			if (value.getPrivateKey() != null)
				stmt.setBytes(4, value.getPrivateKey());
			else
				stmt.setNull(4, Types.BLOB);

			if (value.getRecipient() != null)
				stmt.setBytes(5, value.getRecipient().bytes());
			else
				stmt.setNull(5, Types.BLOB);

			if (value.getNonce() != null)
				stmt.setBytes(6, value.getNonce());
			else
				stmt.setNull(6, Types.BLOB);

			if (value.getSignature() != null)
				stmt.setBytes(7, value.getSignature());
			else
				stmt.setNull(7, Types.BLOB);

			stmt.setInt(8, value.getSequenceNumber());

			if (value.getData() != null)
				stmt.setBytes(9, value.getData());
			else
				stmt.setNull(9, Types.BLOB);

			long now = System.currentTimeMillis();
			stmt.setLong(10, now);
			stmt.setLong(11, updateLastAnnounce ? now : 0);

			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		return old;
	}

	@Override
	public void updateValueLastAnnounce(Id valueId) throws KadException {
		try (PreparedStatement stmt = getConnection().prepareStatement(UPDATE_VALUE_LAST_ANNOUNCE)) {
			long now = System.currentTimeMillis();
			stmt.setLong(1, now);
			stmt.setLong(2, now);
			stmt.setBytes(3, valueId.bytes());
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public Stream<Value> getPersistentValues(long lastAnnounceBefore) throws KadException {
		PreparedStatement stmt = null;
		ResultSet rs;

		// TODO: improve - the stream should not hold the resultset during the life time.
		//       we should use the query pagination, fetch the results batch by batch into
		//       a intermediate container then drop the resultset immediately.
		try {
			stmt = getConnection().prepareStatement("SELECT * FROM valores WHERE persistent = true AND announced <= ?");
			stmt.setLong(1, lastAnnounceBefore);
			stmt.closeOnCompletion();
			rs = stmt.executeQuery();
		} catch (SQLException e) {
			try {
				if (stmt != null)
					stmt.close();
			} catch (SQLException se) {
				log.error("SQLite storage encounter an error: {}", se.getMessage(), se);
			}

			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		final ResultSet vrs = rs;
		Stream<Value> s = StreamSupport.stream(new Spliterators.AbstractSpliterator<>(
				Long.MAX_VALUE, Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.ORDERED) {
			@Override
			public boolean tryAdvance(Consumer<? super Value> consumer) {
				try {
					if(!vrs.next())
						return false;

					byte[] v = vrs.getBytes("publicKey");
					Id publicKey = v != null ? Id.of(v) : null;

					byte[] privateKey = vrs.getBytes("privateKey");

					v = vrs.getBytes("recipient");
					Id recipient = v != null ? Id.of(v) : null;

					byte[] nonce = vrs.getBytes("nonce");
					byte[] signature = vrs.getBytes("signature");
					int sequenceNumber = vrs.getInt("sequenceNumber");
					byte[] data = vrs.getBytes("data");

					Value value = Value.of(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
					consumer.accept(value);
					return true;
				} catch (SQLException e) {
					log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
					return false;
				}
			}
		}, false);

		return s.onClose(() -> {
			try {
				vrs.close();
			} catch (SQLException e) {
				log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			}
		});
	}

	@Override
	public boolean removeValue(Id valueId) throws KadException {
		Connection connection = getConnection();

		try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM valores WHERE id = ?")) {
			stmt.setBytes(1, valueId.bytes());
			int rows = stmt.executeUpdate();
			return rows > 0;
		} catch (SQLException e) {
			log.error("Failed to evict the expired values: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public Stream<Id> getAllPeers() throws KadException {
		PreparedStatement stmt = null;
		ResultSet rs;

		// TODO: improve - the stream should not hold the resultset during the life time.
		//       we should use the query pagination, fetch the results batch by batch into
		//       a intermediate container then drop the resultset immediately.
		try {
			stmt = getConnection().prepareStatement("SELECT DISTINCT id from peers WHERE timestamp >= ? ORDER BY id");
			stmt.closeOnCompletion();
			long when = System.currentTimeMillis() - Constants.MAX_PEER_AGE;
			stmt.setLong(1, when);
			rs = stmt.executeQuery();
		} catch (SQLException e) {
			try {
				if (stmt != null)
					stmt.close();
			} catch (SQLException se) {
				log.error("SQLite storage encounter an error: {}", se.getMessage(), se);
			}

			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		final ResultSet idrs = rs;
		Stream<Id> s = StreamSupport.stream(new Spliterators.AbstractSpliterator<>(
				Long.MAX_VALUE, Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.ORDERED) {
			@Override
			public boolean tryAdvance(Consumer<? super Id> consumer) {
				try {
					if(!idrs.next())
						return false;

					byte[] binId = idrs.getBytes("id");
					consumer.accept(Id.of(binId));
					return true;
				} catch (SQLException e) {
					log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
					return false;
				}
			}
		}, false);

		return s.onClose(() -> {
			try {
				idrs.close();
			} catch (SQLException e) {
				log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			}
		});
	}

	@Override
	public List<PeerInfo> getPeer(Id peerId, int maxPeers) throws KadException {
		if (maxPeers <=0)
			maxPeers = Integer.MAX_VALUE;

		List<PeerInfo> peers = new ArrayList<>(Math.min(maxPeers, 16));
		try (PreparedStatement stmt = getConnection().prepareStatement(SELECT_PEER)) {
			long when = System.currentTimeMillis() - Constants.MAX_PEER_AGE;
			stmt.setBytes(1, peerId.bytes());
			stmt.setLong(2, when);
			stmt.setInt(3, maxPeers);

			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					byte[] privateKey = rs.getBytes("privateKey");
					Id nodeId = Id.of(rs.getBytes("nodeId"));
					byte[] value = rs.getBytes("origin");
					Id origin = rs.wasNull() ? null : Id.of(value);
					int port = rs.getInt("port");
					String alt = rs.getString("alternativeURL");
					byte[] signature = rs.getBytes("signature");

					PeerInfo peer = PeerInfo.of(peerId, privateKey, nodeId, origin, port, alt, signature);
					peers.add(peer);
				}
			}
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		return peers.isEmpty() ? Collections.emptyList() : peers;
	}

	@Override
	public PeerInfo getPeer(Id peerId, Id nodeId) throws KadException {
		try (PreparedStatement stmt = getConnection().prepareStatement(SELECT_PEER_WITH_SRC)) {
			long when = System.currentTimeMillis() - Constants.MAX_PEER_AGE;
			stmt.setBytes(1, peerId.bytes());
			stmt.setBytes(2, nodeId.bytes());
			stmt.setLong(3, when);

			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next())
					return null;

				byte[] privateKey = rs.getBytes("privateKey");
				// Id nodeId = Id.of(rs.getBytes("nodeId"));
				byte[] value = rs.getBytes("origin");
				Id origin = rs.wasNull() ? null : Id.of(value);
				int port = rs.getInt("port");
				String alt = rs.getString("alternativeURL");
				byte[] signature = rs.getBytes("signature");

				return PeerInfo.of(peerId, privateKey, nodeId, origin, port, alt, signature);
			}
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public void putPeer(Collection<PeerInfo> peers) throws KadException {
		long now = System.currentTimeMillis();
		Connection connection = getConnection();

		try {
			connection.setAutoCommit(false);
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		try (PreparedStatement stmt = connection.prepareStatement(UPSERT_PEER)) {
			for (PeerInfo peer : peers) {
				stmt.setBytes(1, peer.getId().bytes());
				stmt.setBytes(2, peer.getNodeId().bytes());
				if (peer.isDelegated())
					stmt.setBytes(3, peer.getOrigin().bytes());
				else
					stmt.setNull(3, Types.BLOB);
				stmt.setBoolean(4, false);

				if (peer.hasPrivateKey())
					stmt.setBytes(5, peer.getPrivateKey());
				else
					stmt.setNull(5, Types.BLOB);

				stmt.setInt(6, peer.getPort());

				if (peer.hasAlternativeURL())
					stmt.setString(7, peer.getAlternativeURL());
				else
					stmt.setNull(7, Types.VARCHAR);

				stmt.setBytes(8, peer.getSignature());
				stmt.setLong(9, now);
				stmt.setLong(10, 0);
				stmt.addBatch();
			}

			stmt.executeBatch();
			connection.commit();
			connection.setAutoCommit(true);
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public void putPeer(PeerInfo peer, boolean persistent, boolean updateLastAnnounce) throws KadException {
		try (PreparedStatement stmt = getConnection().prepareStatement(UPSERT_PEER)) {
			stmt.setBytes(1, peer.getId().bytes());
			stmt.setBytes(2, peer.getNodeId().bytes());
			if (peer.isDelegated())
				stmt.setBytes(3, peer.getOrigin().bytes());
			else
				stmt.setNull(3, Types.BLOB);
			stmt.setBoolean(4, persistent);
			stmt.setBytes(5, peer.getPrivateKey());
			stmt.setInt(6, peer.getPort());

			if (peer.hasAlternativeURL())
				stmt.setString(7, peer.getAlternativeURL());
			else
				stmt.setNull(7, Types.VARCHAR);

			stmt.setBytes(8, peer.getSignature());

			long now = System.currentTimeMillis();
			stmt.setLong(9, now);
			stmt.setLong(10, updateLastAnnounce ? now : 0);

			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public void updatePeerLastAnnounce(Id peerId, Id nodeId) throws KadException {
		try (PreparedStatement stmt = getConnection().prepareStatement(UPDATE_PEER_LAST_ANNOUNCE)) {
			long now = System.currentTimeMillis();
			stmt.setLong(1, now);
			stmt.setLong(2, now);
			stmt.setBytes(3, peerId.bytes());
			stmt.setBytes(4, nodeId.bytes());
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	@Override
	public Stream<PeerInfo> getPersistentPeers(long lastAnnounceBefore) throws KadException {
		PreparedStatement stmt = null;
		ResultSet rs;

		// TODO: improve - the stream should not hold the resultset during the life time.
		//       we should use the query pagination, fetch the results batch by batch into
		//       a intermediate container then drop the resultset immediately.
		try {
			stmt = getConnection().prepareStatement("SELECT * FROM peers WHERE persistent = true AND announced <= ?");
			stmt.setLong(1, lastAnnounceBefore);
			stmt.closeOnCompletion();
			rs = stmt.executeQuery();
		} catch (SQLException e) {
			try {
				if (stmt != null)
					stmt.close();
			} catch (SQLException se) {
				log.error("SQLite storage encounter an error: {}", se.getMessage(), se);
			}

			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}

		final ResultSet prs = rs;
		Stream<PeerInfo> s = StreamSupport.stream(new Spliterators.AbstractSpliterator<>(
				Long.MAX_VALUE, Spliterator.NONNULL | Spliterator.IMMUTABLE | Spliterator.ORDERED) {
			@Override
			public boolean tryAdvance(Consumer<? super PeerInfo> consumer) {
				try {
					if (!prs.next())
						return false;

					Id peerId = Id.of(prs.getBytes("id"));
					byte[] privateKey = prs.getBytes("privateKey");
					Id nodeId = Id.of(prs.getBytes("nodeId"));
					byte[] value = prs.getBytes("origin");
					Id origin = prs.wasNull() ? null : Id.of(value);
					int port = prs.getInt("port");
					String alt = prs.getString("alternativeURL");
					byte[] signature = prs.getBytes("signature");

					PeerInfo peer = PeerInfo.of(peerId, privateKey, nodeId, origin, port, alt, signature);
					consumer.accept(peer);
					return true;
				} catch (SQLException e) {
					log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
					return false;
				}
			}
		}, false);

		return s.onClose(() -> {
			try {
				prs.close();
			} catch (SQLException e) {
				log.error("SQLite storage encounter an error: {}", e.getMessage(), e);
			}
		});
	}

	@Override
	public boolean removePeer(Id peerId, Id nodeId) throws KadException {
		Connection connection = getConnection();

		try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM peers WHERE id = ? and nodeId = ?")) {
			stmt.setBytes(1, peerId.bytes());
			stmt.setBytes(2, nodeId.bytes());
			int rows = stmt.executeUpdate();
			return rows > 0;
		} catch (SQLException e) {
			log.error("Failed to evict the expired peers: {}", e.getMessage(), e);
			throw new IOError("SQLite storage encounter an error: " + e.getMessage(), e);
		}
	}

	private void expire() {
		long now = System.currentTimeMillis();
		Connection connection = getConnection();

		try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM valores WHERE persistent != TRUE and timestamp < ?")) {
			long ts = now - Constants.MAX_VALUE_AGE;
			stmt.setLong(1, ts);
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Failed to evict the expired values: {}", e.getMessage(), e);
		}

		try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM peers WHERE persistent != TRUE and timestamp < ?")) {
			long ts = now - Constants.MAX_PEER_AGE;
			stmt.setLong(1, ts);
			stmt.executeUpdate();
		} catch (SQLException e) {
			log.error("Failed to evict the expired peers: {}", e.getMessage(), e);
		}
	}
}