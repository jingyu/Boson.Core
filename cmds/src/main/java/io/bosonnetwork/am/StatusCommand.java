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

package io.bosonnetwork.am;

import java.util.concurrent.Callable;

import io.bosonnetwork.Id;

import io.bosonnetwork.access.impl.AccessControlList;
import io.bosonnetwork.access.impl.AccessManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "status", mixinStandardHelpOptions = true, version = "Boson access manager 2.0",
		description = "Show node access control list.")
public class StatusCommand extends AmCommand implements Callable<Integer> {
	@Parameters(paramLabel = "NODEID", index = "0", description = "The node id.")
	private String node = null;

	@Override
	public Integer call() throws Exception {
		Id id;
		try {
			id = Id.of(node);
		} catch (Exception e) {
			System.out.println("Invalid Id: " + node);
			return -1;
		}

		AccessManager am = getAccessManager();
		AccessControlList acl = am.get(id);
		if (acl != null)
			System.out.println(acl);
		else
			System.out.format("ACL for %s not exists.\nDefault is Free subscription.\n", id);

		return 0;
	}
}
