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

package io.bosonnetwork.utils.jdbi;

import java.sql.PreparedStatement;
import java.sql.Types;

import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.argument.NullArgument;
import org.jdbi.v3.core.argument.internal.strategies.LoggableBinderArgument;
import org.jdbi.v3.core.config.ConfigRegistry;

import io.bosonnetwork.Id;


/**
 * JDBI argument factory for the {@link Id} type.
 * <p>
 * This factory enables storing {@code Id} objects as BLOBs in SQL databases by converting
 * the {@code Id} to its underlying byte representation when binding to a SQL statement.
 */
public class IdArgumentFactory extends AbstractArgumentFactory<Id> {
	/**
	 * Constructs an {@code IdArgumentFactory} for BLOB SQL types.
	 * <p>
	 * This factory will handle arguments of type {@link Id}, binding them as SQL BLOBs.
	 */
	protected IdArgumentFactory() {
		super(Types.BLOB);
	}

	/**
	 * Builds an {@link Argument} for the given {@link Id} value.
	 * <p>
	 * If the value is {@code null}, returns a {@link NullArgument} for SQL BLOB type.
	 * Otherwise, returns a {@link LoggableBinderArgument} that binds the {@code Id}'s bytes as a BLOB.
	 *
	 * @param value  the {@code Id} value to bind, or {@code null}
	 * @param config the JDBI configuration registry
	 * @return an {@code Argument} for the provided value
	 */
	@Override
	protected Argument build(Id value, ConfigRegistry config) {
		if (value == null)
			return new NullArgument(Types.BLOB);
		else
			return new LoggableBinderArgument<>(value.bytes(), PreparedStatement::setBytes);
	}
}