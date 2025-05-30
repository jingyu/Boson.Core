package io.bosonnetwork.utils.jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import io.bosonnetwork.Id;

public class IdColumnMapper implements ColumnMapper<Id> {
	@Override
	public Id map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
		byte[] value = r.getBytes(columnNumber);
		return value == null || value.length == 0 ? null : Id.of(value);
	}
}