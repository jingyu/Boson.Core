package io.bosonnetwork.kademlia.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;

/**
 * The property under test throughout: what an observation is allowed to do depends on whether the packet
 * behind it proved where it came from, not on how bad the behaviour looked.
 */
public class SuspiciousNodeDetectorTests {
	private static final long OBSERVATION_PERIOD = 60 * 1000;
	private static final int HITS = 32;
	private static final long BAN_DURATION = 60 * 1000;
	private static final long SUPPRESSION_DURATION = 1000;

	private SuspiciousNodeDetector detector;

	@BeforeEach
	void setup() {
		detector = SuspiciousNodeDetector.create(OBSERVATION_PERIOD, HITS, BAN_DURATION, SUPPRESSION_DURATION);
	}

	private static SocketAddress addr(String host) {
		return SocketAddress.inetSocketAddress(39001, host);
	}

	private static void hit(Runnable event, int times) {
		for (int i = 0; i < times; i++)
			event.run();
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testMalformedMessagesOnlySuppressBriefly() throws Exception {
		var victim = addr("192.168.8.1");

		hit(() -> detector.malformedMessage(victim), HITS);

		// It does take effect - unattributable traffic is still traffic, and the source still pays for it.
		assertTrue(detector.isBanned(victim.host()));

		// But it lifts on its own, in seconds rather than in the ban duration a proven source would earn.
		// This is the whole finding: 32 packets must not buy a long silence, because the sender chooses
		// whose address is on them.
		Thread.sleep(SUPPRESSION_DURATION + 500);
		assertFalse(detector.isBanned(victim.host()), "an unproven source must not stay suppressed");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testMalformedMessagesOnlySuppressBriefly2() throws Exception {
		String victim = "192.168.8.1";
		int port = 39001;

		hit(() -> {
			SocketAddress addr =  SocketAddress.inetSocketAddress(port + Random.random().nextInt(10, 1000), victim);
			detector.malformedMessage(addr);
		}, HITS);

		// It does take effect - unattributable traffic is still traffic, and the source still pays for it.
		assertTrue(detector.isBanned(victim));

		// But it lifts on its own, in seconds rather than in the ban duration a proven source would earn.
		// This is the whole finding: 32 packets must not buy a long silence, because the sender chooses
		// whose address is on them.
		Thread.sleep(SUPPRESSION_DURATION + 500);
		assertFalse(detector.isBanned(victim), "an unproven source must not stay suppressed");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testProvenMisbehaviourEarnsTheFullBan() throws Exception {
		var node = addr("192.168.8.2");
		Id id = Id.random();

		hit(() -> detector.misbehaved(node, id), HITS);

		assertTrue(detector.isBanned(node.host()));

		// Still banned well past the point where a suppression would have lifted.
		Thread.sleep(SUPPRESSION_DURATION * 3);
		assertTrue(detector.isBanned(node.host()), "a proven source must serve the full ban");
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void testSuppressionEscalatesOnRepeat() throws Exception {
		var source = addr("192.168.8.3");

		hit(() -> detector.malformedMessage(source), HITS);
		assertTrue(detector.isBanned(source.host()));

		// First suppression is one base duration.
		Thread.sleep(SUPPRESSION_DURATION + 300);
		assertFalse(detector.isBanned(source.host()));

		// Repeating inside the same observation period costs more than the first time, so holding a source
		// down requires sustained traffic rather than one burst.
		hit(() -> detector.malformedMessage(source), HITS);
		assertTrue(detector.isBanned(source.host()));

		Thread.sleep(SUPPRESSION_DURATION + 300);
		assertTrue(detector.isBanned(source.host()), "a repeat offence must be suppressed for longer");

		Thread.sleep(SUPPRESSION_DURATION + 300);
		assertFalse(detector.isBanned(source.host()));
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testMassBanPromotesASuppressedSourceAndNeverShortensIt() throws Exception {
		// The two tiers reach the ban table from independent paths, so a short suppression must never
		// overwrite - or be overwritten by - a longer ban on the same source. The reachable case is a source
		// that is briefly suppressed while its record still carries an id from an earlier proven
		// observation, and is then swept up by the same-id scan.
		Id id = Id.random();
		var source = addr("192.168.14.1");

		detector.misbehaved(source, id);
		hit(() -> detector.malformedMessage(source), HITS - 1);
		assertTrue(detector.isBanned(source.host()), "the unproven hits suppress it briefly");

		// Seven more proven sources presenting the same id complete the fleet.
		for (var i = 2; i <= 8; i++)
			detector.misbehaved(addr("192.168.14." + i), id);

		Thread.sleep(SUPPRESSION_DURATION * 3);
		assertTrue(detector.isBanned(source.host()),
				"the proven mass-ban must outlast the suppression it replaced");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testIdentityChurnAtOneEndpointIsChargedToTheSource() {
		// The Sybil budget. Ids are free, so the ceiling is charged to the address they arrive from - but
		// what counts as churn is measured at the endpoint, one ip:port.
		//
		// Charged here, where the change is seen, and not by whoever acts on the report: the identity that
		// churned need not be a contact the routing table holds, and gating this on the table stops it
		// counting after the first rotation. SybilTests.TestIds is the end-to-end guard for that.
		var endpoint = addr("192.168.15.1");

		// The first id is not churn - there is nothing yet to have changed from.
		Id first = Id.random();
		assertNull(detector.observed(endpoint, first), "a first sighting is not a change");

		Id previous = first;
		for (var i = 0; i < HITS - 1; i++) {
			Id next = Id.random();
			assertEquals(previous, detector.observed(endpoint, next),
					"a change must report the id that was there before");
			previous = next;
		}

		assertFalse(detector.isBanned(endpoint.host()), "one under the budget must still be served");

		detector.observed(endpoint, Id.random());
		assertTrue(detector.isBanned(endpoint.host()), "rotating identities past the budget must cost the source");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testIdentityChurnOnlySuppresses() throws Exception {
		// Unproven tier: the address on the packet that reveals a change is written by its sender, so this
		// charge can be aimed at the endpoint it names. It may therefore suppress a source and must never
		// hold one for the ban duration.
		var endpoint = addr("192.168.15.5");
		detector.observed(endpoint, Id.random());
		for (var i = 0; i < HITS; i++)
			detector.observed(endpoint, Id.random());

		assertTrue(detector.isBanned(endpoint.host()));

		Thread.sleep(SUPPRESSION_DURATION * 3);
		assertFalse(detector.isBanned(endpoint.host()),
				"an unproven report must not hold a source for the ban duration");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testDistinctPeersSharingAnAddressAreNotChurn() {
		// The regression this test exists for: many nodes behind one NAT, one datacenter host or one IPv6
		// /64 present many different ids from a single source. That is not one peer changing identity, and
		// treating it as such suppresses an entire neighbourhood at once - it took out a 32-node cluster.
		for (var port = 39001; port < 39001 + HITS * 2; port++)
			assertNull(detector.observed(SocketAddress.inetSocketAddress(port, "192.168.15.2"), Id.random()),
					"a distinct endpoint is a distinct peer, not a changed identity");

		assertFalse(detector.isBanned("192.168.15.2"), "peers sharing an egress address must not look like churn");
		assertEquals(0, detector.getBannedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testStableIdentityIsNotChurn() {
		// A node talking to us repeatedly is the normal case and must never accumulate anything.
		var endpoint = addr("192.168.15.3");
		Id id = Id.random();

		for (var i = 0; i < HITS * 4; i++)
			assertNull(detector.observed(endpoint, id), "an unchanged identity is not a change");

		assertFalse(detector.isBanned(endpoint.host()));
		assertEquals(0, detector.getBannedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testHitsAreCountedPerSourceNotPerAddress() {
		// 32 events spread over 32 distinct IPv4 sources ban none of them: each source carries one hit.
		for (var i = 0; i < HITS; i++)
			detector.malformedMessage(addr("192.168.9." + i));

		for (var i = 0; i < HITS; i++)
			assertFalse(detector.isBanned("192.168.9." + i));

		assertEquals(0, detector.getBannedSize());
		assertEquals(HITS, detector.getObservedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testIPv6AddressesShareOneRecordPerSlash64() {
		// One /64 is what an ordinary IPv6 subscriber or VPS tenant is handed, so every address in it has to
		// draw on the same budget. Spread the threshold across distinct addresses inside one /64 and the
		// source is still suppressed.
		for (var i = 0; i < HITS; i++)
			detector.malformedMessage(addr("2001:db8:1:1::" + Integer.toHexString(i + 1)));

		assertEquals(1, detector.getObservedSize(), "one /64 is one source");
		assertTrue(detector.isBanned("2001:db8:1:1::99"), "an unused address in a suppressed /64 is suppressed");
		assertFalse(detector.isBanned("2001:db8:1:2::1"), "a different /64 is a different source");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testUnprovenObservationsCannotTriggerTheSameIdMassBan() {
		// The mass-ban exists for one id answering from a fleet of addresses. Unproven observations must not
		// be able to point it at anyone: an attacker that could write its chosen id onto addresses it does
		// not hold would have a cheap way to ban them all.
		Id id = Id.random();
		for (var i = 0; i < 12; i++)
			detector.inconsistent(addr("192.168.10." + i), id);

		assertEquals(0, detector.getBannedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testProvenObservationsTriggerTheSameIdMassBan() {
		// DefaultSuspiciousNodeDetector.SUSPICIOUS_OBSERVATION_HITS == 8
		Id id = Id.random();
		for (var i = 0; i < 8; i++)
			detector.misbehaved(addr("192.168.11." + i), id);

		assertEquals(8, detector.getBannedSize());
		for (var i = 0; i < 8; i++)
			assertTrue(detector.isBanned("192.168.11." + i));
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void testObservationTableIsCapped() {
		// Records are cheap to create by design - that is what makes the unproven tier safe to keep - so the
		// table has to be bounded by capacity, not merely by expiry.
		//
		// The input is derived from the cap rather than written as a number: a literal that happens to be
		// below the cap would exercise nothing and still pass, which is the failure mode a capacity test can
		// least afford.
		int sources = DefaultSuspiciousNodeDetector.MAX_OBSERVED_SOURCES * 2;
		for (var i = 0; i < sources; i++)
			detector.malformedMessage(SocketAddress.inetSocketAddress(39001, "10." + (i >> 16 & 0xff) + "." + (i >> 8 & 0xff) + "." + (i & 0xff)));

		assertTrue(detector.getObservedSize() <= DefaultSuspiciousNodeDetector.MAX_OBSERVED_SOURCES,
				"observation table must hold at its cap, was " + detector.getObservedSize());
		assertEquals(DefaultSuspiciousNodeDetector.MAX_OBSERVED_SOURCES, detector.getObservedSize(),
				"and it must actually be full - otherwise the cap was never reached and nothing was proven");
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testBanExpiresLazilyWithoutPurge() throws Exception {
		// Short ban so the test is fast; high hit threshold replaced by a small one.
		SuspiciousNodeDetector d = SuspiciousNodeDetector.create(60_000, 4, 500, 500);
		var addr = SocketAddress.inetSocketAddress(39001, "10.0.0.1");
		for (var i = 0; i <= 4; i++)
			d.misbehaved(addr, Id.random());

		assertTrue(d.isBanned(addr.host()));
		assertEquals(1, d.getBannedSize());

		// Wait past the ban duration but DO NOT purge.
		Thread.sleep(700);

		// Lazy expiry: the ban no longer takes effect even though purge() has not run and the entry remains.
		assertFalse(d.isBanned(addr.host()), "ban must expire on read, independent of purge");
		assertEquals(1, d.getBannedSize(), "entry remains in the map until purge() - expiry is lazy on read");

		d.purge();
		assertEquals(0, d.getBannedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testPurgeReclaimsExpiredObservations() throws Exception {
		SuspiciousNodeDetector d = SuspiciousNodeDetector.create(500, HITS, BAN_DURATION, SUPPRESSION_DURATION);
		for (var i = 0; i < 10; i++)
			d.malformedMessage(addr("192.168.12." + i));

		assertEquals(10, d.getObservedSize());

		Thread.sleep(700);
		d.purge();
		assertEquals(0, d.getObservedSize());
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	public void testClear() {
		var source = addr("192.168.13.1");
		hit(() -> detector.malformedMessage(source), HITS);

		assertTrue(detector.getBannedSize() > 0);
		detector.clear();

		assertEquals(0, detector.getObservedSize());
		assertEquals(0, detector.getBannedSize());
		assertFalse(detector.isBanned(source.host()));
	}
}
