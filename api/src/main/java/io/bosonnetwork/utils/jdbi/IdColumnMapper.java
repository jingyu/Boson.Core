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

import java.sql.ResultSet;
import java.sql.SQLException;

import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import io.bosonnetwork.Id;

/**
 * JDBI column mapper for the {@link Id} type.
 * <p>
 * This mapper converts SQL BLOB columns to {@link Id} objects by reading the bytes and invoking {@link Id#of(byte[])}.
 * If the SQL value is {@code null} or empty, this mapper returns {@code null}.
 */
public class IdColumnMapper implements ColumnMapper<Id> {
	/**
	 * Maps a SQL BLOB column to an {@link Id} object.
	 *
	 * @param r the {@link ResultSet}
	 * @param columnNumber the column number to read the value from
	 * @param ctx the JDBI statement context
	 * @return {@code null} if the SQL value is {@code null} or empty, otherwise {@link Id#of(byte[])} of the value
	 * @throws SQLException if a database access error occurs
	 */
	@Override
	public Id map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
		byte[] value = r.getBytes(columnNumber);
		return value == null || value.length == 0 ? null : Id.of(value);
	}
}