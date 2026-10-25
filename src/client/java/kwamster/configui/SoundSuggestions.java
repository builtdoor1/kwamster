package kwamster.configui;

import java.util.List;

/**
 * The curated vanilla sound ids offered by each tier's "Sound id" dropdown (§8.1). Every id is
 * verified against {@code net/minecraft/sounds/SoundEvents.java} for 1.21.11 and includes each
 * tier's default. The dropdown runs in suggestion mode, so any other id (a resource-pack sound, for
 * example) can still be typed.
 */
public final class SoundSuggestions {
	/** Namespaced ids, in the order the dropdown lists them. */
	public static final List<String> IDS = List.of(
			"minecraft:entity.experience_orb.pickup",
			"minecraft:block.note_block.pling",
			"minecraft:block.note_block.chime",
			"minecraft:block.note_block.bell",
			"minecraft:block.note_block.hat",
			"minecraft:block.note_block.bass",
			"minecraft:block.note_block.bit",
			"minecraft:block.note_block.xylophone",
			"minecraft:block.note_block.iron_xylophone",
			"minecraft:block.note_block.cow_bell",
			"minecraft:block.note_block.harp",
			"minecraft:block.note_block.snare",
			"minecraft:block.note_block.basedrum",
			"minecraft:block.note_block.flute",
			"minecraft:block.note_block.guitar",
			"minecraft:block.note_block.banjo",
			"minecraft:block.amethyst_block.chime",
			"minecraft:block.amethyst_block.hit",
			"minecraft:entity.arrow.hit_player",
			"minecraft:entity.item.pickup",
			"minecraft:entity.player.levelup",
			"minecraft:entity.player.attack.crit",
			"minecraft:entity.player.attack.strong",
			"minecraft:entity.player.attack.knockback",
			"minecraft:entity.player.attack.weak",
			"minecraft:ui.button.click"
	);

	private SoundSuggestions() {
	}
}
