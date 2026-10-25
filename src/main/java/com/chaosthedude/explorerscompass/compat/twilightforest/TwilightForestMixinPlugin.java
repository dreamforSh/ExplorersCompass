package com.chaosthedude.explorerscompass.compat.twilightforest;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Applies the mixins of {@code explorerscompass.twilightforest.mixins.json} only when The Twilight
 * Forest is loaded, and only in a version they were written against.
 *
 * <p>Those mixins reach into classes of its own, which a version of it that has moved them about or
 * changed what they do would no longer match; a mixin that fails to apply takes the game down with
 * it, which a structure preview is not worth. So a version outside the tested range leaves them off,
 * says so in the log, and costs the previews of its structures what they patch, not the game.
 *
 * <p>Asked before any of the game is loaded, so it reads which mods are present from the list the
 * loader drew up, and touches no class of the game's or of any mod's.
 */
public class TwilightForestMixinPlugin implements IMixinConfigPlugin {

	private static final Logger LOGGER = LogManager.getLogger("explorerscompass");

	/** The versions of The Twilight Forest these mixins were written and tested against. */
	private static final String TESTED_VERSIONS = "[4.8,4.9)";

	private boolean apply;

	@Override
	public void onLoad(String mixinPackage) {
		final ArtifactVersion version = loadedVersion();
		if (version == null) {
			// Not installed, which is nothing worth saying
			apply = false;
			return;
		}
		try {
			apply = VersionRange.createFromVersionSpec(TESTED_VERSIONS).containsVersion(version);
		} catch (InvalidVersionSpecificationException e) {
			apply = false;
		}
		if (!apply) {
			LOGGER.warn("The Twilight Forest " + version + " is outside " + TESTED_VERSIONS + ", which the compass's patches for previewing its structures were written against, so they are left off: some of its structures may preview with parts missing");
		}
	}

	/** The version of The Twilight Forest that is loaded, or null when it is not. */
	private static ArtifactVersion loadedVersion() {
		final ModFileInfo file = LoadingModList.get().getModFileById(TwilightForestCompat.MOD_ID);
		if (file == null) {
			return null;
		}
		for (IModInfo mod : file.getMods()) {
			if (TwilightForestCompat.MOD_ID.equals(mod.getModId())) {
				return mod.getVersion();
			}
		}
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return apply;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

}
