"""Build a Multisim 14 schematic by cloning real component prototypes from sample files."""
import copy, re, uuid, itertools
import xml.etree.ElementTree as ET
from msx import load, partname, comps, ports_of, s, unprotect

_ids = itertools.count(700000008, 8)
def nid(): return str(next(_ids))
def nguid(): return '{%s}' % str(uuid.uuid4()).upper()

def fnum(v):
    f = float(v); return str(int(f)) if f == int(f) else repr(f)

class Proto:
    def __init__(self, D, refdes):
        self.D = D
        self.comp = [c for c in comps(D) if c[0].get('LocalName') == '&ASC' + refdes][0]
        self.sym = D['byid'][self.comp[0].get('SymCompID')]
        self.ports = ports_of(D, self.comp)
        self.part = partname(self.comp)
        rd = s(D['root'].find('.//RefDesMgr'))
        m = re.search(r'<CIRToInfoMapItem CIRKey="&amp;ASC%s">.*?</CIRToInfoMapItem>' % re.escape(refdes), rd, re.S)
        self.refdes_xml = m.group(0)
        pm = s(D['root'].find('.//CCompInstancePropManager'))
        g = self.comp[0].get('InstanceID')
        m = re.search(r'<Item [^>]*CiCompGUID="&amp;ASC%s"[^>]*/>' % re.escape(g), pm)
        self.prop_xml = m.group(0)
        self.models = {}
        for e in [self.comp] + self.ports:
            mid = e[0].get('Model')
            if mid: self.models[mid] = D['byci'][mid]

class Inst:
    """A placed component."""
    def __init__(self, sym, comp, ports, proto, refdes):
        self.sym, self.comp, self.ports, self.proto, self.refdes = sym, comp, ports, proto, refdes
    def top(self): return self.sym[0]
    def main_pins(self):
        """PinName -> (pin connector element, absolute (x,y), CiPort item). Only pins directly in the main symbol."""
        out = {}
        top = self.top()
        tx, ty = float(top.get('Transformer-M20') or 0), float(top.get('Transformer-M21') or 0)
        for it in top.find('Objects'):
            ps = it[0]
            if ps.tag != 'CIITPinSymbolComp': continue
            px, py = tx + float(ps.get('Transformer-M20')), ty + float(ps.get('Transformer-M21'))
            pc = ps.find('Objects/Item/CIITPinConnectorComp')
            x = px + float(pc.get('Transformer-M20')) + float(pc.get('ptCenterX'))
            y = py + float(pc.get('Transformer-M21')) + float(pc.get('ptCenterY'))
            port = [p for p in self.ports if p.get('CiID') == ps.get('PortID')][0]
            out[ps.get('PinName').replace('&ASC', '')] = (pc, (x, y), port, pc.getparent() if hasattr(pc, 'getparent') else None)
        return out
    def shift(self, dx, dy):
        top = self.top()
        if top.get('Transformer-M20') is not None:
            top.set('Transformer-M20', fnum(float(top.get('Transformer-M20')) + dx))
            top.set('Transformer-M21', fnum(float(top.get('Transformer-M21')) + dy))
            return
        # animation symbol: positions baked into first-level children of main + every layer
        lists = [top.find('Objects')] + [l.find('Objects') for l in top.iter('CIITAnimationLayerComp')]
        for objs in lists:
            for it in objs:
                e = it[0]
                if e.get('Transformer-M20') is not None:
                    e.set('Transformer-M20', fnum(float(e.get('Transformer-M20')) + dx))
                    e.set('Transformer-M21', fnum(float(e.get('Transformer-M21')) + dy))
    def place(self, pin, x, y):
        _, (px, py), _, _ = self.main_pins()[pin]
        self.shift(x - px, y - py)

def instantiate(proto, refdes):
    sym = copy.deepcopy(proto.sym); comp = copy.deepcopy(proto.comp); ports = [copy.deepcopy(p) for p in proto.ports]
    idmap = {}
    for e in sym.iter():
        if e.tag == 'Item' and e.get('ID') and e.get('Class') != 'CODPinComp': idmap[e.get('ID')] = nid()
    for e in [comp] + ports: idmap[e.get('CiID')] = nid()
    for root in [sym, comp] + ports:
        for e in root.iter():
            for k, v in list(e.attrib.items()):
                if v in idmap and k not in ('Model',): e.set(k, idmap[v])
        for cl in root.iter('ConnectList'):
            for it in list(cl): cl.remove(it)
    for p in ports:
        nodes = p[0].find('Nodes')
        for it in list(nodes): nodes.remove(it)
    comp[0].set('LocalName', '&ASC' + refdes)
    comp[0].set('InstanceID', nguid())
    for e in sym.iter('CIITSymTextCompName'): e.set('Output', '&ASC' + refdes)
    return Inst(sym, comp, ports, proto, refdes)

