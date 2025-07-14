package io.bosonnetwork.kademlia.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import net.datafaker.Faker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.utils.FileUtils;

public class BlacklistTests {
	private static final Path testRoot = Path.of(System.getProperty("java.io.tmpdir"), "boson");
	private static final Path testDir = Path.of(testRoot.toString(), "dht", "BlacklistTests");
	private static final Faker faker = new Faker();

	@BeforeAll
	static void setup() throws IOException {
		Files.createDirectories(testDir);
	}

	@AfterAll
	static void teardown() throws IOException {
		FileUtils.deleteFile(testRoot);
	}

	@Test
	void testSaveAndLoad() throws IOException {
		var blacklist = Blacklist.empty();

		for (var i = 0; i < 10; i++)
			blacklist.ban(Id.random());

		for (var i = 0; i < 8; i++)
			blacklist.ban(faker.bool().bool() ? faker.internet().ipV4Address() : faker.internet().ipV6Address());

		var blacklistFile = testDir.resolve("blacklist.yaml");
		blacklist.save(blacklistFile);
		assertTrue(Files.exists(blacklistFile));

		var loaded = Blacklist.load(blacklistFile);
		assertNotNull(loaded);
		assertEquals(blacklist, loaded);

		try (BufferedReader reader = Files.newBufferedReader(blacklistFile)) {
			String line;
			while ((line = reader.readLine()) != null)
				System.out.println(line);
		}
	}

	@Test
	void testBanUnbanAndCheck() {
		var blacklist = Blacklist.empty();

		var bannedHosts = IntStream.range(0, 8)
				.mapToObj(i -> faker.bool().bool() ? faker.internet().ipV4Address() : faker.internet().ipV6Address())
				.toList();

		var bannedIds = IntStream.range(0, 8)
				.mapToObj(i -> Id.random())
				.toList();

		for (var host : bannedHosts)
			blacklist.ban(host);

		for (var id : bannedIds)
			blacklist.ban(id);

		for (var host : bannedHosts)
			assertTrue(blacklist.isBanned(host));

		for (var id : bannedIds)
			assertTrue(blacklist.isBanned(id));

		assertFalse(blacklist.isBanned(faker.internet().ipV4Address()));
		assertFalse(blacklist.isBanned(faker.internet().ipV4Address()));
		assertFalse(blacklist.isBanned(faker.internet().ipV6Address()));
		assertFalse(blacklist.isBanned(faker.internet().ipV6Address()));
		assertFalse(blacklist.isBanned(Id.random()));
		assertFalse(blacklist.isBanned(Id.random()));

		for (var host : bannedHosts)
			blacklist.unban(host);

		for (var id : bannedIds)
			blacklist.unban(id);

		for (var host : bannedHosts)
			assertFalse(blacklist.isBanned(host));

		for (var id : bannedIds)
			assertFalse(blacklist.isBanned(id));
	}
}