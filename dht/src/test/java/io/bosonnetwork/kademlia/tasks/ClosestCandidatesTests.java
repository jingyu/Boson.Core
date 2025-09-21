package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.utils.AddressUtils;

public class ClosestCandidatesTests {
	private static final Faker faker = new Faker();

	private Id target;
	private ClosestCandidates candidates;

	private InetSocketAddress randomAddress() {
		try {
			InetAddress addr;
			do {
				addr = InetAddress.getByName(faker.internet().publicIpV4Address());
			} while (!AddressUtils.isGlobalUnicast(addr));

			return new InetSocketAddress(addr, Random.random().nextInt(1024, 65535));
		} catch (Exception e) {
			throw new RuntimeException("randomAddress", e);
		}
	}

	@BeforeEach
	void setUp() {
		target = Id.random();
		candidates = new ClosestCandidates(target, 16);
	}

	@Test
	void testDeduplication() {
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			nodes.add(new NodeInfo(Id.random(), randomAddress()));
		candidates.add(nodes);
		assertEquals(8, candidates.size());

		// Duplicated node id
		List<NodeInfo> nodes2 = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			nodes2.add(new NodeInfo(nodes.get(i).getId(), randomAddress()));
		candidates.add(nodes2);
		assertEquals(8, candidates.size());

		// Duplicated node address
		List<NodeInfo> nodes3 = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			nodes3.add(new NodeInfo(Id.random(), nodes.get(i).getAddress()));
		candidates.add(nodes3);
		assertEquals(8, candidates.size());

		nodes.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(nodes, candidates.entries().toList());
	}

	@Test
	public void testCandidateOrder() {
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			NodeInfo node = new NodeInfo(Id.random(), randomAddress());
			nodes.add(node);
		}

		candidates.add(nodes);

		// Check the final result
		assertEquals(16, candidates.size());
		nodes.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(nodes.subList(0, 16), candidates.entries().toList());
	}

	@Test
	void testCandidateNext() {
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			nodes.add(new NodeInfo(Id.random(), randomAddress()));
		candidates.add(nodes);
		assertEquals(8, candidates.size());

		nodes.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));

		for (int i = 0; i < 8; i++) {
			CandidateNode next = candidates.next();
			assertEquals(nodes.get(i), next);
			next.setSent();
		}
	}

	@Test
	void testRemove() {
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 8; i++)
			nodes.add(new NodeInfo(Id.random(), randomAddress()));
		candidates.add(nodes);
		assertEquals(8, candidates.size());

		// remove the candidate, but the dedup should be retained
		nodes.forEach(n -> candidates.remove(n.getId()));
		assertEquals(0, candidates.size());

		candidates.add(nodes);
		// Dedup retains, no candidate added
		assertEquals(0, candidates.size());
	}

	@Test
	public void testHeadAndTail() {
		TreeSet<NodeInfo> result = new TreeSet<>((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));

		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			NodeInfo node = new NodeInfo(Id.random(), randomAddress());
			nodes.add(node);
			result.add(node);
		}

		candidates.add(nodes);

		assertEquals(8, candidates.size());
		assertEquals(result.first().getId(), candidates.head());
		assertEquals(result.last().getId(), candidates.tail());

		nodes.clear();
		for (int i = 8; i < 12; i++) {
			NodeInfo node = new NodeInfo(Id.random(), randomAddress());
			nodes.add(node);
			result.add(node);
		}

		candidates.add(nodes);

		assertEquals(12, candidates.size());
		assertEquals(result.first().getId(), candidates.head());
		assertEquals(result.last().getId(), candidates.tail());

		nodes.clear();
		for (int i = 12; i < 16; i++) {
			NodeInfo node = new NodeInfo(Id.random(), randomAddress());
			nodes.add(node);
			result.add(node);
		}

		candidates.add(nodes);

		assertEquals(16, candidates.size());
		assertEquals(result.first().getId(), candidates.head());
		assertEquals(result.last().getId(), candidates.tail());

		nodes.clear();
		for (int i = 16; i < 32; i++) {
			NodeInfo node = new NodeInfo(Id.random(), randomAddress());
			nodes.add(node);
			result.add(node);
			result.remove(result.last());
		}

		candidates.add(nodes);

		assertEquals(16, candidates.size());
		assertEquals(16, result.size());
		assertEquals(result.first().getId(), candidates.head());
		assertEquals(result.last().getId(), candidates.tail());
	}
}