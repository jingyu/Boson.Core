package io.bosonnetwork.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.utils.Json;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @hidden
 */
@Command(name = "displaycache", mixinStandardHelpOptions = true, version = "Boson displaycache 2.0",
		description = "Display the cached routing table.")
public class DisplayCacheCommnand implements Callable<Integer> {
	@ArgGroup(exclusive = true, multiplicity = "0..1")
    AddressFamily af;

	/**
	 * @hidden
	 */
    static class AddressFamily {
    	@Option(names = {"-4", "--ipv4-only"}, description = "Diaplay the routing table for IPv4 only.")
    	private boolean ipv4Only = false;

    	@Option(names = {"-6", "--ipv6-only"}, description = "Diaplay the routing table for IPv6 only.")
    	private boolean ipv6Only = false;
    }

	@Parameters(paramLabel = "CACHE_LOCATION", index = "0", defaultValue = ".",
			description = "The boson cache location, default current directory.")
	private String cachePath = null;

	private void print(Path cache) {
		CBORMapper mapper = Json.cborMapper();
		long now = System.currentTimeMillis();
		try {
			JsonNode node = mapper.readTree(cache.toFile());
			long timestamp = node.get("timestamp").asLong();
			System.out.format("Timestamp: %s / %s\n",
					new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ").format(new Date(timestamp)),
					Duration.ofMillis(now - timestamp));

			JsonNode nodes = node.get("entries");
			System.out.println("Entries: " + nodes.size());
			for (JsonNode n : nodes) {
				Map<String, Object> map = mapper.convertValue(n, new TypeReference<Map<String, Object>>() {});
				KBucketEntry entry = KBucketEntry.fromMap(map);
				System.out.println("    " + entry);
			}

			nodes = node.get("cache");
			System.out.println("Cached: " + nodes.size());
			for (JsonNode n : nodes) {
				Map<String, Object> map = mapper.convertValue(n, new TypeReference<Map<String, Object>>() {});
				KBucketEntry entry = KBucketEntry.fromMap(map);
				System.out.println("    " + entry);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public Integer call() throws Exception {
		Path dir = Path.of(cachePath).normalize();
		if (dir.startsWith("~"))
			dir = Path.of(System.getProperty("user.home")).resolve(dir.subpath(1, dir.getNameCount()));
		else
			dir = dir.toAbsolutePath();

		if (af == null || !af.ipv6Only) {
			Path cache4 = dir.resolve("dht4.cache");
			if (Files.exists(cache4) && Files.isRegularFile(cache4)) {
				System.out.println("IPv4 routing table:");
				print(cache4);
			}
		}

		if (af == null || !af.ipv4Only) {
			Path cache6 = dir.resolve("dht6.cache");
			if (Files.exists(cache6) && Files.isRegularFile(cache6)) {
				System.out.println("IPv6 routing table:");
				print(cache6);
			}
		}

		return 0;
	}
}