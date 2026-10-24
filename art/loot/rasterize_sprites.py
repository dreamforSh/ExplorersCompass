"""Rasterises pixel-grid SVG icons into Minecraft GUI sprites.

The SVGs are the source of truth for the interface's icons. Minecraft cannot draw SVG, and draws
its interface sprites one texel to one GUI pixel, so every icon is drawn on a whole-pixel grid out
of <rect> elements alone (shape-rendering="crispEdges"), which rasterises to exactly the PNG the
game will show. Anything other than a grid-aligned rect is refused rather than approximated.

Usage: python rasterize_sprites.py <svg dir> <png out dir> [--preview <dir>] [--scale N]
"""
import argparse
import pathlib
import sys
import xml.etree.ElementTree as ET

from PIL import Image

SVG_NS = '{http://www.w3.org/2000/svg}'


def parse_colour(value, opacity):
	value = value.strip()
	if not value.startswith('#'):
		raise ValueError(f'colour must be #RGB, #RRGGBB or #RRGGBBAA, got {value!r}')
	hexes = value[1:]
	if len(hexes) == 3:
		hexes = ''.join(c * 2 for c in hexes) + 'FF'
	elif len(hexes) == 6:
		hexes += 'FF'
	elif len(hexes) != 8:
		raise ValueError(f'bad colour {value!r}')
	r, g, b, a = (int(hexes[i:i + 2], 16) for i in range(0, 8, 2))
	return (r, g, b, round(a * opacity))


def whole(element, name):
	raw = element.get(name, '0')
	number = float(raw)
	if number != int(number):
		raise ValueError(f'{name}={raw} is not on the pixel grid')
	return int(number)


def blend(dst, src):
	sa = src[3] / 255.0
	da = dst[3] / 255.0
	oa = sa + da * (1 - sa)
	if oa == 0:
		return (0, 0, 0, 0)
	return tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3)) + (round(oa * 255),)


def rasterise(path):
	root = ET.parse(path).getroot()
	view = root.get('viewBox')
	if view is None:
		raise ValueError('svg needs a viewBox')
	vx, vy, vw, vh = (int(float(v)) for v in view.split())
	image = Image.new('RGBA', (vw, vh), (0, 0, 0, 0))
	pixels = image.load()

	def walk(element, inherited_fill, inherited_opacity):
		tag = element.tag.replace(SVG_NS, '')
		fill = element.get('fill', inherited_fill)
		opacity = inherited_opacity * float(element.get('opacity', '1'))
		if tag in ('svg', 'g'):
			for child in element:
				walk(child, fill, opacity)
			return
		if tag in ('title', 'desc', 'metadata'):
			return
		if tag != 'rect':
			raise ValueError(f'only <rect> is allowed, found <{tag}>')
		if fill is None or fill == 'none':
			return
		colour = parse_colour(fill, opacity * float(element.get('fill-opacity', '1')))
		x, y, w, h = whole(element, 'x') - vx, whole(element, 'y') - vy, whole(element, 'width'), whole(element, 'height')
		for px in range(max(0, x), min(vw, x + w)):
			for py in range(max(0, y), min(vh, y + h)):
				pixels[px, py] = blend(pixels[px, py], colour)

	walk(root, root.get('fill'), 1.0)
	return image


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument('svg_dir')
	parser.add_argument('out_dir')
	parser.add_argument('--preview')
	parser.add_argument('--scale', type=int, default=8)
	args = parser.parse_args()
	out = pathlib.Path(args.out_dir)
	out.mkdir(parents=True, exist_ok=True)
	previews = []
	failed = False
	for svg in sorted(pathlib.Path(args.svg_dir).glob('*.svg')):
		try:
			image = rasterise(svg)
		except Exception as error:
			print(f'ERROR {svg.name}: {error}', file=sys.stderr)
			failed = True
			continue
		image.save(out / (svg.stem + '.png'))
		print(f'{svg.name} -> {svg.stem}.png ({image.width}x{image.height})')
		previews.append((svg.stem, image))
	if args.preview and previews:
		# Every icon side by side, enlarged, on the panel colour the interface draws them on
		s = args.scale
		pad = 2 * s
		width = sum(img.width * s + pad for _, img in previews) + pad
		height = max(img.height for _, img in previews) * s + 2 * pad
		sheet = Image.new('RGBA', (width, height), (0x14, 0x18, 0x21, 0xFF))
		x = pad
		for _, img in previews:
			sheet.alpha_composite(img.resize((img.width * s, img.height * s), Image.NEAREST), (x, pad))
			x += img.width * s + pad
		pathlib.Path(args.preview).parent.mkdir(parents=True, exist_ok=True)
		sheet.save(args.preview)
		print(f'preview -> {args.preview}')
	sys.exit(1 if failed else 0)


if __name__ == '__main__':
	main()
