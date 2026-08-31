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

package io.bosonnetwork.service.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.LookupOption;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.ConfigMap;
import io.bosonnetwork.utils.Hex;
import io.bosonnetwork.web.RateLimitPolicy;

/**
 * Covers the shared service configuration readers and writers: the defaults each block falls back to
 * when it is absent, the validation each performs, and that every block survives a round trip
 * through {@link ServiceConfigMap#put(String, Object)}.
 */
public class ServiceConfigMapTests {
	private static ServiceConfigMap cm(Map<String, Object> map) {
		return new ServiceConfigMap(new LinkedHashMap<>(map));
	}

	// -------------------------------------------------------------------------
	// Missing keys select the documented default, consistently across blocks
	// -------------------------------------------------------------------------

	@Test
	void testAbsentBlocksSelectTheirDefaults() {
		ServiceConfigMap empty = cm(Map.of());

		assertSame(RateLimitPolicy.UNLIMITED, empty.getRateLimitPolicy("rateLimit"));
		assertSame(PeerOptions.DEFAULT, empty.getPeerOptions("peer"));
		assertEquals(new DatabaseOptions("jdbc:sqlite:test.db", 0, null),
				empty.getDatabaseOptions("database", "jdbc:sqlite:test.db"));
		assertEquals(new ListenOptions("0.0.0.0", 9000, true),
				empty.getListenOptions("0.0.0.0", 9000, true));
	}

	@Test
	void testEmptyBlocksSelectTheirDefaultsToo() {
		ServiceConfigMap empty = cm(Map.of(
				"peer", Map.of(),
				"database", Map.of()));

		assertSame(PeerOptions.DEFAULT, empty.getPeerOptions("peer"));
		assertEquals(new DatabaseOptions("jdbc:sqlite:test.db", 0, null),
				empty.getDatabaseOptions("database", "jdbc:sqlite:test.db"));
	}

	// -------------------------------------------------------------------------
	// Rate limit policies are read as whole values
	// -------------------------------------------------------------------------

	@Test
	void testRateLimitPolicyIsReadAsAWholeValue() {
		// A block that names only perMinute disables the other windows rather than inheriting them
		// from the default. What the operator wrote is exactly what is enforced.
		ServiceConfigMap m = cm(Map.of("rateLimit", Map.of("perMinute", 60)));

		RateLimitPolicy fullDefault = new RateLimitPolicy(0, 10, 600, 5000);
		assertEquals(RateLimitPolicy.perMinute(60), m.getRateLimitPolicy("rateLimit", fullDefault));
	}

	@Test
	void testRateLimitDefaultAppliesOnlyWhenTheKeyIsAbsent() {
		RateLimitPolicy def = new RateLimitPolicy(0, 10, 600, 5000);

		assertEquals(def, cm(Map.of()).getRateLimitPolicy("rateLimit", def));
		// A block that is present but empty says "nothing is enforced", and beats the default.
		assertSame(RateLimitPolicy.UNLIMITED, cm(Map.of("rateLimit", Map.of())).getRateLimitPolicy("rateLimit", def));
	}

	@Test
	void testRateLimitPolicyReadsEveryWindow() {
		ServiceConfigMap m = cm(Map.of("rateLimit",
				Map.of("perSecond", 5, "perMinute", 60, "perHour", 600, "perDay", 5000)));

		assertEquals(new RateLimitPolicy(5, 60, 600, 5000), m.getRateLimitPolicy("rateLimit"));
	}

	@Test
	void testRateLimitPolicyRejectsNegativeWindows() {
		ServiceConfigMap m = cm(Map.of("rateLimit", Map.of("perMinute", -1)));
		assertThrows(IllegalArgumentException.class, () -> m.getRateLimitPolicy("rateLimit"));
	}

	@Test
	void testRateLimitPolicyRoundTrips() {
		RateLimitPolicy policy = new RateLimitPolicy(5, 60, 600, 5000);
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("rateLimit", policy);

		assertEquals(policy, m.getRateLimitPolicy("rateLimit"));
	}

	@Test
	void testUnlimitedPolicyIsNotWritten() {
		// Every window at its default means there is nothing to say, so the key is dropped rather
		// than written as an empty block.
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("rateLimit", RateLimitPolicy.UNLIMITED);

		assertFalse(m.containsKey("rateLimit"));
	}

	// -------------------------------------------------------------------------
	// PeerOptions
	// -------------------------------------------------------------------------

