#!/usr/bin/env python3
# Run: python3 tools/contrast_check.py  — WCAG contrast of every text colour over its worst-case background.
# WCAG contrast audit of Tunio's text colors over their worst-case backgrounds (sRGB alpha compositing, like Android).
def hx(h): h=h.lstrip('#'); return tuple(int(h[i:i+2],16) for i in (0,2,4))
def over(fg, a, bg): return tuple(round(f*a + b*(1-a)) for f,b in zip(fg,bg))
def lum(c):
    def ch(v):
        v/=255; return v/12.92 if v<=0.03928 else ((v+0.055)/1.055)**2.4
    r,g,b=map(ch,c); return 0.2126*r+0.7152*g+0.0722*b
def ratio(a,b):
    la,lb=sorted([lum(a),lum(b)],reverse=True); return (la+0.05)/(lb+0.05)

WHITE=(255,255,255); BLACK=(0,0,0)
Bg=hx('0E0E12'); MenuBg=hx('1E1C28'); Accent=hx('8B5CF6'); Accent2=hx('EC4899'); Blue=hx('3B82F6')
TextPrimary=hx('F7F7FA'); Muted=hx('C8C8D2'); AccentLight=hx('C9B6FF'); PinkLight=hx('FFA8D8'); Good=hx('6EE7B7'); Err=hx('FECACA')

aurora_peak = over(Accent,0.30,Bg)                    # brightest point of the background light
aurora_pink = over(Accent2,0.20,Bg)
vib=lambda c: tuple(min(255,round(v*1.15)) for v in c)
def glass(base, strong=False): return over(WHITE, 0.10 if strong else 0.06, over(Bg,0.40, vib(base)))
chrome = over(WHITE,0.05, over(Bg,0.50, vib(aurora_peak)))  # tab bar / mini player over brightest background
chrome_fallback = over(WHITE,0.06, hx('191822'))
# Now Playing: worst case = pure white cover, blurred, alpha .6 over aurora, then the black wash (top/mid/bottom).
art = over(WHITE,0.60,aurora_peak)
np_top, np_mid, np_bot = over(BLACK,0.62,art), over(BLACK,0.74,art), over(BLACK,0.86,art)
lens = over(WHITE,0.10, chrome)

backgrounds = {
 'Bg':Bg,'aurora peak':aurora_peak,'aurora pink':aurora_pink,'glass on aurora':glass(aurora_peak),
 'strong glass on aurora':glass(aurora_peak,True),'menu/dialog/snackbar':MenuBg,'glass bar (blur)':chrome,
 'glass bar (Android<12)':chrome_fallback,'tab lens':lens,
 'player top (white cover)':np_top,'player mid (white cover)':np_mid,'player bottom (white cover)':np_bot,
 'player glass slab (white cover)':glass(np_mid),
}
texts = {'TextPrimary':TextPrimary,'Muted':Muted,'AccentLight':AccentLight,'PinkLight':PinkLight,'Good':Good,'ErrorText':Err}
worst=99; fails=[]
print(f"{'background':34}" + ''.join(f"{n:>12}" for n in texts))
for bn,b in backgrounds.items():
    row=f"{bn:34}"
    for tn,t in texts.items():
        r=ratio(t,b); worst=min(worst,r); row+=f"{r:12.1f}"
        if r<4.5: fails.append((tn,bn,round(r,2)))
    print(row)
print()
# Fixed pairs (filled controls)
pairs = {
 'white text on button start #7C4DEE':(WHITE,hx('7C4DEE')),
 'white text on button end #D4337F':(WHITE,hx('D4337F')),
 'black text on YouTube badge':(BLACK,hx('FF5A52')),'black on SoundCloud':(BLACK,hx('FF8A3D')),
 'black on TikTok':(BLACK,hx('25F4EE')),'black on Spotify':(BLACK,hx('1ED760')),'black on Web badge':(BLACK,hx('C4C4CC')),
 'black icon on white play button':(BLACK,WHITE),'white icon on Liked tile end':(WHITE,hx('D4337F')),
 'white icon on Downloads tile end #0A8A62':(WHITE,hx('0A8A62')),'white icon on Spotify tile':(WHITE,hx('0B6B35')),
 'Muted on disabled glass button':(Muted,glass(aurora_peak)),
}
for n,(a,b) in pairs.items():
    r=ratio(a,b); print(f"{n:42}{r:6.1f}" + ("" if r>=3 else "  <-- LOW"))
print("\nLowest text ratio on dark/glass backgrounds:", round(worst,2))
print("FAILS (<4.5):", fails or "none")
