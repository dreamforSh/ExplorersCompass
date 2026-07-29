package com.chaosthedude.explorerscompass.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;

class StructureUtilsTest {

	@Test
	void coordinatesReadOutWithTheHeightWhenThereIsOne() {
		assertEquals("1234, 64, -567", StructureUtils.formatCoordinates(1234, 64, -567));
	}

	@Test
	void anUndeterminedHeightIsLeftOutRatherThanWrittenWrong() {
		assertEquals("1234, -567", StructureUtils.formatCoordinates(1234, ExplorersCompassItem.UNKNOWN_Y, -567));
	}

	@Test
	void aHeightOfZeroIsStillAHeight() {
		// Nothing about the void is unknown, and a search that reported it has to read as having done so
		assertEquals("0, 0, 0", StructureUtils.formatCoordinates(0, 0, 0));
	}

	@Test
	void negativeCoordinatesKeepTheirSigns() {
		assertEquals("-30000000, -64, -30000000", StructureUtils.formatCoordinates(-30000000, -64, -30000000));
	}

}
