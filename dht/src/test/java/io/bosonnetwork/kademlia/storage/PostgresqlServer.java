package io.bosonnetwork.kademlia.storage;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class PostgresqlServer {
	private PostgreSQLContainer container;

	private PostgresqlServer(PostgreSQLContainer container) {
		this.container = container;
	}

	public static PostgresqlServer start(String database, String username, String password) {
		DockerImageName image = DockerImageName
				.parse("postgres:18-alpine");

		PostgreSQLContainer container = new PostgreSQLContainer(image)
				.withDatabaseName(database)
				.withUsername(username)
				.withPassword(password);

		return new PostgresqlServer(container).start();
	}

	private PostgresqlServer start() {
		container.start();
		return this;
	}

	public void stop() {
		if (container != null) {
			container.stop();
			container = null;
		}
	}

	public String getDatabaseUrl() {
		return "postgresql://" +
				container.getUsername() + ":" +
				container.getPassword() + "@" +
				container.getHost() + ":" +
				container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" +
				container.getDatabaseName();
	}
}