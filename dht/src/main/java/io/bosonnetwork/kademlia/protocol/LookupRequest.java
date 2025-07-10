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

package io.bosonnetwork.kademlia.protocol;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;

@JsonPropertyOrder({"t", "w"})
public abstract class LookupRequest implements Request {
	@JsonProperty("t")
	protected final Id target;
	protected final boolean want4;
	protected final boolean want6;
	protected final boolean wantToken;

	protected LookupRequest(Id target, int want) {
		if (want < 0 || want > 7)
			throw new IllegalArgumentException("want");

		this.target = target;
		want4 = (want & 0x01) == 0x01;
		want6 = (want & 0x02) == 0x02;
		wantToken = (want & 0x04) == 0x04;
	}

	protected LookupRequest(Id target, boolean want4, boolean want6, boolean wantToken) {
		this.target = target;
		this.want4 = want4;
		this.want6 = want6;
		this.wantToken = wantToken;
	}

	public Id getTarget() {
		return target;
	}

	public boolean doesWant4() {
		return want4;
	}

	public boolean doesWant6() {
		return want6;
	}

	protected boolean doesWantToken() {
		return wantToken;
	}

	@JsonProperty("w")
	protected int getWant() {
		int want = 0;

		if (want4)
			want |= 0x01;

		if (want6)
			want |= 0x02;

		if (wantToken)
			want |= 0x04;

		return want;
	}

	protected static boolean isValidWant(int want) {
		return want > 0 && want <= 7;
	}

	@Override
	public int hashCode() {
		return Objects.hash(target, want4, want6, wantToken);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof LookupRequest that)
			return Objects.equals(target, that.target) &&
					want4 == that.want4 &&
					want6 == that.want6 &&
					wantToken == that.wantToken;

		return false;
	}
}