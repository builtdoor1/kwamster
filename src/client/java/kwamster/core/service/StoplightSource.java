package kwamster.core.service;

import kwamster.core.model.StoplightReading;

/** The direction stoplight's current reading. Implemented by DIRECTION; the CORE default returns {@link StoplightReading#NONE}. */
public interface StoplightSource {
	/** The current reading; never null. Client thread. */
	StoplightReading reading();
}
