package io.bosonnetwork.util.jdbi;

import java.sql.PreparedStatement;
import java.sql.Types;

import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.argument.NullArgument;
import org.jdbi.v3.core.argument.internal.strategies.LoggableBinderArgument;
import org.jdbi.v3.core.config.ConfigRegistry;

import io.bosonnetwork.Id;

public class IdArgumentFactory extends AbstractArgumentFactory<Id> {
	protected IdArgumentFactory() {
		super(Types.BLOB);
	}

	@Override
	protected Argument build(Id value, ConfigRegistry config) {
		if (value == null)
			return new NullArgument(Types.BLOB);
		else
			return new LoggableBinderArgument<>(value.bytes(), PreparedStatement::setBytes);
	}
}
