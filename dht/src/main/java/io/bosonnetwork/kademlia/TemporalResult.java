package io.bosonnetwork.kademlia;

import io.bosonnetwork.Network;
import io.bosonnetwork.Result;

public class TemporalResult<T> extends Result<T> {
	public TemporalResult(T v4, T v6) {
		super(v4, v6);
	}

	public TemporalResult() {
		super(null, null);
	}

	public void setV4(T value) {
		setValue(Network.IPv4, value);
	}

	public void setV6(T value) {
		setValue(Network.IPv6, value);
	}

	@Override
	public void setValue(Network network, T value) {
		super.setValue(network, value);
	}
}
