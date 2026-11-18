#!/usr/bin/env python3
"""Converts the sprite packs of spandan-kumar/glyph (MIT) to DotForge animation JSON for the 25x25 round matrix.

Usage: convert_sprites.py <glyph-repo>/assets/catalog/sprites <out-dir> [excluded.log]

Rules
- classic_cartoons is excluded, as is every sprite whose source is limited to one jurisdiction (not "worldwide").
- Only original art (no source block), CC0-1.0 art and public-domain redrawings are kept.
- Art is centred, scaled by nearest neighbour as large as possible so that no opaque pixel of any frame lands outside
  the round mask, and colour becomes brightness (sRGB luma, 0 = off).
- Frame timing and play order (seq) are kept. 8 px tall banners scroll across the matrix.
Output: one gzip JSON per category (id -> {frames:[{duration, values[489]}]}) plus sprites.index.json.
"""
import glob, gzip, json, os, sys

SIZE, R2 = 25, 153
CELLS = [(x, y) for y in range(SIZE) for x in range(SIZE) if (x - 12) ** 2 + (y - 12) ** 2 <= R2]
MASK = {c for c in CELLS}

PACK_CATEGORY = {
    'faces': 'love_emoji', 'love': 'love_emoji', 'emotion_bubbles': 'love_emoji',
    'weather': 'nature_weather', 'nature': 'nature_weather', 'artist_fire': 'nature_weather',
    'animals': 'animals_food', 'food': 'animals_food', 'artist_animals': 'animals_food',
    'gaming': 'games', 'symbols': 'symbols', 'diwali': 'seasonal', 'halloween': 'seasonal',
    'storybook': 'stories_art', 'masterpieces': 'stories_art', 'legends': 'stories_art',
}
MINI = {'Love': 'love_emoji', 'Emoji': 'love_emoji', 'Space': 'games', 'Weather': 'nature_weather',
        'Fire & Energy': 'nature_weather', 'Holidays': 'seasonal', 'Gaming': 'games'}
SEASONAL_BANNERS = {'happy-new-year', 'merry-christmas', 'happy-diwali', 'eid-mubarak', 'happy-halloween',
                    'happy-easter', 'happy-holi', 'happy-hanukkah', 'happy-lunar-new-year', 'happy-birthday'}
MIT = 'Spandan Kumar, Glyph (MIT)'


def luma(rgb, gamma=0.85):
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    v = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
    return v ** gamma


def ink_value(spec):
    if spec.startswith('#'):
        return luma(int(spec[1:], 16))
    if spec.startswith('t:#'):
        return luma(int(spec[3:], 16))
    body = spec[2:].rstrip('!')
    if spec.endswith('!'):
        return 1.0
    return min(1.0, float(body.split('*')[0]))


def build_frame(spec, built, parts):
    if isinstance(spec, list):
        return list(spec)
    base = spec['base']
    rows = list(built[base]) if isinstance(base, int) else list(parts[base])
    for p in spec.get('patch', []):
        px, py = p['at']
        for dy, prow in enumerate(p['rows']):
            y = py + dy
            if y < 0 or y >= len(rows):
                continue
            chars = list(rows[y])
            for dx, ch in enumerate(prow):
                x = px + dx
                if ch in ' .' or x < 0 or x >= len(chars):
                    continue
                chars[x] = '.' if ch == '_' else ch
            rows[y] = ''.join(chars)
    if 'shift' in spec:
        sx, sy = spec['shift']
        w, h = len(rows[0]), len(rows)
        rows = [''.join(rows[y - sy][x - sx] if 0 <= x - sx < w and 0 <= y - sy < h else '.' for x in range(w)) for y in range(h)]
    if spec.get('flip') == 'h':
        rows = [r[::-1] for r in rows]
    elif spec.get('flip') == 'v':
        rows = rows[::-1]
    return rows


def decode(sprite, pack):
    colors = dict(pack.get('colors', {}))
    colors.update(sprite.get('colors', {}))
    parts = pack.get('parts', {})
    built = []
    for spec in sprite['frames']:
        built.append(build_frame(spec, built, parts))
    cache = {}

    def val(ch):
        if ch in '. _':
            return -1
        if ch not in cache:
            cache[ch] = ink_value(colors[ch])
        return cache[ch]

    grids = [[[val(ch) for ch in row] for row in rows] for rows in built]
    seq = sprite.get('seq') or list(range(len(grids)))
    ms = sprite.get('ms', 200)
    ms = ms if isinstance(ms, list) else [ms] * len(seq)
    assert len(ms) == len(seq), sprite['id']
    return grids, seq, [min(max(int(m), 20), 10000) for m in ms]


def candidates():
    big = [k / 2 for k in range(12, 1, -1)]          # 6 .. 1
    small = [1 - i / 16 for i in range(1, 13)]       # 0.94 .. 0.25
    return big + small


