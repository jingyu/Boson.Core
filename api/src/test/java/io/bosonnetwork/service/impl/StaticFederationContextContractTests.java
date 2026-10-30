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


package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.BeforeEach;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedNodeDescriptor;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationContextContract;

class StaticFederationContextContractTests extends FederationContextContract {
	private Identity localNode;
	private Id localService;
	private StaticFederationContext context;

	@BeforeEach
	void setUp() {
		localNode = new CryptoIdentity();
		localService = Id.random();
		context = new StaticFederationContext(localNode, localService);
	}

	@Override
	protected FederationContext context() {
		return context;
	}

	@Override
	protected Identity localNode() {
		return localNode;
	}

	@Override
	protected Id localService() {
		return localService;
	}

	@Override
	protected void admitted(Id nodeId) {
		if (!context.addNode(nodeId, "localhost", 9000))
			context.setAdmission(nodeId, Admission.Reason.ACTIVE);
	}

	@Override
	protected void refused(Id nodeId, Admission.Reason reason) {
		context.setAdmission(nodeId, reason);
	}

	@Override
	protected void announced(Identity service, Identity node, FederatedService announcement) {
		context.addService(announcement);
	}

	@Override
	protected void described(Identity node, FederatedNodeDescriptor descriptor) {
		context.putDescriptor(descriptor);
	}
}
