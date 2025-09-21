package io.bosonnetwork.utils.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.color.ANSIConstants;
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase;


/**
 * A Logback converter that changes the console text color based on the logging level.
 * <p>
 * This class maps log levels (ERROR, WARN, INFO, TRACE) to specific ANSI color codes,
 * enabling colored output in supported terminals.
 * </p>
 */
public class HighlightingCompositeConverter extends ForegroundCompositeConverterBase<ILoggingEvent> {

	/**
	 * Returns the ANSI color code corresponding to the logging level of the given event.
	 *
	 * @param event the logging event
	 * @return the ANSI color code as a string
	 */
	@Override
	protected String getForegroundColorCode(ILoggingEvent event) {
		Level level = event.getLevel();
		return switch (level.toInt()) {
			case Level.ERROR_INT -> "38;5;196";
			case Level.WARN_INT -> "38;5;221";
			case Level.INFO_INT -> "38;5;40";
			case Level.TRACE_INT -> "38;5;245";
			default -> ANSIConstants.DEFAULT_FG;
		};
	}

}