def best_scale(grids, seq):
    used = [grids[i] for i in set(seq)]
    h, w = len(used[0]), len(used[0][0])
    opaque = {(x, y) for g in used for y in range(h) for x in range(w) if g[y][x] >= 0}
    if not opaque:
        return 1.0, 0.0, 0.0
    x0 = min(x for x, _ in opaque); x1 = max(x for x, _ in opaque)
    y0 = min(y for _, y in opaque); y1 = max(y for _, y in opaque)
    bcx, bcy = (x0 + x1 + 1) / 2, (y0 + y1 + 1) / 2
    for s in candidates():
        ok = True
        for oy in range(SIZE):
            for ox in range(SIZE):
                sx = int((bcx + (ox + 0.5 - 12.5) / s) // 1)
                sy = int((bcy + (oy + 0.5 - 12.5) / s) // 1)
                if (sx, sy) in opaque and (ox, oy) not in MASK:
                    ok = False; break
            if not ok: break
        if ok:
            return s, bcx, bcy
    return 0.25, bcx, bcy


def render(grid, s, bcx, bcy):
    h, w = len(grid), len(grid[0])
    out = []
    for (ox, oy) in CELLS:
        sx = int((bcx + (ox + 0.5 - 12.5) / s) // 1)
        sy = int((bcy + (oy + 0.5 - 12.5) / s) // 1)
        v = grid[sy][sx] if 0 <= sx < w and 0 <= sy < h else -1
        out.append(0 if v < 0 else max(0, min(255, round(v * 255))))
    return out


def render_banner(grid, ms_each=70):
    h, w = len(grid), len(grid[0])
    s = 2
    top = int(12.5 - h * s / 2)
    frames = []
    total = w * s + SIZE
    for shift in range(0, total + 1, 2):
        vals = []
        for (ox, oy) in CELLS:
            sx = (ox + shift - SIZE) // s
            sy = (oy - top) // s
            v = grid[sy][sx] if 0 <= sx < w and 0 <= sy < h else -1
            vals.append(0 if v < 0 else max(0, min(255, round(v * 255))))
        frames.append({'duration': ms_each, 'values': vals})
    return frames


def credit_for(sprite):
    src = sprite.get('source')
    if not src:
        return MIT
    lic = src.get('license')
    if lic == 'CC0-1.0':
        return f"{src['notice'].replace(' Adapted for Glyph.', '')} Via {MIT}"
    return f"{src['notice']} Redrawn in Glyph by Spandan Kumar (MIT)"


def main():
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    log_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(out_dir, 'excluded.log')
    os.makedirs(out_dir, exist_ok=True)
    cats, index, credits, excluded = {}, [], [], []
    for path in sorted(glob.glob(os.path.join(src_dir, '*.json'))):
        name = os.path.basename(path)[:-5]
        pack = json.load(open(path))
        if name == 'classic_cartoons':
            excluded.append(f'{name}: whole pack excluded (1908-1930 film characters; trademark and non-US copyright risk)')
            continue
        for sp in pack['sprites']:
            src = sp.get('source')
            if src and src.get('license') not in (None, 'CC0-1.0') and src.get('jurisdiction') != 'worldwide':
                excluded.append(f"{name}/{sp['id']}: public domain only in {src.get('jurisdiction')}")
                continue
            if src and not src.get('license') and src.get('jurisdiction') != 'worldwide':
                excluded.append(f"{name}/{sp['id']}: public domain only in {src.get('jurisdiction')}")
                continue
            if src and src.get('license') not in (None, 'CC0-1.0'):
                excluded.append(f"{name}/{sp['id']}: licence {src.get('license')} is not CC0 or MIT")
                continue
            grids, seq, ms = decode(sp, pack)
            banner = sp.get('motion') == 'scroll' and len(grids[0]) <= 8 and len(grids[0][0]) > 25
            if name == 'mini':
                cat = MINI.get(sp.get('category'), 'symbols')
            elif name == 'holidays':
                cat = 'seasonal' if (banner is False or sp['id'] in SEASONAL_BANNERS) else 'text'
            else:
                cat = PACK_CATEGORY[name]
            if banner:
                frames = render_banner(grids[0]); n = 0
            else:
                s, bcx, bcy = best_scale(grids, seq)
                rendered = {i: render(grids[i], s, bcx, bcy) for i in set(seq)}
                frames = [{'duration': d, 'values': rendered[i]} for i, d in zip(seq, ms)]
                n = len(seq)
            cats.setdefault(cat, {})[sp['id']] = {'frames': frames}
            cr = credit_for(sp)
            if cr not in credits: credits.append(cr)
            tags = list(sp.get('tags', []))
            if banner and 'scroll' not in tags: tags.append('scroll')
            index.append({'id': 'sprite-' + sp['id'], 'src': sp['id'], 'name': sp.get('title', sp['id']), 'cat': cat,
                          'tags': tags, 'file': f'sprites_{cat}.json.gz', 'credit': credits.index(cr), 'n': n})
    for cat, items in cats.items():
        data = json.dumps({'version': 1, 'items': items}, separators=(',', ':')).encode()
        with open(os.path.join(out_dir, f'sprites_{cat}.json.gz'), 'wb') as f:
            f.write(gzip.compress(data, 9, mtime=0))
    with open(os.path.join(out_dir, 'sprites.index.json'), 'w') as f:
        json.dump({'version': 1, 'credits': credits, 'items': index}, f, separators=(',', ':'), ensure_ascii=False)
    with open(log_path, 'w') as f:
        f.write('\n'.join(excluded) + '\n')
    print(len(index), 'sprites,', len(excluded), 'excluded')


if __name__ == '__main__':
    main()
