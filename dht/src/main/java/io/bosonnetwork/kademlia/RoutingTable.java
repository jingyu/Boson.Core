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

package io.bosonnetwork.kademlia;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.tasks.PingRefreshTask;
import io.bosonnetwork.kademlia.tasks.Task;
import io.bosonnetwork.utils.Json;

/**
 * This is a lock-free routing table implementation.
 *
 * The table itself and all buckets are Copy-on-Write list.
 * Which all mutative operations(add, remove, ...) are implemented by
 * making a fresh copy of the underlying list.
 *
 * This is ordinarily too costly, but may be more efficient for routing
 * table reading. All mutative operations are synchronized, this means
 * only one writer at the same time.
 *
 * CAUTION:
 *   All methods name leading with _ means that method will WRITE the
 *   routing table, it can only be called inside the pipeline processing.
 *
 * @hidden
 */
public final class RoutingTable {
	private final DHT dht;

	private volatile List<KBucket> buckets;

	private final AtomicInteger writeLock;
	private final Queue<Operation> pipeline;

	private long timeOfLastPingCheck;

	private final Map<KBucket, Task> maintenanceTasks = new IdentityHashMap<>();

	private static final Logger log = LoggerFactory.getLogger(RoutingTable.class);

	private static class Operation {
		public static final int PUT = 1;
		public static final int REMOVE = 2;
		public static final int ON_SEND = 3;
		public static final int ON_TIMEOUT = 4;
		public static final int MAINTENANCE = 5;

		public final int code;
		public final Id id;
		public final KBucketEntry entry;

		private Operation(int code, Id id, KBucketEntry entry) {
			this.code = code;
			this.id = id;
			this.entry = entry;
		}

		public static Operation put(KBucketEntry entry) {
			return new Operation(PUT, null, entry);
		}

		public static Operation remove(Id id) {
			return new Operation(REMOVE, id, null);
		}

		public static Operation onSend(Id id) {
			return new Operation(ON_SEND, id, null);
		}

		public static Operation onTimeout(Id id) {
			return new Operation(ON_TIMEOUT, id, null);
		}

		public static Operation maintenance() {
			return new Operation(MAINTENANCE, null, null);
		}
	}

	public RoutingTable(DHT dht) {
		this.dht = dht;
		this.buckets = new ArrayList<>();
		this.writeLock = new AtomicInteger(0);
		this.pipeline = new ConcurrentLinkedQueue<>();
		buckets.add(new KBucket(Prefix.all(), x -> true));
	}

	private List<KBucket> getBuckets() {
		return buckets;
	}

	private void setBuckets(List<KBucket> buckets) {
		this.buckets = buckets;
	}

	private DHT getDHT() {
		return dht;
	}

	public int size() {
		return getBuckets().size();
	}

	public KBucket get(int index) {
		return getBuckets().get(index);
	}

	public KBucketEntry getEntry(Id id, boolean includeCache) {
		return bucketOf(id).get(id, includeCache);
	}

	public List<KBucket> buckets() {
		return Collections.unmodifiableList(getBuckets());
	}

	public Stream<KBucket> stream() {
		return getBuckets().stream();
	}

	public int indexOf(Id id) {
		return indexOf(getBuckets(), id);
	}

	public KBucket bucketOf(Id id) {
		List<KBucket> bucketsRef = getBuckets();
		return bucketsRef.get(indexOf(bucketsRef, id));
	}

	static int indexOf(List<KBucket> bucketsRef, Id id) {
		int low = 0;
		int mid = 0;
		int high = bucketsRef.size() - 1;
		int cmp = 0;

		while (low <= high) {
			mid = (low + high) >>> 1;
			KBucket bucket = bucketsRef.get(mid);
			cmp = id.compareTo(bucket.prefix());
			if (cmp > 0)
				low = mid + 1;
			else if (cmp < 0)
				high = mid - 1;
			else
				return mid; // match the current bucket
		}

		return cmp < 0 ? mid - 1 : mid;
	}

