#!/usr/bin/env python3
"""Recover the ten editable SRS drawings as native, uncompressed draw.io models.

Uses only Python's standard library. No image tracing or inferred model content.
"""
import argparse
import copy
import hashlib
import html
import json
import re
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

NS = {
    'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
    'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
    'wpg': 'http://schemas.microsoft.com/office/word/2010/wordprocessingGroup',
    'wps': 'http://schemas.microsoft.com/office/word/2010/wordprocessingShape',
    'mc': 'http://schemas.openxmlformats.org/markup-compatibility/2006',
}
SLUGS = [
    'system-context', 'core-object-relationships',
    'workspace-resource-use-cases', 'intake-personal-planning-use-cases',
    'project-collaboration-use-cases', 'information-retrieval-confirmation',
    'scheduling-execution-feedback', 'resource-management-use',
    'shared-project-personal-execution', 'assisted-extraction-human-confirmation',
]
SECTIONS = ['2.5', '5.1', '5.8', '5.8', '5.8',
            '5.11.1', '5.11.2', '5.11.3', '5.11.4', '5.11.5']
EMU_PER_PX = 9525


def attr(node, name, default=None):
    return default if node is None else node.get('{' + NS['w'] + '}' + name, default)


def num(value):
    return format(round(value, 5), 'g')


def colour(node, path, fallback='none'):
    c = node.find(path + '/a:solidFill/a:srgbClr', NS)
    return '#' + c.get('val') if c is not None else fallback


def label(shape):
    """Preserve explicit Word line breaks, paragraph alignment and run emphasis."""
    box = shape.find('wps:txbx/w:txbxContent', NS)
    if box is None:
        return '', '', 13.33333, 'center'
    name = shape.find('wps:cNvPr', NS)
    description = name.get('descr', '') if name is not None else ''
    match = re.search(r'source size: ([\d.]+) pt', description)
    default_pt = float(match.group(1)) if match else 10
    paragraphs, plain, sizes = [], [], []
    align = 'center'
    for p in box.findall('w:p', NS):
        jc = attr(p.find('w:pPr/w:jc', NS), 'val', 'center')
        align = {'both': 'justify'}.get(jc, jc)
        spacing = p.find('w:pPr/w:spacing', NS)
        line_px = float(attr(spacing, 'line', str(default_pt * 28))) / 20 * 4 / 3
        parts, txt = [], []
        for run in p.findall('w:r', NS):
            rp = run.find('w:rPr', NS)
            size = None if rp is None else rp.find('w:sz', NS)
            if size is None and rp is not None:
                size = rp.find('w:szCs', NS)
            px = float(attr(size, 'val', str(default_pt * 2))) / 2 * 4 / 3
            sizes.append(px)
            style = f'font-size:{num(px)}px;'
            if rp is not None:
                for tag, css in [('b', 'font-weight:bold;'), ('i', 'font-style:italic;')]:
                    e = rp.find('w:' + tag, NS)
                    if e is not None and attr(e, 'val', '1') not in ['0', 'false']:
                        style += css
                c = rp.find('w:color', NS)
                if c is not None and attr(c, 'val') not in [None, 'auto']:
                    style += 'color:#' + attr(c, 'val') + ';'
            text = []
            for item in run:
                if item.tag == '{' + NS['w'] + '}t':
                    text.append(html.escape(item.text or ''))
                    txt.append(item.text or '')
                elif item.tag in ['{' + NS['w'] + '}br', '{' + NS['w'] + '}cr']:
                    text.append('<br>')
                    txt.append('\n')
            if text:
                parts.append('<span style="' + style + '">' + ''.join(text) + '</span>')
        paragraphs.append(f'<div style="line-height:{num(line_px)}px;white-space:nowrap;text-align:{align};">' + ''.join(parts) + '</div>')
        plain.append(''.join(txt))
    return ''.join(paragraphs), '\n'.join(plain), max(sizes or [default_pt * 4 / 3]), align


