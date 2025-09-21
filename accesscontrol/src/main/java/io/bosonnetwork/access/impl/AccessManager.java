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

package io.bosonnetwork.access.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;
import io.bosonnetwork.access.Permission;
import io.bosonnetwork.utils.Json;

/**
 * @hidden
 */
public class AccessManager implements io.bosonnetwork.access.AccessManager {
	private Path repo;
	private Path defaults;
	private Path acls;

	private WatchService watchService;
	private WatchKey keyDefaults;
	private WatchKey keyAcls;

	private Map<Subscription, AccessControlList> defaultACLs;

	private LoadingCache<Id, AccessControlList> cache;

	public static AccessControlList DEFAULT = new AccessControlList(Subscription.Free, Collections.emptyMap());

	private static final Logger log = LoggerFactory.getLogger(AccessManager.class);

	public AccessManager() {
		repo = null;
		defaults = null;
		acls = null;
	}

	public AccessManager(Path repoPath) {
		repo = repoPath.normalize().toAbsolutePath();
		defaults = repo.resolve("defaults");
		acls = repo.resolve("acls");
	}

	public AccessManager(File repoPath) throws IOException {
		this(repoPath.toPath());
	}

	public AccessManager(String repoPath) throws IOException {
		this(Path.of(repoPath));
	}

	public void init(Node node) throws IOException {
		if (node == null)
			return;

		cache = Caffeine.newBuilder()
				.initialCapacity(32)
				.maximumSize(256)
				.build(id -> {
					return loadNodeACL(id);
				});
		log.debug("Initialized the access control list cache");

		if (repo != null) {
			watchService = FileSystems.getDefault().newWatchService();

			keyDefaults = defaults.register(watchService,
					StandardWatchEventKinds.ENTRY_CREATE,
					StandardWatchEventKinds.ENTRY_DELETE,
					StandardWatchEventKinds.ENTRY_MODIFY);

			keyAcls = acls.register(watchService,
					StandardWatchEventKinds.ENTRY_CREATE,
					StandardWatchEventKinds.ENTRY_DELETE,
					StandardWatchEventKinds.ENTRY_MODIFY);

			log.debug("Registered the watcher for the access control repo");

			loadDefaults();

			/*/
			node.addStatusListener(new NodeStatusListener() {
				ScheduledFuture<?> future;

				@Override
				public void started() {
					future = node.getScheduler().scheduleWithFixedDelay(() -> {
						processRepoChanges();
					}, 60, 60, TimeUnit.SECONDS);
				}

				@Override
				public void stopping() {
					future.cancel(false);
					// none of the scheduled tasks should experience exceptions,
					// log them if they did
					try {
						future.get();
					} catch (ExecutionException | InterruptedException | CancellationException ignore) {
					}

					keyDefaults.cancel();
					keyAcls.cancel();

					try {
						watchService.close();
					} catch (IOException ignore) {
					}

					cache.invalidateAll();
					cache.cleanUp();

					log.info("Finished the cleanup");
				}
			});
			*/

			log.info("Initialized @ {}", repo);
		} else {
			initDefaults();
			log.info("Initialized with defaults");
		}
	}

	private AccessControlList loadACL(Path file) throws IOException {
		log.trace("Loading the access control list from: {}", file);
		return Json.objectMapper().readValue(file.toFile(), AccessControlList.class);
	}

	private void saveACL(Path file, AccessControlList acl) throws IOException {
		Json.objectMapper().writeValue(file.toFile(), acl);
	}

	private void initDefaults() {
		log.debug("Initialize the default access control lists...");

		EnumSet<Subscription> subscriptions = EnumSet.allOf(Subscription.class);
		defaultACLs = new EnumMap<>(Subscription.class);

		for (Subscription subscription : subscriptions) {
			log.debug("No access control list defined for: {}, using default", subscription);
			 AccessControlList acl = new AccessControlList(subscription);
			defaultACLs.put(acl.getSubscription(), acl);
		}
	}