	/**
	 * Get the number of entries in the routing table
	 *
	 * @return the number of entries
	 */
	public int getNumBucketEntries() {
		return getBuckets().stream().flatMapToInt(b -> IntStream.of(b.size())).sum();
	}

	public int getNumCacheEntries() {
		return getBuckets().stream().flatMapToInt(b -> IntStream.of(b.cacheSize())).sum();
	}

	public KBucketEntry getRandomEntry() {
		List<KBucket> bucketsRef = getBuckets();

		int offset = Random.random().nextInt(bucketsRef.size());
		return bucketsRef.get(offset).random();
	}

	public Set<NodeInfo> getRandomEntries(int expect) {
		List<KBucket> bucketsRef = getBuckets();

		List<List<KBucketEntry>> bucketsCopy = new ArrayList<>(bucketsRef.size());
		int total = 0;
		for (KBucket bucket : bucketsRef) {
			List<KBucketEntry> entries = bucket.entries();
			bucketsCopy.add(entries);
			total += entries.size();
		}

		if (total <= expect) {
			Set<NodeInfo> result = new HashSet<>();
			bucketsCopy.forEach(result::addAll);
			return result;
		}

		AtomicInteger bucketIndex = new AtomicInteger(0);
		AtomicInteger flatIndex = new AtomicInteger(0);
		final int totalEntries = total;

		ThreadLocalRandom rnd = Random.random();
		return IntStream.generate(() -> rnd.nextInt(totalEntries))
				.distinct()
				.limit(expect)
				.sorted()
				.mapToObj((i) -> {
					while (bucketIndex.get() < bucketsCopy.size()) {
						int pos = i - flatIndex.get();
						List<KBucketEntry> b = bucketsCopy.get(bucketIndex.get());
						int s = b.size();
						if (pos < s) {
							return b.get(pos);
						} else {
							bucketIndex.incrementAndGet();
							flatIndex.addAndGet(s);
						}
					}

					return null;
				}).filter(e -> e != null)
				.collect(Collectors.toSet());
	}

	private boolean isHomeBucket(Prefix p) {
		return p.isPrefixOf(getDHT().getNode().getId());
	}

	// TODO: CHECKME!!!
	void _refreshOnly(KBucketEntry toRefresh) {
		bucketOf(toRefresh.getId())._update(toRefresh);
	}

	public void put(KBucketEntry entry) {
		pipeline.add(Operation.put(entry));
		processPipeline();
	}

	public void remove(Id id) {
		pipeline.add(Operation.remove(id));
		processPipeline();
	}

	public void onSend(Id id) {
		pipeline.add(Operation.onSend(id));
		processPipeline();
	}

	public void onTimeout(Id id) {
		pipeline.add(Operation.onTimeout(id));
		processPipeline();
	}

	void maintenance() {
		pipeline.add(Operation.maintenance());
		processPipeline();
	}

	private void processPipeline() {
		if(!writeLock.compareAndSet(0, 1))
			return;

		// we are now the exclusive writer for the routing table
		while(true) {
			Operation op = pipeline.poll();
			if(op == null)
				break;

			switch (op.code) {
			case Operation.PUT:
				_put(op.entry);
				break;

			case Operation.REMOVE:
				_remove(op.id);
				break;

			case Operation.ON_SEND:
				_onSend(op.id);
				break;

			case Operation.ON_TIMEOUT:
				_onTimeout(op.id);
				break;

			case Operation.MAINTENANCE:
				_maintenance();
				break;
			}
		}

		writeLock.set(0);

		// check if we might have to pick it up again due to races
		// schedule async to avoid infinite stacks
		if(pipeline.peek() != null)
			getDHT().getNode().getScheduler().execute(this::processPipeline);
	}

	private void _put(KBucketEntry entry) {
		Id nodeId = entry.getId();
		KBucket bucket = bucketOf(nodeId);

		while (_needsSplit(bucket, entry)) {
			_split(bucket);
			bucket = bucketOf(nodeId);
		}

		bucket._put(entry);
	}