	@Test
	void testPeerOptionsRoundTrip() {
		PeerOptions peer = new PeerOptions("https://example.com/store", 42L, 7);
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("peer", peer);

		assertEquals(peer, m.getPeerOptions("peer", "http", "https"));
	}

	@Test
	void testPeerOptionsExtraRoundTrip() {
		PeerOptions peer = new PeerOptions("https://example.com/store", 42L, 7,
				Map.of("federationEndpoint", "https://example.com/store/federation"));
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("peer", peer);

		assertEquals(peer, m.getPeerOptions("peer", "http", "https"));
		assertEquals(Map.of("federationEndpoint", "https://example.com/store/federation"),
				m.getPeerOptions("peer", "http", "https").extra());
	}

	/**
	 * The extra block is service-defined and may be absent, so it has to read back as an empty map
	 * rather than as null - callers of {@link PeerOptions#extra()} index into it directly.
	 */
	@Test
	void testPeerOptionsWithoutExtraReadsAsEmpty() {
		assertEquals(Map.of(), cm(Map.of("peer", Map.of("endpoint", "https://example.com")))
				.getPeerOptions("peer", "http", "https").extra());
		assertEquals(Map.of(), new PeerOptions("https://example.com", 0, 0).extra());
	}

	/** These options are a value; a caller must not be able to change one after building it. */
	@Test
	void testPeerOptionsCopiesExtra() {
		Map<String, Object> extra = new LinkedHashMap<>(Map.of("k", "v"));
		PeerOptions peer = new PeerOptions("https://example.com", 0, 0, extra);
		extra.put("k", "changed");

		assertEquals("v", peer.extra().get("k"));
		assertThrows(UnsupportedOperationException.class, () -> peer.extra().put("k", "changed"));
	}

	@Test
	void testPeerOptionsRejectsUnexpectedScheme() {
		ServiceConfigMap m = cm(Map.of("peer", Map.of("endpoint", "ftp://example.com")));
		assertThrows(IllegalArgumentException.class, () -> m.getPeerOptions("peer", "http", "https"));
	}

	@Test
	void testPeerOptionsValidatesSyntaxEvenWithoutExpectedSchemes() {
		// An endpoint nobody can parse is useless whether or not the caller named the schemes it
		// supports, so the syntax check is unconditional.
		ServiceConfigMap malformed = cm(Map.of("peer", Map.of("endpoint", "http://exa mple.com")));
		assertThrows(IllegalArgumentException.class, () -> malformed.getPeerOptions("peer"));

		ServiceConfigMap relative = cm(Map.of("peer", Map.of("endpoint", "example.com/store")));
		assertThrows(IllegalArgumentException.class, () -> relative.getPeerOptions("peer"));
	}

	@Test
	void testPeerOptionsTreatsAnEmptyEndpointAsAbsent() {
		ServiceConfigMap m = cm(Map.of("peer", Map.of("endpoint", "", "fingerprint", 9L)));
		PeerOptions peer = m.getPeerOptions("peer", "http", "https");

		assertNull(peer.endpoint());
		assertEquals(9L, peer.fingerprint());
	}

	@Test
	void testPeerOptionsRejectsNegativeSequenceNumber() {
		ServiceConfigMap m = cm(Map.of("peer", Map.of("sequenceNumber", -1)));
		assertThrows(IllegalArgumentException.class, () -> m.getPeerOptions("peer"));
	}

	@Test
	void testDefaultPeerOptionsAreNotWritten() {
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("peer", PeerOptions.DEFAULT);

		assertFalse(m.containsKey("peer"));
	}

	// -------------------------------------------------------------------------
	// DatabaseOptions
	// -------------------------------------------------------------------------

	@Test
	void testDatabaseOptionsRoundTrip() {
		DatabaseOptions database = new DatabaseOptions("postgresql://localhost/boson", 16, "ionstore");
		ServiceConfigMap m = new ServiceConfigMap();
		m.put("database", database);

		assertEquals(database, m.getDatabaseOptions("database", "jdbc:sqlite:unused.db"));
	}

	@Test
	void testDatabaseSupportCheckAppliesToTheConfiguredUri() {
		ServiceConfigMap m = cm(Map.of("database", Map.of("uri", "jdbc:mysql://localhost/db")));
		assertThrows(IllegalArgumentException.class,
				() -> m.getDatabaseOptions("database", "jdbc:sqlite:test.db", uri -> uri.startsWith("jdbc:sqlite:")));
	}

