package com.chaosthedude.explorerscompass.client;

/**
 * How much the compass says about itself in its own tooltip. The coordinates it may show are the
 * server's to allow, but how much of everything else is worth reading every time a compass passes
 * under the pointer is the player's own preference, and nothing else could decide it for them.
 */
public enum TooltipDetail {

	/** Nothing at all, for a pack that reports the compass some other way. */
	NONE,
	/** The state and what follows from it, with the rest a held shift key away. */
	COMPACT,
	/** Everything at once, for a player who would rather not hold a key to read it. */
	FULL;

}
