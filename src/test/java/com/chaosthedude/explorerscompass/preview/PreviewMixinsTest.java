package com.chaosthedude.explorerscompass.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chaosthedude.explorerscompass.mixin.CompositeEntryBaseAccessor;
import com.chaosthedude.explorerscompass.mixin.CompositeLootItemConditionAccessor;
import com.chaosthedude.explorerscompass.mixin.LootEntryConditionsAccessor;
import com.chaosthedude.explorerscompass.mixin.LootItemAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolEntriesAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolSingletonFunctionsAccessor;
import com.chaosthedude.explorerscompass.mixin.LootPoolSingletonWeightAccessor;
import com.chaosthedude.explorerscompass.mixin.LootTablePoolsAccessor;
import com.chaosthedude.explorerscompass.mixin.NestedLootTableAccessor;
import com.chaosthedude.explorerscompass.mixin.SetItemCountFunctionAccessor;
import com.chaosthedude.explorerscompass.mixin.SetPotionFunctionAccessor;
import com.chaosthedude.explorerscompass.mixin.SinglePoolElementTemplateAccessor;
import com.chaosthedude.explorerscompass.mixin.StructureTemplatePalettesAccessor;
import com.chaosthedude.explorerscompass.mixin.TagEntryAccessor;
import com.chaosthedude.explorerscompass.mixin.TemplateStructurePieceInvoker;

import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;

/**
 * Holds every accessor the preview reads the game through to the class it reads.
 *
 * <p>An accessor that fails to apply does not fail the build: the cast to it fails the first time
 * a preview is built, which is on a server, for the first player to open one. Loading each target
 * here is what applies the accessor to it, and being assignable to it is what says it applied.
 */
class PreviewMixinsTest {

	@Test
	void theLootTableAccessorsAreApplied() {
		assertApplied(LootTablePoolsAccessor.class, LootTable.class);
		assertApplied(LootPoolEntriesAccessor.class, LootPool.class);
		assertApplied(LootEntryConditionsAccessor.class, LootPoolEntryContainer.class);
		assertApplied(LootPoolSingletonWeightAccessor.class, LootPoolSingletonContainer.class);
		assertApplied(LootPoolSingletonFunctionsAccessor.class, LootPoolSingletonContainer.class);
		assertApplied(LootItemAccessor.class, LootItem.class);
		assertApplied(TagEntryAccessor.class, TagEntry.class);
		assertApplied(NestedLootTableAccessor.class, NestedLootTable.class);
		assertApplied(CompositeEntryBaseAccessor.class, CompositeEntryBase.class);
		assertApplied(CompositeLootItemConditionAccessor.class, CompositeLootItemCondition.class);
	}

	@Test
	void theLootFunctionAccessorsAreApplied() {
		assertApplied(SetItemCountFunctionAccessor.class, SetItemCountFunction.class);
		assertApplied(SetPotionFunctionAccessor.class, SetPotionFunction.class);
	}

	@Test
	void theStructureAccessorsAreApplied() {
		assertApplied(StructureTemplatePalettesAccessor.class, StructureTemplate.class);
		assertApplied(SinglePoolElementTemplateAccessor.class, SinglePoolElement.class);
		// The one that reaches an abstract method: a piece's data markers are handled by each kind of piece for itself
		assertApplied(TemplateStructurePieceInvoker.class, TemplateStructurePiece.class);
	}

	private static void assertApplied(Class<?> accessor, Class<?> target) {
		assertTrue(accessor.isAssignableFrom(target), accessor.getSimpleName() + " was not applied to " + target.getName());
	}

}