	@Test
	void testDatabaseSupportCheckAppliesToTheDefaultUriToo() {
		// A service must not be able to ship a default its own driver does not support.
		ServiceConfigMap m = cm(Map.of());
		assertThrows(IllegalArgumentException.class,
				() -> m.getDatabaseOptions("database", "jdbc:mysql://localhost/db", uri -> uri.startsWith("jdbc:sqlite:")));
	}

	@Test
	void testDatabaseOptionsRejectNegativePoolSize() {
		ServiceConfigMap m = cm(Map.of("database", Map.of("uri", "jdbc:sqlite:test.db", "poolSize", -1)));
		assertThrows(IllegalArgumentException.class, () -> m.getDatabaseOptions("database", "jdbc:sqlite:test.db"));
	}

	@Test
	void testDatabaseOptionsRejectUnsafeSchema() {
		ServiceConfigMap m = cm(Map.of("database",
				Map.of("uri", "jdbc:sqlite:test.db", "schema", "public; DROP TABLE users")));
		assertThrows(IllegalArgumentException.class, () -> m.getDatabaseOptions("database", "jdbc:sqlite:test.db"));
	}

	// -------------------------------------------------------------------------
	// ListenOptions, which live at the top level rather than in a block
	// -------------------------------------------------------------------------

	@Test
	void testListenOptionsRoundTripAtTheTopLevel() {
		ListenOptions listen = new ListenOptions("127.0.0.1", 8443, true);
		ServiceConfigMap m = new ServiceConfigMap();
		m.putListenOptions(listen);

		// Written flat, not nested under a key of their own
		assertEquals("127.0.0.1", m.get("host"));
		assertEquals(8443, m.get("port"));
		assertEquals(true, m.get("ssl"));
		assertEquals(listen, m.getListenOptions("0.0.0.0", 9000, false));
	}

	/**
	 * A secondary interface is configured in a block of its own. A missing block means every setting
	 * is at its default, which is how a document written before the block existed keeps working.
	 */
	@Test
	void testKeyedListenOptions() {
		ServiceConfigMap m = cm(Map.of("federation", Map.of("host", "127.0.0.1", "port", 9084, "ssl", false)));
		assertEquals(new ListenOptions("127.0.0.1", 9084, false),
				m.getListenOptions("federation", "0.0.0.0", 9000, true));

		// Absent block, and a block that names only some of the settings
		assertEquals(new ListenOptions("0.0.0.0", 9000, true),
				new ServiceConfigMap().getListenOptions("federation", "0.0.0.0", 9000, true));
		assertEquals(new ListenOptions("0.0.0.0", 9084, true),
				cm(Map.of("federation", Map.of("port", 9084))).getListenOptions("federation", "0.0.0.0", 9000, true));
	}

	@Test
	void testKeyedListenOptionsRejectInvalidPort() {
		assertThrows(IllegalArgumentException.class, () -> cm(Map.of("federation", Map.of("port", 0)))
				.getListenOptions("federation", "0.0.0.0", 9000, false));
	}

	@Test
	void testListenOptionsRejectInvalidPort() {
		assertThrows(IllegalArgumentException.class,
				() -> cm(Map.of("port", 0)).getListenOptions("0.0.0.0", 9000, false));
		assertThrows(IllegalArgumentException.class,
				() -> cm(Map.of("port", 65536)).getListenOptions("0.0.0.0", 9000, false));
	}

	@Test
	void testListenOptionsRejectEmptyHost() {
		assertThrows(IllegalArgumentException.class,
				() -> cm(Map.of("host", "")).getListenOptions("0.0.0.0", 9000, false));
	}

	@Test
	void testPutListenOptionsWithNullClearsTheEntries() {
		ServiceConfigMap m = new ServiceConfigMap();
		m.putListenOptions(new ListenOptions("127.0.0.1", 8443, true));
		m.putListenOptions(null);

		assertFalse(m.containsKey("host"));
		assertFalse(m.containsKey("port"));
		assertFalse(m.containsKey("ssl"));
	}

	// -------------------------------------------------------------------------
	// TlsOptions, also top-level and optional as a whole
	// -------------------------------------------------------------------------

	@Test
	void testTlsOptionsRoundTripAtTheTopLevel() {
		TlsOptions tls = new TlsOptions(Path.of("/etc/boson/cert.pem"), Path.of("/etc/boson/key.pem"));
		ServiceConfigMap m = new ServiceConfigMap();
		m.putTlsOptions(tls);

		assertEquals("/etc/boson/cert.pem", m.get("sslCertFile"));
		assertEquals("/etc/boson/key.pem", m.get("sslKeyFile"));
		assertEquals(tls, m.getTlsOptions());
	}