def shape_record(shape, origin, index, figure):
    pr = shape.find('wps:spPr', NS)
    xf = pr.find('a:xfrm', NS)
    if xf is None or xf.get('rot', '0') != '0':
        raise ValueError('Unsupported missing or rotated transform')
    off, ext = xf.find('a:off', NS), xf.find('a:ext', NS)
    x = (int(off.get('x')) - origin[0]) / EMU_PER_PX + 32
    y = (int(off.get('y')) - origin[1]) / EMU_PER_PX + 32
    w, h = int(ext.get('cx')) / EMU_PER_PX, int(ext.get('cy')) / EMU_PER_PX
    geom = pr.find('a:prstGeom', NS)
    kind = geom.get('prst') if geom is not None else 'triangle'
    value, text, font_size, align = label(shape)
    info = shape.find('wps:cNvPr', NS)
    rec = dict(id=f'f{figure:02}-s{index:03}', source_word_id=info.get('id'),
               source_name=info.get('name'), kind=kind,
               geometry=dict(x=x, y=y, width=w, height=h),
               text=text, html=value, font_size=font_size, align=align,
               fill=colour(pr, '.', 'none'), stroke=colour(pr, 'a:ln', 'none'),
               stroke_width=float(pr.find('a:ln', NS).get('w', '0')) / EMU_PER_PX
               if pr.find('a:ln', NS) is not None else 0,
               flip_h=xf.get('flipH') == '1', flip_v=xf.get('flipV') == '1')
    if geom is None:
        paths = pr.findall('a:custGeom/a:pathLst/a:path', NS)
        if len(paths) != 1:
            raise ValueError('Unsupported custom geometry')
        path = paths[0]
        pts = [(int(pt.get('x')) / int(path.get('w')), int(pt.get('y')) / int(path.get('h')))
               for pt in path.findall('.//a:pt', NS)]
        expected = [[(1, .5), (0, 1), (0, 0)], [(.5, 1), (0, 0), (1, 0)]]
        rounded = [(round(a, 3), round(b, 3)) for a, b in pts]
        if rounded not in expected:
            raise ValueError('Unexpected polygon: ' + str(rounded))
        rec['direction'] = 'east' if rounded == expected[0] else 'south'
    if kind not in ['rect', 'ellipse', 'line', 'triangle']:
        raise ValueError('Unsupported preset: ' + kind)
    return rec


def model(records):
    width = max(r['geometry']['x'] + r['geometry']['width'] for r in records) + 32
    height = max(r['geometry']['y'] + r['geometry']['height'] for r in records) + 32
    graph = ET.Element('mxGraphModel', dx='1000', dy='800', grid='0', gridSize='10',
                       guides='1', tooltips='1', connect='1', arrows='1', fold='1',
                       page='1', pageScale='1', pageWidth=num(width), pageHeight=num(height),
                       math='0', shadow='0', background='#FFFFFF')
    root = ET.SubElement(graph, 'root')
    ET.SubElement(root, 'mxCell', id='0')
    ET.SubElement(root, 'mxCell', id='1', parent='0')
    for rec in records:
        g = rec['geometry']
        style = (f"html=1;whiteSpace=nowrap;overflow=visible;align={rec['align']};"
                 f"verticalAlign=middle;fontFamily=Arial;fontSize={num(rec['font_size'])};"
                 f"fontColor=#000000;spacing=0;rounded=0;shadow=0;"
                 f"fillColor={rec['fill']};strokeColor={rec['stroke']};"
                 f"strokeWidth={num(rec['stroke_width'])};")
        attrs = dict(id=rec['id'], value=rec['html'], parent='1',
                     sourceName=rec['source_name'], sourceWordId=rec['source_word_id'])
        if rec['kind'] == 'line':
            attrs['edge'] = '1'
            style += 'endArrow=none;startArrow=none;noEdgeStyle=1;'
            attrs['style'] = style
            cell = ET.SubElement(root, 'mxCell', **attrs)
            geo = ET.SubElement(cell, 'mxGeometry', relative='1', **{'as': 'geometry'})
            sx = g['x'] + (g['width'] if rec['flip_h'] else 0)
            sy = g['y'] + (g['height'] if rec['flip_v'] else 0)
            ex = g['x'] + (0 if rec['flip_h'] else g['width'])
            ey = g['y'] + (0 if rec['flip_v'] else g['height'])
            ET.SubElement(geo, 'mxPoint', x=num(sx), y=num(sy), **{'as': 'sourcePoint'})
            ET.SubElement(geo, 'mxPoint', x=num(ex), y=num(ey), **{'as': 'targetPoint'})
        else:
            attrs['vertex'] = '1'
            style += 'shape=' + rec['kind'] + ';'
            if rec['kind'] == 'triangle':
                style += 'direction=' + rec['direction'] + ';'
            if rec['flip_h']:
                style += 'flipH=1;'
            if rec['flip_v']:
                style += 'flipV=1;'
            attrs['style'] = style
            cell = ET.SubElement(root, 'mxCell', **attrs)
            ET.SubElement(cell, 'mxGeometry', x=num(g['x']), y=num(g['y']),
                          width=num(g['width']), height=num(g['height']), **{'as': 'geometry'})
    return graph


