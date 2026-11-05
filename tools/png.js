const { chromium } = require('playwright');
(async () => {
  const [svg, out, w, h, s] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: +s });
  await p.goto('file://' + require('path').resolve(svg));
  await p.evaluate(([w, h]) => { const e = document.querySelector('svg'); e.setAttribute('width', w); e.setAttribute('height', h); }, [w, h]);
  await p.screenshot({ path: out }); await b.close();
})();
