package com.chaosthedude.explorerscompass.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;

/**
 * Lets a preview run the data markers of a template piece, which is where several structures put
 * their loot.
 *
 * <p><b>What is opened up.</b> The hook a template piece is given for each {@code DATA} structure
 * block in its template once the template has been placed. A shipwreck, an igloo, an end city and a
 * woodland mansion name the loot table of a chest through one of these rather than in the chest's
 * own data, and an ocean ruin places the chest itself from one; a template read block by block
 * without them has the chest and not the loot, or neither.
 *
 * <p><b>Why an invoker.</b> The method already exists on every template piece and already does
 * exactly what is wanted; only its visibility is in the way. Placing the template through the
 * piece instead would run the processors the placement carries, which weather and rot a template
 * into one instance of the structure rather than the structure itself.
 *
 * <p><b>Compatibility.</b> Nothing is injected and no code runs. The method is abstract on the piece
 * and implemented by each kind of piece, so the invoker reaches whichever implementation the piece
 * has, a mod's included.
 */
@Mixin(TemplateStructurePiece.class)
public interface TemplateStructurePieceInvoker {

	@Invoker("handleDataMarker")
	void explorerscompass$handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box);

}
