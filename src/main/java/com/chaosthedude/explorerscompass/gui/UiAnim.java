package com.chaosthedude.explorerscompass.gui;

import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Frame-rate independent easing of the values the interface animates: hover fades, a selection
 * sliding from one row to the next, a switch's knob crossing its track.
 *
 * <p>A screen or a list that draws many rows keeps one of these, calls {@link #frame()} once per
 * render and then {@link #approach} for each value it animates, keyed by whatever identifies the
 * value. A widget that only animates itself keeps a {@link Value} instead, which measures its own
 * time between frames.
 */
@OnlyIn(Dist.CLIENT)
public final class UiAnim {

	/** How quickly a hover fades in and out, as the times per second the gap left shrinks by e. */
	public static final float HOVER_SPEED = 16.0F;
	/** How quickly a selection, a knob or a sliding pill settles where it is going. */
	public static final float SLIDE_SPEED = 18.0F;

	private final Object2FloatOpenHashMap<Object> values = new Object2FloatOpenHashMap<Object>();
	private long lastMillis = Util.getMillis();
	private float dt;

	public UiAnim() {
		values.defaultReturnValue(Float.NaN);
	}

	/** Measures the time since the last frame; called once at the start of every render. */
	public void frame() {
		final long now = Util.getMillis();
		dt = Math.min(0.1F, (now - lastMillis) / 1000.0F);
		lastMillis = now;
	}

	/**
	 * Moves the value kept under the given key toward a target and answers where it now stands. The
	 * first call snaps straight to the target, so that nothing animates in from nowhere when a screen
	 * opens.
	 */
	public float approach(Object key, float target, float speed) {
		final float current = values.getFloat(key);
		if (current == target) {
			return target;
		}
		final float next = step(current, target, speed, dt);
		values.put(key, next);
		return next;
	}

	/** How far a hover has faded in, from 0 to 1. */
	public float hover(Object key, boolean hovered) {
		return approach(key, hovered ? 1.0F : 0.0F, HOVER_SPEED);
	}

	/** Puts the value under the given key straight at the target, so that it does not slide there. */
	public void snap(Object key, float target) {
		values.put(key, target);
	}

	/** Forgets every value, for when what they were kept for has been replaced. */
	public void clear() {
		values.clear();
	}

	private static float step(float current, float target, float speed, float dt) {
		if (Float.isNaN(current)) {
			return target;
		}
		final float next = current + (target - current) * (1.0F - (float) Math.exp(-speed * dt));
		return Math.abs(target - next) < 0.002F ? target : next;
	}

	/** One eased value that keeps its own time, for a widget that animates nothing but itself. */
	public static final class Value {

		private float value = Float.NaN;
		private long lastMillis;

		/** Moves toward the target by however much time has passed since the last call, and answers where it stands. */
		public float approach(float target, float speed) {
			final long now = Util.getMillis();
			final float dt = Float.isNaN(value) ? 0.0F : Math.min(0.1F, (now - lastMillis) / 1000.0F);
			lastMillis = now;
			value = step(value, target, speed, dt);
			return value;
		}

		public float hover(boolean hovered) {
			return approach(hovered ? 1.0F : 0.0F, HOVER_SPEED);
		}

	}

}