def write_xml(path, root):
    ET.indent(root, space='  ')
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def recover(source, out):
    out.mkdir(parents=True, exist_ok=True)
    individual = out / 'individual'
    individual.mkdir(exist_ok=True)
    with ZipFile(source) as z:
        document = ET.fromstring(z.read('word/document.xml'))
    body = list(document.find('w:body', NS))
    drawings = []
    for i, paragraph in enumerate(body):
        group = paragraph.find('.//mc:Choice//wpg:wgp', NS)
        if group is None:
            continue
        caption = ''.join(n.text or '' for n in body[i + 1].iter('{' + NS['w'] + '}t'))
        match = re.match(r'Figure\s+(\d+)\s+(.+)', caption)
        if match is None:
            raise ValueError('Drawing has no adjacent figure caption')
        figure, title = int(match.group(1)), match.group(2)
        xf = group.find('wpg:grpSpPr/a:xfrm/a:chOff', NS)
        origin = int(xf.get('x')), int(xf.get('y'))
        records = [shape_record(s, origin, j, figure)
                   for j, s in enumerate(group.findall('wps:wsp', NS), 1)]
        note = ''
        if i + 2 < len(body):
            following = body[i + 2]
            if following.find('.//mc:AlternateContent', NS) is None:
                note = ''.join(n.text or '' for n in following.iter('{' + NS['w'] + '}t'))
                if re.match(r'^\d+(?:\.|\s)', note):
                    note = ''
        if figure == 10:
            note = ''.join(n.text or '' for n in body[i - 1].iter('{' + NS['w'] + '}t'))
        drawings.append(dict(figure=figure, title=title, section=SECTIONS[figure-1],
                             file=f'{figure:02}-{SLUGS[figure-1]}.drawio',
                             source_note=note, elements=records))
    if [d['figure'] for d in drawings] != list(range(1, 11)):
        raise ValueError('Expected exactly Figures 1–10 in source order')
    combined = ET.Element('mxfile', host='app.diagrams.net', type='device',
                          compressed='false', pages='10')
    for d in drawings:
        diagram = ET.SubElement(combined, 'diagram', id=f'figure-{d["figure"]:02}',
                                name=f'{d["figure"]:02} {SLUGS[d["figure"]-1]}')
        diagram.append(model(d['elements']))
        single = ET.Element('mxfile', host='app.diagrams.net', type='device',
                            compressed='false', pages='1')
        single.append(copy.deepcopy(diagram))
        write_xml(individual / d['file'], single)
    write_xml(out / 'KaiXu_SRS_Models.drawio', combined)
    manifest = dict(source_file=source.name,
                    source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                    extraction='DrawingML Choice only; VML fallback excluded to avoid duplicates',
                    coordinate_unit='CSS pixels at 96 dpi; 32 px page padding',
                    font_substitution='Arial for portable English diagram labels',
                    element_count=sum(len(d['elements']) for d in drawings),
                    diagrams=drawings)
    (out / 'recovery-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    notes = ['# SRS 图注与流程说明', '',
             '以下英文图名与图后说明直接提取自 SRS。流程图沿用原图的正常流程；异常与取消分支仍以原文说明为准。', '']
    for d in drawings:
        notes += [f'## Figure {d["figure"]} {d["title"]}', '',
                  f'SRS section {d["section"]}; file `individual/{d["file"]}`.', '']
        if d['source_note']:
            notes += [d['source_note'], '']
    (out / 'MODEL_NOTES.md').write_text('\n'.join(notes) + '\n')
    print(f'Recovered {len(drawings)} diagrams, {manifest["element_count"]} editable elements into {out}')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path, help='Original SRS .docx')
    p.add_argument('--out', type=Path, default=Path(__file__).resolve().parents[1])
    args = p.parse_args()
    recover(args.source.resolve(), args.out.resolve())