	private void loadDefaults() {
		log.debug("Loading the default access control lists...");

		EnumSet<Subscription> subscriptions = EnumSet.allOf(Subscription.class);
		defaultACLs = new EnumMap<>(Subscription.class);

		for (Subscription subscription : subscriptions) {
			AccessControlList acl;

			Path aclFile = defaults.resolve(subscription.name());
			if (Files.notExists(aclFile) || Files.isDirectory(aclFile)) {
				log.debug("No access control list defined for: {}, using default", subscription);
				acl = new AccessControlList(subscription);
			} else {
				log.debug("Loading access control list for: {}", subscription);
				try {
					acl = loadACL(aclFile);
				} catch (IOException e) {
					log.error("Load access control list from " + aclFile + " failed. using default", e);
					acl = new AccessControlList(subscription);
				}
			}

			defaultACLs.put(acl.getSubscription(), acl);
		}
	}

	private AccessControlList loadNodeACL(Id id) {
		log.debug("Loading the access control list for: {}", id);

		if (acls == null)
			return DEFAULT;

		Path aclFile = acls.resolve(id.toString());
		if (Files.notExists(aclFile) || Files.isDirectory(aclFile)) {
			log.debug("No access control list file for: {}, using default", id);
			return DEFAULT;
		}

		AccessControlList acl;
		try {
			acl = loadACL(aclFile);
			acl.seal();
		} catch (IOException e) {
			log.error("Load access control list from " + aclFile + " failed. using default", e);
			acl = DEFAULT;
		}

		return acl;
	}

	private void processRepoChanges() {
		log.debug("Checking the repo changes...");

		while (true) {
			WatchKey key = watchService.poll();
			if (key == null)
				break;

			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();

				if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
						kind == StandardWatchEventKinds.ENTRY_MODIFY ||
						kind == StandardWatchEventKinds.ENTRY_DELETE) {
					@SuppressWarnings("unchecked")
					WatchEvent<Path> ev = (WatchEvent<Path>)event;

					Path changed = ev.context();
					if (key == keyAcls) {
						Id id = Id.of(changed.getFileName().toString());
						log.debug("Access control list for {} changed, invalidate the cached entry", id);
						cache.invalidate(id);
					} else if (key == keyDefaults) {
						try {
							AccessControlList acl = loadACL(defaults.resolve(changed));
							log.debug("Default access control list for {} changed, reload", acl.getSubscription());
							defaultACLs.put(acl.getSubscription(), acl);
						} catch (IOException e) {
							log.error("Load default access control list from " + changed + " failed, ignore changes!", e);
						}
					}
				}
			}

			key.reset();
		}
	}

	@Override
	public Permission getPermission(Id subjectNode, String targetServiceId) {
		AccessControlList nodeACL;

		nodeACL = cache.get(subjectNode);
		if (nodeACL == null)
			nodeACL = DEFAULT;

		Permission permission = nodeACL.getPermission(targetServiceId);
		if (permission == null)
			permission = defaultACLs.get(nodeACL.getSubscription()).getPermission(targetServiceId);

		return permission;
	}

	@Override
	public boolean allow(Id subjectNode, String targetServiceId) {
		return getPermission(subjectNode, targetServiceId).isAllow();
	}

	public AccessControlList getDefault(Subscription subscription) throws IOException {
		Path aclFile = defaults.resolve(subscription.name());
		return loadACL(aclFile);
	}

	public AccessControlList allow(Id subjectNode, Subscription subscription) throws IOException {
		Path aclFile = acls.resolve(subjectNode.toString());
		AccessControlList acl = new AccessControlList(subscription);
		saveACL(aclFile, acl);
		return acl;
	}

	public AccessControlList deny(Id subjectNode) throws IOException {
		Path aclFile = acls.resolve(subjectNode.toString());
		AccessControlList acl = new AccessControlList(Subscription.Blocked);
		saveACL(aclFile, acl);
		return acl;
	}

	public void remove(Id subjectNode) throws IOException {
		Path aclFile = acls.resolve(subjectNode.toString());
		Files.deleteIfExists(aclFile);
	}

	public AccessControlList get(Id subjectNode) throws IOException {
		Path aclFile = acls.resolve(subjectNode.toString());
		AccessControlList acl = loadACL(aclFile);
		acl.seal();

		return acl;
	}
}