def first(it, cls): return [i for i in it if i.get('Class') == cls][0]

class Builder:
    def __init__(self, base_path):
        self.B = B = load(base_path)
        d = B['diag']
        self.circ = first(B['el'], 'CiCircuit')
        self.cid = self.circ.get('CiID')
        # templates
        self.t_link = copy.deepcopy(first(B['objs'], 'CIITLinkComp'))
        self.t_junc = copy.deepcopy(first(B['objs'], 'CODPinComp'))
        self.t_ntext = copy.deepcopy(first(B['objs'], 'CODNodeTextComp'))
        self.t_ep = copy.deepcopy([i for i in B['refs'] if i.get('Class') == 'CODPinComp' and len(i)][0])
        self.t_node = copy.deepcopy(first(B['el'], 'CiNode'))
        r = B['root']
        self.netlabels = r.find('.//CDesignNetContainer/NetLabels/Item/Items')
        self.t_netlabel = copy.deepcopy(self.netlabels[0])
        rd = r.find('.//RefDesMgr')
        self.cpins = rd.find('ComponentPins/Item/Items'); self.t_cpin = copy.deepcopy(self.cpins[0])
        self.cirmap = rd.find('RefDesInfoContainers/RefDesInfoContainer/CIRToInfoMap')
        self.prefix_map = rd.find('RefDesPrefixUsageMap')
        self.propmgr = r.find('.//CCompInstancePropManager/RefDesGUIDsForSave')
        self.models = {first_model_name(m): m for m in B['el'] if m.get('Class') == 'CiModel'}
        # clear everything
        for cont in (B['objs'], B['refs'], self.netlabels, self.cpins, self.cirmap, self.propmgr):
            for it in list(cont): cont.remove(it)
        for it in list(B['el']):
            if it.get('Class') != 'CiCircuit': B['el'].remove(it)
        for tag in ('Models', 'Nodes', 'Components'):
            lst = self.circ[0].find(tag)
            for it in list(lst): lst.remove(it)
        self.insts = []; self.junctions = {}; self.used_models = {}; self.nets = []

    def model_id(self, proto, old):
        m = proto.models[old]; name = first_model_name(m)
        if name not in self.used_models:
            src = self.models.get(name)
            if src is None:
                src = copy.deepcopy(m); src.set('CiID', nid())
            src[0].set('Scope', self.cid)
            self.used_models[name] = src
        return self.used_models[name].get('CiID')

    def add(self, proto, refdes, pin, x, y, key=None):
        inst = instantiate(proto, refdes)
        for e in [inst.comp] + inst.ports:
            if e[0].get('Model'): e[0].set('Model', self.model_id(proto, e[0].get('Model')))
        inst.comp[0].set('Circuit', self.cid)
        if key:
            for e in inst.sym.iter('CIITTextCompAnimationLayerName'): e.set('Output', '&ASC' + key)
            for e in inst.sym.iter('CIITSymTextCompValue'):
                if 'Key' in (e.get('Output') or ''): e.set('Output', '&ASCKey = ' + key)
            for cs in inst.comp.iter('CiaCollString'):
                st = cs.find('strings')
                if len(st) == 1 and re.fullmatch(r'&ASC\w', st[0].get('Value') or ''): st[0].set('Value', '&ASC' + key)
        inst.place(pin, x, y)
        self.insts.append(inst)
        return inst

    def junction(self, name, x, y):
        j = copy.deepcopy(self.t_junc); j.set('ID', nid())
        j[0].set('Transformer-M20', str(x)); j[0].set('Transformer-M21', str(y))
        for it in list(j[0].find('ConnectList')): j[0].find('ConnectList').remove(it)
        self.junctions[name] = (j, (x, y))

    def net(self, name, segs):
        self.nets.append((name, segs))

    def _endpoint(self, end):
        """returns (target element, target ID, target class, xy, port or None)"""
        if end[0] == 'J':
            j, xy = self.junctions[end[1]]
            return j[0], j.get('ID'), 'CODPinComp', xy, None
        inst, pin = end[1], end[2]
        pc, xy, port, _ = inst.main_pins()[pin]
        item_id = [it for it in inst.sym.iter('Item') if len(it) and it[0] is pc][0].get('ID')
        return pc, item_id, 'CIITPinConnectorComp', xy, port

    def build(self, out_path):
        B = self.B; objs, refs, el = B['objs'], B['refs'], B['el']
        for inst in self.insts: objs.append(inst.sym)
        stub = lambda i, c: ET.fromstring('<Item ID="%s" Class="%s" />' % (i, c))
        node_of_port = {}
        connected_pcs = []
        for n, (name, segs) in enumerate(self.nets, start=1):
            node = copy.deepcopy(self.t_node); node.set('CiID', nid())
            node[0].set('LocalName', '&ASC%d' % n); node[0].set('Circuit', self.cid)
            plist = node[0].find('Ports')
            for it in list(plist): plist.remove(it)
            ntext = copy.deepcopy(self.t_ntext); ntext.set('ID', nid())
            links = ntext[0].find('Links')
            for it in list(links): links.remove(it)
            ports = []
            for si, (a, b, mids) in enumerate(segs):
                link = copy.deepcopy(self.t_link); link.set('ID', nid())
                eps = []
                for end in (a, b):
                    tel, tid, tcls, xy, port = self._endpoint(end)
                    ep = copy.deepcopy(self.t_ep); ep.set('ID', nid())
                    ep[0].set('CenterX', fnum(xy[0])); ep[0].set('CenterY', fnum(xy[1]))
                    cl = ep[0].find('ConnectList')
                    for it in list(cl): cl.remove(it)
                    cl.append(stub(tid, tcls))
                    tel.find('ConnectList').append(stub(ep.get('ID'), 'CODPinComp'))
                    if tcls == 'CIITPinConnectorComp' and tid not in connected_pcs: connected_pcs.append(tid)
                    if port is not None and port not in ports: ports.append(port)
                    eps.append((ep, xy))
                L = link[0]
                L.set('Connect1', eps[0][0].get('ID')); L.set('Connect2', eps[1][0].get('ID'))
                L.set('Node', node.get('CiID')); L.set('NodeText', ntext.get('ID'))
                pts = L.find('Points')
                for it in list(pts): pts.remove(it)
                for (x, y) in [eps[0][1]] + list(mids) + [eps[1][1]]:
                    pts.append(ET.fromstring('<Item X="%s" Y="%s" />' % (fnum(x), fnum(y))))
                g = nguid()
                L.find('ElectricalObject/ModifierInfo/Element/Item').set('Guid', g)
                nl = copy.deepcopy(self.t_netlabel); nl.set('ID', g); nl.set('LastNetName', '&ASC%d' % n)
                self.netlabels.append(nl)
                objs.append(link)
                refs.extend([eps[0][0], eps[1][0], stub(link.get('ID'), 'CIITLinkComp')])
                links.append(stub(link.get('ID'), 'CIITLinkComp'))
                if si == 0:
                    x0, y0 = eps[0][1]
                    ntext[0].set('Transformer-M20', fnum(x0 + 9)); ntext[0].set('Transformer-M21', fnum(y0 - 14))
            objs.append(ntext); refs.append(stub(ntext.get('ID'), 'CODNodeTextComp'))
            for p in ports:
                plist.append(ET.fromstring('<Item CiID="%s" />' % p.get('CiID')))
                p[0].find('Nodes').append(ET.fromstring('<Item CiID="%s" />' % node.get('CiID')))
                node_of_port[p.get('CiID')] = n
            el.append(node); self.circ[0].find('Nodes').append(ET.fromstring('<Item CiID="%s" />' % node.get('CiID')))
        for j, xy in self.junctions.values():
            objs.append(j); refs.append(stub(j.get('ID'), 'CODPinComp'))
        for pcid in connected_pcs: refs.append(stub(pcid, 'CIITPinConnectorComp'))
        # electrical elements: components + ports first, like Multisim does
        elems = []
        for inst in self.insts:
            elems.append(inst.comp); elems.extend(inst.ports)
            self.circ[0].find('Components').append(ET.fromstring('<Item CiID="%s" />' % inst.comp.get('CiID')))
        for i, e in enumerate(elems): el.insert(i, e)
        for name, m in self.used_models.items():
            if m not in list(el): el.append(m)
            self.circ[0].find('Models').append(ET.fromstring('<Item CiID="%s" />' % m.get('CiID')))
        # refdes registries
        unums = []
        for inst in self.insts:
            g = inst.comp[0].get('InstanceID')
            for pname, (pc, xy, port, _) in inst.main_pins().items():
                cp = copy.deepcopy(self.t_cpin); cp.set('GUID', g); cp.set('PinName', '&ASC' + pname)
                n = node_of_port.get(port.get('CiID')); cp.set('lastnet', '&ASC%d' % n if n else '')
                self.cpins.append(cp)
            old = inst.proto.comp[0].get('LocalName')[4:]; new = inst.refdes
            pre, num = re.match(r'([A-Za-z]+)(\d+)', new).groups()
            if pre == 'U': unums.append(num)
            x = inst.proto.refdes_xml
            x = x.replace('CIRKey="&amp;ASC%s"' % old, 'CIRKey="&amp;ASC%s"' % new)
            x = x.replace('!0!0!0%s!0' % old, '!0!0!0%s!0' % new)
            x = re.sub(r'RefDesStrSize="(\d+)"', lambda m: 'RefDesStrSize="%d"' % (int(m.group(1)) + len(new) - len(old)), x)
            x = re.sub(r'(Prefix="&amp;ASC)[A-Za-z]+(" Number=")\d+', r'\g<1>%s\g<2>%s' % (pre, num), x)
            x = re.sub(r'IRNumber="(\d+)"', 'IRNumber="%s"' % num, x)
            self.cirmap.append(ET.fromstring(x.replace('&amp;', '&amp;amp;') if False else x))
            p = ET.fromstring(inst.proto.prop_xml)
            p.set('CiCompGUID', '&ASC' + g); p.set('InstanceGUID', nguid()); p.set('AnimationState', '0')
            self.propmgr.append(p)
        for pu in self.prefix_map:
            if pu.get('Prefix') == '&ASCU':
                lst = pu.find('RefDesPrefixUsage')
                for it in list(lst): lst.remove(it)
                for n in ['0'] + unums: lst.append(ET.fromstring('<NumbersUsedVTwo NumberUsed="%s" />' % n))
        fix_tails(B['root'])
        xml = ET.tostring(B['root'], encoding='unicode')
        xml = '<?xml version="1.0" encoding="ASCII"?>\n' + xml.replace(' />', '/>') + '\n'
        xml = unprotect(re.sub(r'[^\x00-\x7f]', lambda m: '&#%d;' % ord(m.group(0)), xml))
        open(out_path, 'w', encoding='ascii', newline='').write(xml)
        return xml