	private KBucketEntry _remove(Id id) {
		KBucket bucket = bucketOf(id);
		KBucketEntry toRemove = bucket.get(id, false);
		if (toRemove != null)
			bucket._removeIfBad(toRemove, true);

		return toRemove;
	}

	void _onTimeout(Id id) {
		KBucket bucket = bucketOf(id);
		bucket._onTimeout(id);
	}

	void _onSend(Id id) {
		KBucket bucket = bucketOf(id);
		bucket._onSend(id);
	}

	private boolean _needsSplit(KBucket bucket, KBucketEntry newEntry) {
		if (!bucket.prefix().isSplittable() || !bucket.isFull() ||
				!newEntry.isReachable() || bucket.exists(newEntry.getId()) ||
				bucket.needsReplacement())
			return false;

		Prefix highBranch = bucket.prefix().splitBranch(true);
		return highBranch.isPrefixOf(newEntry.getId());
	}

	private void _modify(Collection<KBucket> toRemove, Collection<KBucket> toAdd) {
		List<KBucket> newBuckets = new ArrayList<>(getBuckets());
		if (toRemove != null && !toRemove.isEmpty())
			newBuckets.removeAll(toRemove);
		if (toAdd != null && !toAdd.isEmpty())
			newBuckets.addAll(toAdd);
		Collections.sort(newBuckets);
		setBuckets(newBuckets);
	}

	private void _split(KBucket bucket) {
		KBucket a = new KBucket(bucket.prefix().splitBranch(false), this::isHomeBucket);
		KBucket b = new KBucket(bucket.prefix().splitBranch(true), this::isHomeBucket);

		for (KBucketEntry e : bucket.entries()) {
			if (a.prefix().isPrefixOf(e.getId()))
				a._put(e);
			else
				b._put(e);
		}

		for (KBucketEntry e : bucket.cacheEntries()) {
			if (a.prefix().isPrefixOf(e.getId()))
				a._put(e);
			else
				b._put(e);
		}

		_modify(List.of(bucket), List.of(a, b));
	}

	private void _mergeBuckets() {
		int i = 0;

		// perform bucket merge operations where possible
		while (true) {
			i++;

			if (i < 1)
				continue;

			List<KBucket> bucketsRef = getBuckets();
			if (i >= bucketsRef.size())
				break;

			KBucket b1 = bucketsRef.get(i - 1);
			KBucket b2 = bucketsRef.get(i);

			if (b1.prefix().isSiblingOf(b2.prefix())) {
				int effectiveSize1 = (int) (b1.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b1.cacheStream().filter(KBucketEntry::isEligibleForNodesList).count());
				int effectiveSize2 = (int) (b2.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b2.cacheStream().filter(KBucketEntry::isEligibleForNodesList).count());

				// check if the buckets can be merged without losing any effective entries
				if (effectiveSize1 + effectiveSize2 <= Constants.MAX_ENTRIES_PER_BUCKET) {
					// Insert into a new bucket directly, no splitting to avoid
					// fibrillation between merge and split operations
					KBucket newBucket = new KBucket(b1.prefix().getParent(), this::isHomeBucket);

					b1.stream().forEach(newBucket::_put);
					b2.stream().forEach(newBucket::_put);
					b1.cacheStream().forEach(newBucket::_put);
					b2.cacheStream().forEach(newBucket::_put);

					_modify(List.of(b1, b2), List.of(newBucket));

					i -= 2;
				}
			}
		}
	}

