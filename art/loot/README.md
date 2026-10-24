# Loot tier icons

The SVGs in `svg/` are the source of truth for the loot interface's tier icons. The PNGs under
`src/main/resources/assets/explorerscompass/textures/gui/sprites/loot/` are generated from them and
must never be edited by hand.

Minecraft draws GUI sprites one texel to one GUI pixel, so every icon is drawn on a whole-pixel grid:

- The `viewBox` is the sprite size: `0 0 9 9` for the large icons, `0 0 7 7` for the `_small` ones.
- Only `<rect>` elements, with whole-number `x`, `y`, `width` and `height`. Anything else (paths,
  circles, fractional coordinates, transforms) is refused by the rasteriser, not approximated.
- Colours are `#RGB`, `#RRGGBB` or `#RRGGBBAA` in a `fill` on the rect or its `<g>`; `opacity` and
  `fill-opacity` multiply in. Later rects are blended over earlier ones.
- Every icon has the same rim: 1px of `#0B0D12C0` around the shape (8-neighbourhood), so it reads on
  dark panels and on bright item art alike. Each shape has a base colour, a highlight at the top-left
  and a shade at the bottom-right.

The file name is the sprite name: `svg/tier_rare.svg` becomes `explorerscompass:loot/tier_rare`.

## Regenerating the PNGs

Needs Python 3 with Pillow. From the repository root:

```
python art/loot/rasterize_sprites.py art/loot/svg src/main/resources/assets/explorerscompass/textures/gui/sprites/loot
```

Add `--preview build/loot_icons_preview.png` to also write an enlarged sheet of every icon on the
panel colour.