def first_model_name(m): return m[0].get('LocalName')

def fix_tails(root):
    """every element on its own line, like Multisim writes"""
    for e in root.iter():
        if len(e) and not (e.text or '').strip(): e.text = '\n'
        for k in e:
            if not (k.tail or '').strip(): k.tail = '\n'

def finalize(xml, name, png=None):
    """neutralise sample-author file paths, fix RefDesStrSize, replace preview image"""
    for sep in ('\\', '\\\\'):
        old = sep.join(['Users', 'zisan', 'OneDrive', 'Documents', 'National Instruments', 'Circuit Design Suite 14.3', ''])
        xml = xml.replace(old, sep.join(['Users', 'Public', 'Documents', '']))
    xml = re.sub(r'Lab 4 problem 1\.ms14|Lab 2 --1\.ms14', name, xml)
    xml = xml.replace('User="&amp;ASCzisan"', 'User="&amp;ASCuser"').replace('OwnerName="&amp;ASCzisan"', 'OwnerName="&amp;ASCuser"')
    def fix(m):
        val = m.group(3)
        return '%s%d%s%s' % (m.group(1), len(val.replace('!0', '\0').replace('\\\\', '\\')) + 2, m.group(2), val)
    xml = re.sub(r'(RefDesStrSize=")\d+(" RefDesCount="\d+" RefDes="&amp;ASC)([^"]*)', fix, xml)
    if png is not None:
        xml = re.sub(r'(<PrjPreview_\w+ Format="PNG" Data=")[0-9A-F]*', lambda m: m.group(1) + png.hex().upper(), xml)
    assert 'zisan' not in xml
    return xml