	/**
	 * Check if a buckets needs to be refreshed, and refresh if necessary.
	 */
	private void _maintenance() {
		long now = System.currentTimeMillis();

		// don't spam the checks if we're not receiving anything.
		// we don't want to cause too many stray packets somewhere in a network
		// if (!isRunning() && now - timeOfLastPingCheck < Constants.BOOTSTRAP_MIN_INTERVAL)
		if (now - timeOfLastPingCheck < Constants.ROUTING_TABLE_MAINTENANCE_INTERVAL)
			return;

		timeOfLastPingCheck = now;

		_mergeBuckets();

		Id localId = getDHT().getNode().getId();
		Collection<Id> bootstrapIds = getDHT().getBootstrapIds();

		List<KBucket> bucketsRef = getBuckets();
		for (KBucket bucket : bucketsRef) {
			boolean isHome = bucket.isHomeBucket();

			List<KBucketEntry> entries = bucket.entries();
			boolean wasFull = entries.size() >= Constants.MAX_ENTRIES_PER_BUCKET;
			for (KBucketEntry entry : entries) {
				// remove really old entries, ourselves and bootstrap nodes if the bucket is full
				if (entry.getId().equals(localId) || (wasFull && bootstrapIds.contains(entry.getId()))) {
					bucket._removeIfBad(entry, true);
					continue;
				}

				// Fix the wrong entries
				if (!bucket.prefix().isPrefixOf(entry.getId())) {
					bucket._removeIfBad(entry, true);
					put(entry);
				}
			}

			boolean refreshNeeded = bucket.needsToBeRefreshed();
			boolean replacementNeeded = bucket.needsCachePing() || (isHome && bucket.findPingableCacheEntry() != null);
			if (refreshNeeded || replacementNeeded)
				tryPingMaintenance(bucket, EnumSet.of(PingRefreshTask.Options.probeCache), "Refreshing Bucket - " + bucket.prefix());

			// only replace 1 bad entry with a replacement bucket entry at a time (per bucket)
			bucket._promoteVerifiedCacheEntry();
		}
	}

	void tryPingMaintenance(KBucket bucket, EnumSet<PingRefreshTask.Options> options, String name) {
		if (maintenanceTasks.containsKey(bucket))
			return;

		PingRefreshTask task = new PingRefreshTask(getDHT(), bucket, options);
		task.setName(name);
		if (maintenanceTasks.putIfAbsent(bucket, task) == null) {
			task.addListener(t -> maintenanceTasks.remove(bucket, task));
			getDHT().getTaskManager().add(task);
		}
	}

	CompletableFuture<Void> pingBuckets() {
		List<KBucket> bucketsRef = getBuckets();
		if (bucketsRef.isEmpty())
			return CompletableFuture.completedFuture(null);

		List<CompletableFuture<Void>> futures = new ArrayList<>(bucketsRef.size());
		for (KBucket bucket : bucketsRef) {
			if (bucket.size() == 0)
				continue;

			CompletableFuture<Void> future = new CompletableFuture<>();
			Task task = new PingRefreshTask(getDHT(), bucket, EnumSet.of(PingRefreshTask.Options.removeOnTimeout));
			task.addListener((v) -> future.complete(null));
			task.setName("Bootstrap cached table ping for " + bucket.prefix());
			getDHT().getTaskManager().add(task);
			futures.add(future);
		}

		return futures.isEmpty() ? CompletableFuture.completedFuture(null) :
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}

	CompletableFuture<Void> fillBuckets() {
		List<KBucket> bucketsRef = getBuckets();
		if (bucketsRef.isEmpty())
			return CompletableFuture.completedFuture(null);

		List<CompletableFuture<Void>> futures = new ArrayList<>(bucketsRef.size());
		for (KBucket bucket : bucketsRef) {
			int num = bucket.size();

			// just try to fill partially populated buckets
			// not empty ones, they may arise as artifacts from deep splitting
			if (num < Constants.MAX_ENTRIES_PER_BUCKET) {
				CompletableFuture<Void> future = new CompletableFuture<>();

				bucket.updateRefreshTimer();
				Task task = getDHT().findNode(bucket.prefix().createRandomId(), (v) -> {
					future.complete(null);
				});
				task.setName("Filling Bucket - " + bucket.prefix());
				futures.add(future);
			}
		}

		return futures.isEmpty() ? CompletableFuture.completedFuture(null) :
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
	}

