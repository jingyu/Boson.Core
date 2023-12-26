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

import io.bosonnetwork.access.impl.AccessControlList;
import io.bosonnetwork.access.impl.AccessManager;
import io.bosonnetwork.access.impl.Subscription;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @hidden
 */
@Command(name = "subscription", mixinStandardHelpOptions = true, version = "Boson access manager 2.0",
		description = "Show access control list for subscription.")
public class SubscriptionCommand extends AmCommand implements Callable<Integer> {
	@Parameters(paramLabel = "SUBSCRIPTION", index = "0", description = "The subscription: Free, Professional, Premium.")
	private String subscription = null;

	@Override
	public Integer call() throws Exception {
		Subscription sub;
		try {
			String ss = subscription.substring(0, 1).toUpperCase() + subscription.substring(1).toLowerCase();
			sub = Subscription.of(ss);
		} catch (Exception e) {
			System.out.println("Unknown subscription: " + subscription);
			return -1;
		}

		AccessManager am = getAccessManager();
		AccessControlList acl = am.getDefault(sub);

		System.out.println(acl);
		return 0;
	}
}
