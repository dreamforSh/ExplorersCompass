package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.util.RenderUtils;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class TransparentTextField extends EditBox {

	/** The glyph that empties the field, and how much room is kept clear for it. */
	private static final String CLEAR_GLYPH = "✕";
	private static final int CLEAR_WIDTH = 12;

	private Font font;
	private Component label;
	private int labelColor = GuiTheme.TEXT_MUTED;
	/** Whether what has been typed is not something the field can take, which its border shows in red. */
	private boolean invalid;
	/** Whether the field offers the cross that empties it, which a field that must always hold something does not. */
	private boolean clearable = true;
	private final UiAnim.Value focusAnim = new UiAnim.Value();
	private final UiAnim.Value hoverAnim = new UiAnim.Value();
	private final UiAnim.Value clearAnim = new UiAnim.Value();

	private boolean pseudoIsEnabled = true;
	private boolean pseudoEnableBackgroundDrawing = true;
	private int pseudoMaxStringLength = 32;
	private int pseudoLineScrollOffset;
	private int pseudoEnabledColor = 14737632;
	private int pseudoDisabledColor = 7368816;
	/**
	 * When this field last took focus. The vanilla box blinks its caret off the time since that
	 * moment rather than off a counter advanced once a tick, since there is no longer a tick to
	 * advance one in, and the field it keeps it in is private.
	 */
	private long pseudoFocusedTime = Util.getMillis();
	private int pseudoSelectionEnd;

	public TransparentTextField(Font font, int x, int y, int width, int height, Component label) {
		super(font, x, y, width, height, label);
		this.font = font;
		this.label = label;
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (isVisible()) {
			final int x = getX();
			final int y = getY();
			final boolean showClear = clearable && !getValue().isEmpty();
			if (pseudoEnableBackgroundDrawing) {
				// The border is what says whether typing lands here, which the blinking cursor alone
				// does not make obvious on a field that has no chrome of its own; it eases toward the
				// accent as the field takes the focus, and turns red over anything it cannot take
				final float focus = focusAnim.approach(isFocused() ? 1.0F : 0.0F, UiAnim.SLIDE_SPEED);
				final float hover = hoverAnim.hover(isHovered());
				UiShapes.roundRect(guiGraphics, x, y, width, height, GuiTheme.CONTROL_RADIUS, GuiTheme.INPUT_BACKGROUND);
				final int resting = GuiTheme.lerpColor(GuiTheme.WIDGET_BORDER, GuiTheme.withAlpha(GuiTheme.TEXT_MUTED, 0xA0), hover);
				final int border = invalid ? GuiTheme.opaque(GuiTheme.TEXT_DANGER) : GuiTheme.lerpColor(resting, GuiTheme.withAlpha(GuiTheme.ACCENT, 0xE0), focus);
				UiShapes.roundRectStroke(guiGraphics, x, y, width, height, GuiTheme.CONTROL_RADIUS, focus > 0.5F || invalid ? 1.0F : UiShapes.stroke(), border);
			}
			if (showClear) {
				final boolean overClear = isOverClearGlyph(mouseX, mouseY);
				final float clearHover = clearAnim.hover(overClear);
				final float glyphLeft = x + width - CLEAR_WIDTH + 1;
				if (clearHover > 0.01F) {
					UiShapes.circle(guiGraphics, glyphLeft + font.width(CLEAR_GLYPH) / 2.0F, y + height / 2.0F, 5.5F, GuiTheme.withAlpha(GuiTheme.TEXT_DANGER, Math.round(0x40 * clearHover)));
				}
				guiGraphics.drawString(font, CLEAR_GLYPH, (int) glyphLeft, y + (height - 8) / 2, GuiTheme.lerpColor(GuiTheme.TEXT_MUTED, GuiTheme.TEXT_PRIMARY, clearHover), false);
			} else {
				clearAnim.hover(false);
			}
			boolean showLabel = !isFocused() && getValue().isEmpty();
            int i = showLabel ? labelColor : (pseudoIsEnabled ? pseudoEnabledColor : pseudoDisabledColor);
			int j = getCursorPosition() - pseudoLineScrollOffset;
			int k = pseudoSelectionEnd - pseudoLineScrollOffset;
			String text = showLabel ? label.getString() : getValue();
			String s = font.plainSubstrByWidth(text.substring(pseudoLineScrollOffset), getWidth() - (showClear ? CLEAR_WIDTH + 4 : 4));
			boolean flag = j >= 0 && j <= s.length();
			boolean flag1 = isFocused() && (Util.getMillis() - pseudoFocusedTime) / 300L % 2L == 0L && flag;
			int l = pseudoEnableBackgroundDrawing ? x + 4 : x;
			int i1 = pseudoEnableBackgroundDrawing ? y + (height - 8) / 2 : y;
			int j1 = l;

			if (k > s.length()) {
				k = s.length();
			}

			if (!s.isEmpty()) {
				String s1 = flag ? s.substring(0, j) : s;
				j1 = guiGraphics.drawString(font, s1, l, i1, i, true);
			}

			boolean flag2 = getCursorPosition() < getValue().length() || getValue().length() >= pseudoMaxStringLength;
			int k1 = j1;

			if (!flag) {
				k1 = j > 0 ? l + width : l;
			} else if (flag2) {
				k1 = j1 - 1;
				--j1;
			}

			if (!s.isEmpty() && flag && j < s.length()) {
				j1 = guiGraphics.drawString(font, s.substring(j), j1, i1, i, true);
			}

			if (flag1) {
				if (flag2) {
					RenderUtils.drawRect(guiGraphics, k1, i1 - 1, k1 + 1, i1 + 1 + font.lineHeight, -3092272);
				} else {
					guiGraphics.drawString(font, "_", k1, i1, i, true);
				}
			}

			if (k != j) {
				int l1 = l + font.width(s.substring(0, k));
				drawSelectionBox(guiGraphics, k1, i1 - 1, l1 - 1, i1 + 1 + font.lineHeight);
			}
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Emptying the field is a click away, rather than a held backspace, which matters when the
		// filter is what is standing between the player and the rest of the list
		if (button == 0 && clearable && !getValue().isEmpty() && isOverClearGlyph((int) mouseX, (int) mouseY)) {
			setValue("");
			setFocused(true);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean isOverClearGlyph(int mouseX, int mouseY) {
		return isVisible() && mouseX >= getX() + width - CLEAR_WIDTH && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
	}

	@Override
	public void setEditable(boolean enabled) {
		super.setEditable(enabled);
		pseudoIsEnabled = enabled;
	}

	@Override
	public void setTextColor(int color) {
		super.setTextColor(color);
		pseudoEnabledColor = color;
	}

	@Override
	public void setTextColorUneditable(int color) {
		super.setTextColorUneditable(color);
		pseudoDisabledColor = color;
	}

	@Override
	public void setFocused(boolean isFocused) {
		if (isFocused && !isFocused()) {
			pseudoFocusedTime = Util.getMillis();
		}
		super.setFocused(isFocused);
	}
	
	@Override
	public void setBordered(boolean enableBackgroundDrawing) {
		super.setBordered(enableBackgroundDrawing);
		pseudoEnableBackgroundDrawing = enableBackgroundDrawing;
	}
	
	@Override
	public void setMaxLength(int length) {
		super.setMaxLength(length);
		pseudoMaxStringLength = length;
	}
	
	@Override
	public void setHighlightPos(int position) {
		super.setHighlightPos(position);
		int i = getValue().length();
	      pseudoSelectionEnd = Mth.clamp(position, 0, i);
	      if (font != null) {
	         if (pseudoLineScrollOffset > i) {
	            pseudoLineScrollOffset = i;
	         }

	         int j = getInnerWidth();
	         String s = font.plainSubstrByWidth(getValue().substring(this.pseudoLineScrollOffset), j, false);
	         int k = s.length() + pseudoLineScrollOffset;
	         if (pseudoSelectionEnd == pseudoLineScrollOffset) {
	            pseudoLineScrollOffset -= font.plainSubstrByWidth(getValue(), j, true).length();
	         }

	         if (pseudoSelectionEnd > k) {
	        	 pseudoLineScrollOffset += pseudoSelectionEnd - k;
	         } else if (pseudoSelectionEnd <= pseudoLineScrollOffset) {
	        	 pseudoLineScrollOffset -= pseudoLineScrollOffset - pseudoSelectionEnd;
	         }

	         pseudoLineScrollOffset = Mth.clamp(pseudoLineScrollOffset, 0, i);
	      }
	}

	/** Marks what has been typed as something the field cannot take, which shows as a red border. */
	public void setInvalid(boolean invalid) {
		this.invalid = invalid;
	}

	public boolean isInvalid() {
		return invalid;
	}

	/** Leaves out the cross that empties the field, for a field that must always hold something. */
	public void setClearable(boolean clearable) {
		this.clearable = clearable;
	}

	public void setLabel(Component label) {
		this.label = label;
	}

	public void setLabelColor(int labelColor) {
		this.labelColor = labelColor;
	}

	/**
	 * Marks the selected run of text. The inverting blend this used to set up by hand is now a render
	 * type of its own, which is what the vanilla field draws its own selection with.
	 */
	private void drawSelectionBox(GuiGraphics guiGraphics, int startX, int startY, int endX, int endY) {
		if (startX < endX) {
			int i = startX;
			startX = endX;
			endX = i;
		}

		if (startY < endY) {
			int j = startY;
			startY = endY;
			endY = j;
		}

		if (endX > getX() + width) {
			endX = getX() + width;
		}

		if (startX > getX() + width) {
			startX = getX() + width;
		}

		guiGraphics.fill(RenderType.guiTextHighlight(), endX, endY, startX, startY, -16776961);
	}

}