	/**
	 * Loads the routing table from a file
	 *
	 * @param file the file that load from
	 */
	public void load(Path file) {
		if (Files.notExists(file) || !Files.isRegularFile(file))
			return;

		int totalEntries = 0;

		try (InputStream in = Files.newInputStream(file)) {
			CBORMapper mapper = new CBORMapper();
			JsonNode root = mapper.readTree(in);
			long timestamp = root.get("timestamp").asLong();

			JsonNode nodes = root.get("entries");
			if (!nodes.isArray())
				throw new IOException("Invalid node entries");

			for (JsonNode node : nodes) {
				Map<String, Object> map = mapper.convertValue(node, new TypeReference<Map<String, Object>>(){});
				KBucketEntry entry = KBucketEntry.fromMap(map);
				if (entry != null) {
					_put(entry);
					totalEntries++;
				}
			}

			nodes = root.get("cache");
			if (nodes != null) {
				if (!nodes.isArray())
					throw new IOException("Invalid node entries");

				for (JsonNode node : nodes) {
					Map<String, Object> map = mapper.convertValue(node, new TypeReference<Map<String, Object>>(){});
					KBucketEntry entry = KBucketEntry.fromMap(map);
					if (entry != null) {
						bucketOf(entry.getId())._insertIntoCache(entry);
						totalEntries++;
					}
				}
			}

			log.info("Loaded {} entries from persistent file. it was {} min old.", totalEntries,
					((System.currentTimeMillis() - timestamp) / (60 * 1000)));
		} catch (IOException e) {
			log.error("Can not load the routing table.", e);
		}
	}

	/**
	 * Saves the routing table to a file.
	 *
	 * @param file to save to.
	 * @throws IOException is an I/O error occurred.
	 */
	public void save(Path file) throws IOException {
		if (!Files.isRegularFile(file))
			return;

		if (this.getNumBucketEntries() == 0) {
			log.trace("Skip to save the empty routing table.");
			return;
		}

		Path tempFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), "-" + String.valueOf(System.currentTimeMillis()));
		try (OutputStream out = Files.newOutputStream(tempFile)) {
			CBORGenerator gen = Json.cborFactory().createGenerator(out);
			gen.writeStartObject();

			gen.writeFieldName("timestamp");
			gen.writeNumber(System.currentTimeMillis());

			gen.writeFieldName("entries");
			gen.writeStartArray();
			for (KBucket bucket : getBuckets()) {
				for (KBucketEntry entry : bucket.entries()) {
					gen.writeStartObject();

					Map<String, Object> map = entry.toMap();
					for (Map.Entry<String, Object> kv : map.entrySet()) {
						gen.writeFieldName(kv.getKey());
						gen.writeObject(kv.getValue());
					}

					gen.writeEndObject();
				}
			}
			gen.writeEndArray();

			gen.writeFieldName("cache");
			gen.writeStartArray();
			for (KBucket bucket : getBuckets()) {
				for (KBucketEntry entry : bucket.cacheEntries()) {
					gen.writeStartObject();

					Map<String, Object> map = entry.toMap();
					for (Map.Entry<String, Object> kv : map.entrySet()) {
						gen.writeFieldName(kv.getKey());
						gen.writeObject(kv.getValue());
					}

					gen.writeEndObject();
				}
			}
			gen.writeEndArray();

			gen.writeEndObject();
			gen.close();
			out.close();
			Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			// Force delete the tempFile if error occurred
			Files.deleteIfExists(tempFile);
		}
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(10240);
		List<KBucket> buckets = getBuckets();
		repr.append("buckets: ").append(buckets.size()).append(" / entries: ").append(getNumBucketEntries());
		repr.append('\n');
		for (KBucket bucket : buckets) {
			repr.append(bucket);
			repr.append('\n');
		}

		return repr.toString();
	}
}