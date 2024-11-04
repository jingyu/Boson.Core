package io.bosonnetwork.util.jdbi;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.spi.JdbiPlugin;

public class BosonPlugin extends JdbiPlugin.Singleton {
	@Override
	public void customizeJdbi(Jdbi jdbi) {
		jdbi.registerArgument(new IdArgumentFactory());
		jdbi.registerColumnMapper(new IdColumnMapper());
	}
}
