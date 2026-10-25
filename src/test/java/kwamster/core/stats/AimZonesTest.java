package kwamster.core.stats;

import org.junit.jupiter.api.Test;

import static kwamster.core.model.AimZone.CENTER;
import static kwamster.core.model.AimZone.EDGE;
import static kwamster.core.model.AimZone.HIGH;
import static kwamster.core.model.AimZone.LOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** §5.3 zone rule: HIGH if v ≥ 1/3, else LOW if v ≤ −1/3, else EDGE if |h| ≥ 0.5, else CENTER. */
final class AimZonesTest {
	@Test
	void verticalThirds() {
		assertEquals(HIGH, AimZones.of(0, 1.0 / 3.0));
		assertEquals(CENTER, AimZones.of(0, 0.3332));
		assertEquals(LOW, AimZones.of(0, -1.0 / 3.0));
		assertEquals(CENTER, AimZones.of(0, -0.3332));
		assertEquals(HIGH, AimZones.of(0, 1.0));
		assertEquals(LOW, AimZones.of(0, -1.0));
	}

	@Test
	void horizontalEdges() {
		assertEquals(EDGE, AimZones.of(0.5, 0));
		assertEquals(EDGE, AimZones.of(-0.5, 0));
		assertEquals(CENTER, AimZones.of(-0.49, 0));
		assertEquals(CENTER, AimZones.of(0.49, 0.3));
	}

	@Test
	void precedence() {
		assertEquals(HIGH, AimZones.of(0.9, 0.9));
		assertEquals(HIGH, AimZones.of(-0.9, 1.0 / 3.0));
		assertEquals(LOW, AimZones.of(0.9, -0.9));
		assertEquals(LOW, AimZones.of(-0.6, -1.0 / 3.0));
		assertEquals(EDGE, AimZones.of(0.9, 0.33));
	}
}