	@Test
	void testAbsentTlsOptionsMeanSelfSign() {
		// No certificate configured is not an error - the node generates its own - so this reads as
		// null rather than throwing or yielding an empty pair.
		assertNull(cm(Map.of()).getTlsOptions());
	}

	@Test
	void testHalfATlsPairIsRejected() {
		// Accepting half would leave the node quietly self-signing while the operator believes their
		// certificate is in force.
		assertThrows(IllegalArgumentException.class,
				() -> cm(Map.of("sslCertFile", "/etc/boson/cert.pem")).getTlsOptions());
		assertThrows(IllegalArgumentException.class,
				() -> cm(Map.of("sslKeyFile", "/etc/boson/key.pem")).getTlsOptions());
	}

	@Test
	void testPutTlsOptionsWithNullClearsTheEntries() {
		ServiceConfigMap m = new ServiceConfigMap();
		m.putTlsOptions(new TlsOptions(Path.of("/etc/boson/cert.pem"), Path.of("/etc/boson/key.pem")));
		m.putTlsOptions(null);

		assertFalse(m.containsKey("sslCertFile"));
		assertFalse(m.containsKey("sslKeyFile"));
	}

	// -------------------------------------------------------------------------
	// Boson domain types
	// -------------------------------------------------------------------------

	@Test
	void testPrivateKeyAcceptsBase58AndHex() {
		Signature.KeyPair keyPair = Signature.KeyPair.random();
		byte[] sk = keyPair.privateKey().bytes();

		assertEquals(keyPair, cm(Map.of("privateKey", Base58.encode(sk))).getPrivateKey("privateKey"));
		assertEquals(keyPair, cm(Map.of("privateKey", "0x" + Hex.encode(sk))).getPrivateKey("privateKey"));
	}

	@Test
	void testPrivateKeyRejectsGarbage() {
		ServiceConfigMap m = cm(Map.of("privateKey", "not-a-key"));
		assertThrows(IllegalArgumentException.class, () -> m.getPrivateKey("privateKey"));

		ServiceConfigMap empty = cm(Map.of("privateKey", ""));
		assertThrows(IllegalArgumentException.class, () -> empty.getPrivateKey("privateKey"));
	}

	@Test
	void testLookupOptionIsCaseInsensitive() {
		assertEquals(LookupOption.OPTIMISTIC, cm(Map.of("mode", "optimistic")).getLookupOption("mode"));
		assertEquals(LookupOption.CONSERVATIVE, cm(Map.of("mode", "CONSERVATIVE")).getLookupOption("mode"));
		assertEquals(LookupOption.ARBITRARY, cm(Map.of()).getLookupOption("mode", LookupOption.ARBITRARY));
	}

	@Test
	void testLookupOptionRejectsUnknownValue() {
		ServiceConfigMap m = cm(Map.of("mode", "sideways"));
		assertThrows(IllegalArgumentException.class, () -> m.getLookupOption("mode"));
	}

	// -------------------------------------------------------------------------
	// Map behavior
	// -------------------------------------------------------------------------

	@Test
	void testPutNullRemovesTheKey() {
		ServiceConfigMap m = cm(Map.of("host", "127.0.0.1"));

		assertEquals("127.0.0.1", m.put("host", null));
		assertFalse(m.containsKey("host"));
	}

	@Test
	void testPutAllRoutesThroughPut() {
		// putAll must give ConfigOptions values the same meaning put does, rather than storing the
		// object itself.
		ServiceConfigMap m = new ServiceConfigMap();
		m.putAll(Map.of("peer", new PeerOptions("https://example.com", 0, 0)));

		assertEquals(Map.of("endpoint", "https://example.com"), m.get("peer"));
	}

	@Test
	void testEqualityWithAPlainMapIsSymmetric() {
		Map<String, Object> plain = Map.of("host", "127.0.0.1", "port", 9000);
		ConfigMap wrapped = cm(plain);

		assertEquals(plain, wrapped);
		assertEquals(wrapped, plain);
		assertEquals(plain.hashCode(), wrapped.hashCode());
	}

	@Test
	void testNestedObjectsAreServiceConfigMaps() {
		ServiceConfigMap m = cm(Map.of("limits", Map.of("user", Map.of("rateLimit", Map.of("perMinute", 60)))));

		ServiceConfigMap limits = m.getObject("limits");
		assertTrue(limits != null);
		ServiceConfigMap user = limits.getObject("user");
		assertTrue(user != null);
		assertEquals(RateLimitPolicy.perMinute(60), user.getRateLimitPolicy("rateLimit"));
	}
}
