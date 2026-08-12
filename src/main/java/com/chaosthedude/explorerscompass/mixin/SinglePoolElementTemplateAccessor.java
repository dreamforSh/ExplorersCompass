package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Lets a preview resolve the template behind one piece of a jigsaw structure.
 *
 * <p><b>What is opened up.</b> The method that turns a pool element into the template it names.
 * Everything a village, an outpost, a bastion or an ancient city is assembled from is a pool
 * element, and this is the only place the template it stands for is worked out.
 *
 * <p><b>Why a mixin.</b> A jigsaw piece hands out its element, its position and its rotation, but
 * nothing that leads from the element to its blocks. The public methods on a pool element either
 * place it into a world, which a preview must not do, or answer about its jigsaw blocks alone.
 *
 * <p><b>Why an invoker.</b> The method already exists and already does exactly what is wanted; only
 * its visibility is in the way. An invoker calls it as it stands rather than reimplementing the
 * lookup, which would have to be kept in step with how templates are resolved.
 *
 * <p><b>Compatibility.</b> Nothing is injected and no code runs. The invoker also reaches
 * {@code LegacySinglePoolElement}, which extends this class, so both kinds of single element
 * resolve the same way.
 */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementTemplateAccessor {

	@Invoker("getTemplate")
	StructureTemplate explorerscompass$getTemplate(StructureTemplateManager templateManager);

}
