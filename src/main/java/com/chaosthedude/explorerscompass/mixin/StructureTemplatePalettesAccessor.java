package com.chaosthedude.explorerscompass.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Lets a preview read every block a structure template is made of.
 *
 * <p><b>What is opened up.</b> The palettes a template holds. Each of them is the whole template
 * written out as blocks; which one a placement uses is decided when it is placed, and a preview
 * reads the first, since a preview shows what the structure looks like rather than what one
 * particular instance of it turned out as.
 *
 * <p><b>Why a mixin.</b> There is no way to ask a template for its blocks. The one public method
 * that answers with any of them, {@code filterBlocks}, answers for a single block type, so reading
 * a whole template through it means asking once for every block in the game and is a question
 * about the block registry rather than about the template. Nothing else on the class exposes them.
 *
 * <p><b>Why an accessor.</b> This reads a field and changes no behaviour, which is the least a
 * mixin can do. An access transformer would open the field just as well; it is kept here so that
 * everything the preview needs to reach into worldgen is declared in one place, beside the invoker
 * that resolves a jigsaw element's template.
 *
 * <p><b>Compatibility.</b> Nothing is injected and no code runs, so nothing another mod does to
 * templates is affected. The list handed back is the template's own and must not be modified.
 */
@Mixin(StructureTemplate.class)
public interface StructureTemplatePalettesAccessor {

	@Accessor("palettes")
	List<StructureTemplate.Palette> explorerscompass$getPalettes();

}
