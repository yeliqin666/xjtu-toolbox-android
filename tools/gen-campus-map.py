# 校区简图数据生成：OpenStreetMap → shared/model/src/commonMain/kotlin/com/xjtu/toolbox/campusmap/CampusMapsGenerated.kt
# 先下载四个校区的 OSM 数据到 osm/（bbox 为 minLon,minLat,maxLon,maxLat）：
#   curl -o osm/xingqing.osm      "https://www.openstreetmap.org/api/0.6/map?bbox=108.976,34.240,108.994,34.254"
#   curl -o osm/yanta.osm         "https://www.openstreetmap.org/api/0.6/map?bbox=108.9280,34.2140,108.9370,34.2235"
#   curl -o osm/qujiang.osm       "https://www.openstreetmap.org/api/0.6/map?bbox=108.9925,34.2215,108.9995,34.2295"
#   curl -o osm/chuangxingang.osm "https://www.openstreetmap.org/api/0.6/map?bbox=108.6440,34.2500,108.6600,34.2650"
# 再运行：python gen_campus_map.py <输出 .kt 路径>
# 数据 © OpenStreetMap contributors，ODbL 1.0。
import sys, xml.etree.ElementTree as ET, io, math, re
CAMPUS = [  # (文件, 显示名, 边界：way id 或 rel id)
    ('xingqing', '兴庆校区', ('rel', '17568632')),
    ('yanta', '雁塔校区', ('rel', '17568634')),
    ('qujiang', '曲江校区', ('way', '379517666')),
    ('chuangxingang', '创新港校区', ('way', '492791467')),
]
ROAD = {'footway','path','service','residential','pedestrian','living_street','steps','unclassified','tertiary','cycleway','track'}

def enc_num(v):
    v = ~(v << 1) if v < 0 else (v << 1)
    s = ''
    while v >= 0x20:
        s += chr((0x20 | (v & 0x1f)) + 63); v >>= 5
    return s + chr(v + 63)
def encode(pts):
    out, pl, pn = '', 0, 0
    for la, lo in pts:
        a, b = round(la * 1e5), round(lo * 1e5)
        out += enc_num(a - pl) + enc_num(b - pn); pl, pn = a, b
    return out
def simplify(pts, eps):
    if len(pts) < 3: return pts
    (ax, ay), (bx, by) = pts[0], pts[-1]
    dmax, idx = 0, 0
    for i in range(1, len(pts) - 1):
        px, py = pts[i]
        dx, dy = bx - ax, by - ay
        d = abs(dy * px - dx * py + bx * ay - by * ax) / (math.hypot(dx, dy) or 1e-12) if (dx or dy) else math.hypot(px-ax, py-ay)
        if d > dmax: dmax, idx = d, i
    if dmax > eps:
        return simplify(pts[:idx+1], eps)[:-1] + simplify(pts[idx:], eps)
    return [pts[0], pts[-1]]
def inside(pt, rings):
    x, y = pt[1], pt[0]; c = False
    for ring in rings:
        for i in range(len(ring)):
            (y1, x1), (y2, x2) = ring[i], ring[i-1]
            if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c
def dist_m(a, b):
    return math.hypot((a[0]-b[0]) * 111320, (a[1]-b[1]) * 111320 * math.cos(math.radians(a[0])))
def near_ring(pt, rings, m):
    return any(dist_m(pt, p) < m for ring in rings for p in ring)

def kind_of(name, tags):
    a, b, l, s = tags.get('amenity',''), tags.get('building',''), tags.get('leisure',''), tags.get('shop','')
    if a == 'library' or '图书' in name: return 'LIBRARY'
    if a in ('restaurant','fast_food','food_court','canteen') or re.search(r'(苑|食堂|餐厅|面屋)$', name): return 'CANTEEN'
    if a == 'cafe' or s: return 'SHOP'
    if b in ('dormitory','apartments','residential') or re.search(r'(舍|公寓|宿舍)', name): return 'DORM'
    if re.search(r'门(（.*）)?$', name) and not b: return 'GATE'
    if l in ('pitch','sports_centre','stadium','track') or re.search(r'(操场|体育|球场|田径)', name): return 'SPORT'
    if b in ('university','school','college','yes') or re.search(r'(楼|馆|中心|学院|实验室|大厦|坊|部)', name): return 'BUILDING'
    if l in ('park','garden') or tags.get('landuse') in ('meadow','grass') or tags.get('tourism'): return 'LANDMARK'
    return 'OTHER'

SKIP = re.compile(r'(社区|家属院|宾馆|酒店|影城|MOMOPARK|阳阳|皇家|郝佳|罗家寨|附中|附小|附属中学|附属小学|幼儿园|派出所|消防|银行|一村|二村|三村|新家园|和园公寓|大钧院)')

def load(fname):
    r = ET.parse(fname).getroot()
    nodes = {n.get('id'): (float(n.get('lat')), float(n.get('lon'))) for n in r.iter('node')}
    ways = {w.get('id'): w for w in r.iter('way')}
    rels = {x.get('id'): x for x in r.iter('relation')}
    return r, nodes, ways, rels

def way_pts(w, nodes):
    return [nodes[nd.get('ref')] for nd in w.findall('nd') if nd.get('ref') in nodes]

