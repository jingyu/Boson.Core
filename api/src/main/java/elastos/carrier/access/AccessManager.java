package elastos.carrier.access;

import elastos.carrier.Id;

public interface AccessManager {
	default public boolean allow(Id subjectNode, String targetServiceId) {
		return true;
	}

	default public Permission getPermission(Id subjectNode, String targetServiceId) {
		return null;
	}
}
