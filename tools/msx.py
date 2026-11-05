import xml.etree.ElementTree as ET, re
def protect(txt):
    return re.sub(r'="([^"]*)"', lambda m: '="'+m.group(1).replace('\r','&#13;').replace('\n','&#10;').replace('\t','&#9;')+'"', txt)
def unprotect(txt):
    return txt.replace('&#13;','\r').replace('&#10;','\n').replace('&#09;','\t').replace('&#9;','\t')
def load(path):
    txt=open(path,encoding='latin1',newline='').read()
    r=ET.fromstring(protect(txt).encode('latin1')); d=r.find('.//CIITDiagram')
    D=dict(root=r, diag=d, objs=d.find('Components/CODComposite/Objects'),
           refs=d.find('Components/CODComposite/ReferencedComponents'), el=d.find('Elements'))
    D['byid']={i.get('ID'):i for i in list(D['objs'])+list(D['refs'])}
    D['byci']={i.get('CiID'):i for i in D['el']}
    return D
def partname(item):
    cs=item.find('./*/Attributes/Item/CiaCollString/strings')
    return cs[1].get('Value').replace('&ASC','') if cs is not None and len(cs)>1 else None
def comps(D):
    return [i for i in D['el'] if i.get('Class')=='CiComponent']
def ports_of(D,comp):
    return [p for p in D['el'] if p.get('Class')=='CiPort' and p[0].get('Component')==comp.get('CiID')]
s=lambda e: ET.tostring(e).decode()
