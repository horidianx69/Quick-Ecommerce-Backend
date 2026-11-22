"""Cut QuikEFinal.jpeg (horizontal logo + app icon, on white) into web assets in public/brand/.
usage (needs Pillow):  python scripts/cut-logo.py ../QuikEFinal.jpeg public/brand
Crop boxes are measured for that image; a redesigned logo needs new boxes."""
import sys, pathlib
from PIL import Image, ImageDraw

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
img = Image.open(src).convert('RGB')

LOCKUP = (37, 33, 889, 432)       # "QuikE" + bolt + tagline
TAGLINE = (332, 352, 890, 402)    # "Need It? QuikE It." strip, blanked for the header version
ICON = (938, 98, 1244, 399)       # red rounded-square app icon
ICON_RADIUS = 58 / 306            # corner radius as a share of the icon width


def unmatte(im):
    """White background -> transparent, keeping soft edges (colour-to-alpha against white)."""
    px = im.load()
    res = Image.new('RGBA', im.size)
    rp = res.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b = px[x, y]
            a = max(255 - r, 255 - g, 255 - b)
            if a < 10:
                rp[x, y] = (0, 0, 0, 0)
                continue
            f = a / 255
            un = lambda c: max(0, min(255, round((c - 255 * (1 - f)) / f)))
            rp[x, y] = (un(r), un(g), un(b), a)
    return res


def trimmed(im, pad=6):
    l, t, r, b = im.getbbox()
    return im.crop((max(0, l - pad), max(0, t - pad), min(im.width, r + pad), min(im.height, b + pad)))


def save(im, name, width=None, height=None):
    if width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    if height:
        im = im.resize((round(im.width * height / im.height), height), Image.LANCZOS)
    im.save(out / name, optimize=True)
    print(f'{name}: {im.size} {(out / name).stat().st_size // 1024} KB')


def rounded_icon(size):
    """The icon square with its own rounded corners and a transparent outside (no drop shadow)."""
    sq = img.crop(ICON).resize((size, size), Image.LANCZOS).convert('RGBA')
    big = size * 4  # draw the mask large, then shrink it, for smooth corners
    mask = Image.new('L', (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=round(big * ICON_RADIUS), fill=255)
    sq.putalpha(mask.resize((size, size), Image.LANCZOS))
    return sq


full = trimmed(unmatte(img.crop(LOCKUP)))
no_tag = img.copy()
no_tag.paste((255, 255, 255), TAGLINE)
header = trimmed(unmatte(no_tag.crop(LOCKUP)))

save(full, 'quike-logo.png', width=860)
save(header, 'quike-logo-header.png', height=120)
save(rounded_icon(256), 'quike-icon.png')
save(rounded_icon(64), 'favicon-64.png')
apple = Image.new('RGBA', (180, 180), (255, 255, 255, 255))
apple.alpha_composite(rounded_icon(180))
save(apple.convert('RGB'), 'apple-touch-icon.png')