def assemble(segs):
    rings = []
    segs = [s[:] for s in segs if s]
    while segs:
        cur = segs.pop(0)
        changed = True
        while changed and cur[0] != cur[-1]:
            changed = False
            for i, s in enumerate(segs):
                if s[0] == cur[-1]: cur += s[1:]; segs.pop(i); changed = True; break
                if s[-1] == cur[-1]: cur += s[::-1][1:]; segs.pop(i); changed = True; break
        rings.append(cur)
    return rings

out = []
for fname, title, (btype, bid) in CAMPUS:
    r, nodes, ways, rels = load(f'osm/{fname}.osm')
    if btype == 'way':
        rings = [way_pts(ways[bid], nodes)]
    else:
        rel = rels[bid]
        segs = [way_pts(ways[m.get('ref')], nodes) for m in rel.findall('member') if m.get('type') == 'way' and m.get('role') in ('outer', '') and m.get('ref') in ways]
        rings = assemble(segs)
    allpts = [p for ring in rings for p in ring]
    box = (min(p[0] for p in allpts), min(p[1] for p in allpts), max(p[0] for p in allpts), max(p[1] for p in allpts))
    pois, roads, seen = [], [], set()
    for el in list(r.iter('way')) + list(r.iter('node')) + list(r.iter('relation')):
        tags = {x.get('k'): x.get('v') for x in el.findall('tag')}
        if el.tag == 'relation':
            if tags.get('type') != 'multipolygon' or not any(k in tags for k in ('building', 'amenity', 'leisure')): continue
            segments = [way_pts(ways[m.get('ref')], nodes) for m in el.findall('member')
                        if m.get('type') == 'way' and m.get('role') in ('outer', '') and m.get('ref') in ways]
            outlines = assemble(segments)
            if not outlines: continue
            pts = max(outlines, key=len)
            c = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
        elif el.tag == 'way':
            pts = way_pts(el, nodes)
            if not pts: continue
            hw = tags.get('highway')
            if hw in ROAD:
                if sum(inside(p, rings) for p in pts) * 2 >= len(pts):
                    roads.append(encode(simplify(pts, 0.00003)))
                continue
            c = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
        else:
            pts, c = None, nodes[el.get('id')]
        name = tags.get('name:zh') or tags.get('name') or tags.get('official_name')
        if not name and tags.get('building'):
            name = tags.get('building:ref') or tags.get('ref')
        if not name or 'highway' in tags or 'public_transport' in tags or SKIP.search(name): continue
        kind = kind_of(name, tags)
        ok = inside(c, rings) or (kind == 'GATE' and near_ring(c, rings, 40))
        if not ok or kind == 'OTHER' and el.tag == 'node': continue
        if el.get('id') == bid: continue
        key = (name, kind)
        if key in seen: continue
        seen.add(key)
        outline = encode(simplify(pts, 0.00001)) if pts and len(pts) >= 4 and kind not in ('GATE',) else ''
        short = re.sub(r'^(西安交通大学|交大)(\(.*?\)|（.*?）)?(雁塔校区|曲江校区|创新港校区)?(医学校区)?[-－\s]?', '', name)
        short = {'医院': '校医院', '': name}.get(short, short)
        # 同类、30 米内、名字互相包含的（「康桥苑」与「康桥苑餐厅」）只留短的那个
        dup = next((i for i, p in enumerate(pois) if p[1] == kind and dist_m(p[2], c) < 30 and (p[0] in short or short in p[0])), None)
        if dup is not None:
            if len(short) < len(pois[dup][0]): pois[dup] = (short, kind, pois[dup][2], pois[dup][3] or outline)
            continue
        pois.append((short, kind, c, outline))
    boundary = [encode(simplify(ring, 0.00002)) for ring in rings]
    out.append((title, box, boundary, pois, roads))
    print(title, len(pois), 'pois', len(roads), 'roads', file=sys.stderr)

def kstr(s): return '"' + s.replace(chr(92), chr(92) * 2).replace('"', chr(92) + '"').replace('$', chr(92) + '$') + '"'
lines = ['package com.xjtu.toolbox.campusmap', '',
 '// 由 OpenStreetMap 数据生成（© OpenStreetMap contributors，ODbL 1.0），不要手改；课表楼名的别名见 CampusMap.kt。', '',
 'internal val GENERATED_CAMPUS_MAPS: List<CampusMapData> = listOf(']
for title, box, boundary, pois, roads in out:
    lines.append(f'    CampusMapData(')
    lines.append(f'        campus = {kstr(title)},')
    lines.append(f'        minLat = {box[0]:.5f}, minLon = {box[1]:.5f}, maxLat = {box[2]:.5f}, maxLon = {box[3]:.5f},')
    lines.append(f'        boundary = listOf(' + ', '.join(kstr(b) for b in boundary) + '),')
    lines.append(f'        places = listOf(')
    for name, kind, c, outline in pois:
        lines.append(f'            CampusPlace({kstr(name)}, PlaceKind.{kind}, {c[0]:.5f}, {c[1]:.5f}, {kstr(outline)}),')
    lines.append('        ),')
    lines.append('        paths = listOf(')
    for i in range(0, len(roads), 4):
        lines.append('            ' + ' '.join(kstr(x) + ',' for x in roads[i:i+4]))
    lines.append('        ),')
    lines.append('    ),')
lines.append(')')
io.open(sys.argv[1], 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
