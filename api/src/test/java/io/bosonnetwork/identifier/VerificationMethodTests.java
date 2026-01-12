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

package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.json.Json;

public class VerificationMethodTests {
	@Test
	void verificationMethodSeder() {
		var controller = Id.random();
		String id = controller.toDIDString() + "#key-1";

		var vm = VerificationMethod.of(
				id,
				VerificationMethod.Type.Ed25519VerificationKey2020,
				controller,
				controller.toBase58String());

		assertFalse(vm.isReference());

		var json = Json.toString(vm);
		System.out.println(json);
		System.out.println(vm);

		var vm2 = Json.parse(json, VerificationMethod.class);
		assertEquals(vm, vm2);

		var vmr = vm.getReference();
		assertNotEquals(vm, vmr);
		assertEquals(vm.getId(), vmr.getId());
	}

	@Test
	void verificationMethodReferenceSeder() {
		var controller = Id.random();
		String id = controller.toDIDString() + "#key-1";

		var vm = VerificationMethod.of(id);

		assertTrue(vm.isReference());

		var json = Json.toString(vm);
		System.out.println(json);
		System.out.println(vm);

		var vm2 = Json.parse(json, VerificationMethod.class);
		assertEquals(vm, vm2);

		var vmr = vm.getReference();
		assertEquals(vm, vmr);
	}

	@Test
	void verificationMethodReference() {
		var controller = Id.random();
		String id = controller.toDIDString() + "#key-1";

		var vm = VerificationMethod.of(
				id,
				VerificationMethod.Type.Ed25519VerificationKey2020,
				controller,
				controller.toBase58String());

		var vmr = (VerificationMethod.Reference) VerificationMethod.of(id);
		assertNull(vmr.getType());
		assertNull(vmr.getController());
		assertNull(vmr.getPublicKeyMultibase());

		assertEquals(vm.getReference(), vmr);

		vmr.updateReference(vm);
		assertEquals(vm.getType(), vmr.getType());
		assertEquals(vm.getController(), vmr.getController());
		assertEquals(vm.getPublicKeyMultibase(), vmr.getPublicKeyMultibase());
	}
}