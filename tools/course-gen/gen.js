// Generator for the parkour benchmark course data file. Run: node gen.js <out.txt> <summary.md>
const fs = require('fs');
const HD = [{n:'south',f:[0,1],yaw:0},{n:'east',f:[1,0],yaw:-90},{n:'north',f:[0,-1],yaw:180},{n:'west',f:[-1,0],yaw:90}];
const TOP = {full:1, fence:1.5, wall:1.5, pane:1, slab:0.5};
const HALF = {fence:0.125, wall:0.25, pane:0.0625};
const out = []; const warn = []; const jumps = []; const sections = [];
const vox = new Map();
let sec = 0, jn = 0, cpn = 0;
let cur = null; // {x,y,z,h,kind,rect,bounds,surf,runBack}
const fmt = v => (Math.round(v*1000)/1000).toString();

// ---- physics tables (vanilla 1.21.1 sprint jump, forward + sprint held) ----
function sim(dy, cap, surf, standing) {
  const slip = surf === 'ice' ? 0.98 : 0.6, f = slip*0.91;
  const a = 0.1274*(0.216/(slip*slip*slip));
  let v = standing ? 0 : (a/(1-f))*f;
  let x = 0, y = 0, vy = 0.42;
  for (let t = 1; t < 60; t++) {
    if (t > 1 && vy < 0 && y >= dy - 1e-9 && y + vy < dy) return x;
    if (t === 1) { v += 0.2 + a; x += v; v *= f; } else { v += 0.02548; x += v; v *= 0.91; }
    y += vy; if (cap != null && y > cap) { y = cap; vy = 0; }
    vy = (vy - 0.08)*0.98;
  }
  return NaN;
}
// slime: takeoff top 3 above the slime top; returns [x at slime touchdown, x when coming down through h, apex]
function slimeSim(h) {
  let v = 0.2806*0.546, x = 0, y = 3, vy = 0.42, xs = NaN;
  v += 0.2 + 0.1274; x += v; v *= 0.546; y += vy; vy = (vy-0.08)*0.98;
  for (let t = 0; t < 80; t++) { // fall to the slime
    if (y + vy <= 0) { xs = x; v += 0.02548; x += v; v *= 0.91; y = 0; vy = (-vy - 0.08)*0.98; break; }
    v += 0.02548; x += v; v *= 0.91; y += vy; vy = (vy-0.08)*0.98;
  }
  const fs_ = 0.8*0.91; v += 0.1274*(0.216/(0.8*0.8*0.8)); x += v; v *= fs_; y += vy; vy = (vy-0.08)*0.98;
  let apex = y;
  for (let k = 0; k < 80; k++) {
    if (vy < 0 && y >= h && y + vy < h) return [xs, x, apex];
    v += 0.02548; x += v; v *= 0.91; y += vy; vy = (vy-0.08)*0.98; apex = Math.max(apex, y);
  }
  return [xs, NaN, apex];
}

// ---- world helpers ----
function W(f, l, d) { const H = HD[cur.h], L = HD[(cur.h+1)%4];
  return [cur.x + f*H.f[0] + l*L.f[0], cur.y + d, cur.z + f*H.f[1] + l*L.f[1]]; }
function put(a, b, block) {
  const lo = [0,1,2].map(i => Math.min(a[i], b[i])), hi = [0,1,2].map(i => Math.max(a[i], b[i]));
  for (let x = lo[0]; x <= hi[0]; x++) for (let y = lo[1]; y <= hi[1]; y++) for (let z = lo[2]; z <= hi[2]; z++) {
    const k = x+','+y+','+z; if (vox.has(k) && vox.get(k).id !== block) warn.push('overwrite '+k+' '+vox.get(k).id+' -> '+block);
    vox.set(k, {id: block, sec}); }
  if (lo.every((v,i) => v === hi[i])) out.push(`set ${lo.join(' ')} ${block}`);
  else out.push(`fill ${lo.join(' ')} ${hi.join(' ')} ${block}`);
}
function box(f0,l0,d0,f1,l1,d1,block) { put(W(f0,l0,d0), W(f1,l1,d1), block); }
function centre(p, top) { return [p[0]+0.5, top, p[2]+0.5]; }
function route(p, tag) { out.push(`route ${fmt(p[0])} ${fmt(p[1])} ${fmt(p[2])} ${tag}`); }
function comment(s) { out.push('# ' + s); }
function shaped(kind, i, n) {
  const H = HD[cur.h].n, B = HD[(cur.h+2)%4].n; const b = n > 1 && i > 0, f = n > 1 && i < n-1;
  const s = [];
  if (kind === 'wall') { if(b)s.push(B+'=low'); if(f)s.push(H+'=low'); if (b&&f) s.push('up=false');
    return 'minecraft:cobblestone_wall'+(s.length?'['+s.join(',')+']':''); }
  if(b)s.push(B+'=true'); if(f)s.push(H+'=true');
  return (kind === 'fence' ? 'minecraft:oak_fence' : 'minecraft:glass_pane')+(s.length?'['+s.join(',')+']':'');
}
function boundsOf(a0, a1) { return {x0:Math.min(a0[0],a1[0]), x1:Math.max(a0[0],a1[0]), z0:Math.min(a0[2],a1[2]), z1:Math.max(a0[2],a1[2])}; }
// float AABB of a support surface given block-range in frame coords
function rectOf(f0, f1, l0, l1, d, kind) {
  const a = W(f0, l0, d), b = W(f1, l1, d);
  let x0 = Math.min(a[0],b[0]), x1 = Math.max(a[0],b[0])+1, z0 = Math.min(a[2],b[2]), z1 = Math.max(a[2],b[2])+1;
  if (HALF[kind] != null) { const n = f1-f0+1; let w = HALF[kind]; const e = HALF[kind];
    if (kind === 'wall' && n > 1) w = 0.1875;
    const alongZ = HD[cur.h].f[1] !== 0;
    if (alongZ) { const c = (x0+x1)/2; x0 = c-w; x1 = c+w; z0 += 0.5-e; z1 -= 0.5-e; }
    else { const c = (z0+z1)/2; z0 = c-w; z1 = c+w; x0 += 0.5-e; x1 -= 0.5-e; } }
  return {x0,x1,z0,z1};
}
function clip(A, B, r, m) { // range of distance along A->B (extended) inside rect expanded by m
  const dx = B[0]-A[0], dz = B[2]-A[2], len = Math.hypot(dx, dz); let t0 = -1e9, t1 = 1e9;
  for (const [p, d, lo, hi] of [[A[0],dx/len,r.x0-m,r.x1+m],[A[2],dz/len,r.z0-m,r.z1+m]]) {
    if (Math.abs(d) < 1e-9) { if (p < lo || p > hi) return null; continue; }
    let a = (lo-p)/d, b = (hi-p)/d; if (a > b) [a,b] = [b,a]; t0 = Math.max(t0,a); t1 = Math.min(t1,b); }
  return t0 <= t1 ? [t0, t1] : null;
}
function window_(A, B, landRect, dySurf, cap) {
  const X = sim(dySurf, cap, cur.surf, false);
  const step = cur.surf === 'ice' ? 0.2703 : 0.2806;
  const te = clip(A, B, cur.rect, 0.3), tl = clip(A, B, landRect, 0.3);
  if (!te || !tl || isNaN(X)) return {ticks: 0, X};
  const lo = Math.max(tl[0]-X, te[0], -cur.runBack-0.3), hi = Math.min(te[1]+step, tl[1]-X);
  const hg = Math.min(hi, te[1]);
  return {ticks: (hi-lo)/step, X, lo, hi, press: Math.max((lo+hg)/2, hg-0.5)};
}

function section(n, name, theme) { sec = n; sections.push({n, name, theme, jumps: []}); out.push(''); comment(`==== SECTION ${n}: ${name} ====`); comment(theme); }
function meta(tech, diff, assist, note, win) {
  jn++; const id = 'J' + String(jn).padStart(2,'0');
  const w = win == null ? '' : ` win=${win.toFixed(1)}t`;
  out.push(`jump ${id} ${sec} ${tech} ${diff} assist=${assist}${w} | ${note}`);
  const j = {id, sec, tech, diff, assist, note, win}; jumps.push(j); sections[sections.length-1].jumps.push(j); return j;
}
/**
 * A jump from the cursor to a new platform. gap = air cells along the heading, side = lateral shift (left +),
 * dy = change of the landing CELL, kind = full|fence|wall|pane|slab.
 */
function jump(o) {
  const gap = o.gap, dy = o.dy||0, side = o.side||0, len = o.len||4, width = o.width||3, kind = o.kind||'full';
  const hw = (width-1)/2, block = o.block || cur.theme;
  const l0 = side-hw, l1 = side+hw;
  const fromL = o.fromL||0;
  let A = centre(W(0,fromL,0), cur.y + TOP[cur.kind]);
  if (fromL) route(centre(W(-(o.runBack||3),fromL,0), cur.y + TOP[cur.kind]), 'run');
  if (o.ceil) box(o.ceil.from, -1, o.ceil.dy, o.ceil.to, 1, o.ceil.dy, 'minecraft:orange_stained_glass');
  if (o.pre) o.pre();
  if (kind === 'fence' || kind === 'wall' || kind === 'pane') {
    for (let i = 0; i < len; i++) box(gap+1+i, side, dy, gap+1+i, side, dy, shaped(kind, i, len));
  } else if (kind === 'slab') box(gap+1, l0, dy, gap+len, l1, dy, 'minecraft:smooth_stone_slab[type=bottom]');
  else box(gap+1, l0, dy, gap+len, l1, dy, block);
  const landL = o.landL != null ? o.landL : side, aim = o.aim || [0,0];
  const B = centre(W(gap+1, landL, dy), cur.y + dy + TOP[kind]); { const H = HD[cur.h].f, L = HD[(cur.h+1)%4].f; B[0] += aim[0]*H[0] + aim[1]*L[0]; B[2] += aim[0]*H[1] + aim[1]*L[1]; }
  const rect = rectOf(gap+1, gap+len, l0, l1, dy, kind);
  const dySurf = dy + TOP[kind] - TOP[cur.kind];
  let win = null;
  if (!o.manual) { const w = window_(A, B, rect, dySurf, o.cap); win = w.ticks;
    if (w.press != null) { const d = Math.hypot(B[0]-A[0], B[2]-A[2]); A = [A[0] + (B[0]-A[0])/d*w.press, A[1], A[2] + (B[2]-A[2])/d*w.press]; }
    if (!(w.ticks >= (o.minWin||1.5))) warn.push(`J${jn+1} window ${w.ticks.toFixed(2)} ticks (X=${w.X.toFixed(3)})`); }
  const surfNote = dySurf === 0 ? 'level' : (dySurf > 0 ? '+' : '') + dySurf + ' surface';
  const geo = `${gap} air, ${surfNote}${side ? ', side ' + side : ''}, onto ${o.what || (width === 1 && len === 1 ? 'a single block' : 'a ' + width + 'x' + len + (width === 1 ? ' strip' : ' pad'))}`;
  meta(o.tech, o.diff, o.assist || 'ok', geo + (o.note ? '; ' + o.note : ''), win);
  route(A, 'takeoff'); if (o.via) route([centre(W(o.via[0],0,0),0)[0] + o.via[1]*HD[(cur.h+1)%4].f[0], cur.y + TOP[cur.kind] + 1, centre(W(o.via[0],0,0),0)[2] + o.via[1]*HD[(cur.h+1)%4].f[1]], 'via'); route(B, 'land');
  const p = W(gap+len, side, dy), a0 = W(gap+1, l0, dy), a1 = W(gap+len, l1, dy);
  cur = {x:p[0], y:p[1], z:p[2], h:cur.h, kind, rect, theme:cur.theme, surf: o.surf || 'normal', runBack: len-0.5, bounds: boundsOf(a0,a1)};
}
function turn(dir, quiet) { // dir: +1 left, -1 right; on a square full pad
  const b = cur.bounds, cx = (b.x0+b.x1)/2, cz = (b.z0+b.z1)/2;
  const h = (cur.h + (dir > 0 ? 1 : 3)) % 4, F = HD[h].f;
  if (!quiet) route([cx+0.5, cur.y+1, cz+0.5], 'run');
  cur.h = h; cur.x = cx + F[0]*(b.x1-b.x0)/2; cur.z = cz + F[1]*(b.z1-b.z0)/2; cur.runBack = 2.5;
}
function checkpoint(name, dir, o) {
  jump(Object.assign({len:3, width:3, block:'minecraft:gold_block', what:'the checkpoint pad'}, o));
  const b = cur.bounds, cx = (b.x0+b.x1)/2, cz = (b.z0+b.z1)/2; cpn++;
  turn(dir, true);
  out.push(`checkpoint ${cpn} ${cx+0.5} ${cur.y+1} ${cz+0.5} ${HD[cur.h].yaw} ${name}`);
  route([cx+0.5, cur.y+1, cz+0.5], 'checkpoint');
}
function ladderClimb(H) { // ladders above the last block of the current pad, wall behind them
  const A = centre(W(0,0,0), cur.y+1);
  box(1,-1,0, 3,1,H, cur.theme);
  box(0,-1,1, 0,1,H, `minecraft:ladder[facing=${HD[(cur.h+2)%4].n}]`);
  meta('ladder-climb', 1, 'none', `walk into the ladder wall (3 wide) at the end of the pad and climb, +${H} surface; hold forward, no jump`, null);
  const B = centre(W(1,0,H), cur.y+H+1); route(A, 'climb'); route(B, 'land');
  const p = W(3,0,H), a0 = W(1,-1,H), a1 = W(3,1,H), rect = rectOf(1,3,-1,1,H,'full');
  cur = {x:p[0],y:p[1],z:p[2],h:cur.h,kind:'full',theme:cur.theme,surf:'normal',runBack:2.5, rect, bounds: boundsOf(a0,a1)};
}
function climbCatch(air, up, o) { // air cells, then the climbable cell on the face of a tower `up` cells higher
  const top0 = cur.y + TOP[cur.kind];
  const A = centre(W(0,0,0), top0);
  const c = air+1;
  box(c+1,-1,-2, c+3,1,up, o.block);
  if (o.vine) box(c,-1,-1, c,1,up, `minecraft:vine[${HD[cur.h].n}=true]`);
  else box(c,-1,-1, c,1,up, `minecraft:ladder[facing=${HD[(cur.h+2)%4].n}]`);
  const rise = up + 1 - TOP[cur.kind];
  meta(o.vine ? 'vine-catch' : 'ladder-catch', o.diff, 'none', `${air} air, then catch the ${o.vine?'vines':'ladders'} (3 wide) on the tower face and climb, +${rise} surface; keep forward held` + (o.note ? '; ' + o.note : ''), null);
  route(A, 'takeoff'); route(centre(W(c,0,0), top0), 'climb'); route(centre(W(c+1,0,up), cur.y+up+1), 'land');
  const p = W(c+3,0,up), a0 = W(c+1,-1,up), a1 = W(c+3,1,up), rect = rectOf(c+1,c+3,-1,1,up,'full');
  cur = {x:p[0],y:p[1],z:p[2],h:cur.h,kind:'full',theme:cur.theme,surf:'normal',runBack:2.5, rect, bounds: boundsOf(a0,a1)};
}
const E_FROM_S = +1, E_FROM_N = -1, N_FROM_E = +1, S_FROM_E = -1;
const TURNPAD = {len:3, what:'the 3x3 turn pad', block:'minecraft:polished_andesite'};
const T = o => Object.assign({}, TURNPAD, o);

// ================================ COURSE ================================
out.push('# Parkour benchmark course for mc-utility-suite (Movement Assist benchmark).');
out.push('# Format, rules and scoring: docs/design/parkour-benchmark-course.md');
out.push('# Coordinates are relative to the course origin: (0,0,0) is the start pad\'s centre top BLOCK; feet stand at y+1.');
out.push('# +x east, +z south, yaw 0 = south (vanilla). start/checkpoint/route/alt positions are feet positions.');
out.push('# Commands: clear, fill, set, fail, kit, start, finish, checkpoint, jump, route, alt. Lines are in build order.');
out.push('# jump <id> <section> <technique> <difficulty 1-5> assist=<ok|uncertain|hard|blind|none> [win=<takeoff window in ticks>] | <note>');
out.push('# route/alt tags: start, run, takeoff, via, land, climb, bounce, checkpoint, finish.');
const HEAD = out.length;

// ---- Section 1 ----
section(1, 'Warm-up gaps', 'Flat sprint jumps on 3-wide pads: 1 to 3 air, one step up, one step down, small sideways shifts.');
cur = {x:0,y:0,z:0,h:0,kind:'full',theme:'minecraft:smooth_stone',surf:'normal',runBack:4.5};
put([-1,0,-2],[1,0,2],'minecraft:lime_concrete');
cur.z = 2; cur.rect = {x0:-1,x1:2,z0:-2,z1:3}; cur.bounds = {x0:-1,x1:1,z0:-2,z1:2};
out.push('start 0.5 1 0.5 0'); route([0.5,1,0.5],'start');
jump({gap:1, tech:'sprint-jump', diff:1});
jump({gap:2, tech:'sprint-jump', diff:1});
jump({gap:3, tech:'sprint-jump', diff:2});
jump({gap:2, dy:1, tech:'sprint-jump-up', diff:2});
jump({gap:3, dy:-1, tech:'sprint-jump-down', diff:2});
jump({gap:3, side:-1, tech:'sprint-jump', diff:2});
checkpoint('warmup', E_FROM_S, {gap:2, side:1, tech:'sprint-jump', diff:1});

// ---- Section 2 ----
section(2, 'Strips and offsets', 'One-wide strips with sideways shifts, a height change, a single block.');
cur.theme = 'minecraft:light_blue_concrete';
jump({gap:2, width:1, len:4, tech:'strip', diff:2});
jump(T({gap:2, tech:'sprint-jump', diff:1}));
turn(N_FROM_E);
jump({gap:2, width:1, len:5, tech:'strip', diff:2});
jump({gap:3, side:1, width:1, len:4, tech:'strip-to-strip', diff:3});
jump({gap:2, dy:1, width:1, len:4, tech:'strip-up', diff:3});
jump({gap:3, side:-1, width:1, len:3, tech:'strip-to-strip', diff:3, note:'short landing: 3 long'});
jump({gap:3, dy:-1, side:-2, width:1, len:6, tech:'strip-diagonal-down', diff:3, note:'aim diagonally, the strip is long'});
jump({gap:2, width:1, len:1, tech:'single-block', diff:3});
checkpoint('strips', E_FROM_N, {gap:2, tech:'block-to-pad', diff:2});

// ---- Section 3 ----
section(3, 'Narrow landings', 'Fence and wall tops (1.5 high: half a block above pad level), a lone glass pane; rows and lone posts.');
cur.theme = 'minecraft:stone_bricks';
jump({gap:2, kind:'fence', width:1, len:3, tech:'fence-row', diff:3, what:'a fence row (3 long, 0.25 wide)'});
jump(T({gap:2, tech:'post-to-pad', diff:2}));
turn(S_FROM_E);
jump({gap:2, kind:'fence', width:1, len:1, tech:'fence-post', diff:4, what:'a lone fence post (0.25 wide)', note:'same geometry as the fence-2gap lane'});
jump({gap:2, tech:'post-to-pad', diff:2});
jump({gap:2, kind:'wall', width:1, len:1, tech:'wall-post', diff:3, what:'a lone wall post (0.5 wide)', note:'one air shorter than the wall-3gap lane, whose takeoff window is 1.4 ticks'});
jump({gap:2, tech:'post-to-pad', diff:2});
jump({gap:1, kind:'pane', width:1, len:1, tech:'pane-post', diff:4, minWin:1, what:'a lone glass pane (0.125 wide post)', note:'same geometry as the pane-1gap lane; a late full sprint jump overshoots: jump early or drop sprint'});
jump({gap:2, tech:'post-to-pad', diff:2});
jump({gap:2, kind:'wall', width:1, len:4, tech:'wall-row', diff:3, what:'a wall row (4 long, 0.375 wide)'});
checkpoint('narrow', E_FROM_S, {gap:2, tech:'post-to-pad', diff:2});

// ---- Section 4 ----
section(4, 'Corners and neo', 'Landings behind a column: round a pillar, corner jumps onto a block, a fence post and a wall post, then a one-block neo.');
cur.theme = 'minecraft:polished_deepslate';
jump({gap:2, tech:'sprint-jump', diff:1});
jump(T({gap:2, tech:'pillar-dodge', diff:2, fromL:1, landL:1, runBack:2,
  pre:() => box(1,0,1, 1,0,3, 'minecraft:deepslate_bricks'), note:'a 3-high pillar floats in the middle of the gap: take the left or right lane'}));
turn(N_FROM_E);
jump({gap:2, len:6, tech:'sprint-jump', diff:1, note:'runway for the corner jumps'});
jump({gap:2, side:1, width:1, len:1, tech:'corner', diff:3, fromL:-1, aim:[0.2,-0.25], assist:'uncertain',
  pre:() => box(1,1,1, 1,1,2, 'minecraft:deepslate_bricks'),
  note:'a 2-high column floats at the pad edge in the left lane, the block is behind it (the TechniqueLibrary corner case); run the middle or right lane and cut in'});
jump({gap:2, len:6, tech:'block-to-pad', diff:2});
jump({gap:2, side:1, kind:'fence', width:1, len:1, tech:'corner-fence', diff:5, fromL:-1, aim:[0.2,-0.25], assist:'hard',
  pre:() => box(1,1,0, 1,1,3, 'minecraft:deepslate_bricks'), what:'a lone fence post',
  note:'the corner-fence-2gap lane: column one block deep in the left lane, post behind it; the route comes in diagonally from the right lane. KNOWN LIMIT: Co-pilot walked off here with a held-back press on the straight approach (fix unverified in game); Corrections promises no landing'});
jump({gap:2, len:7, side:-1, tech:'post-to-pad', diff:2});
jump({gap:2, side:1, kind:'wall', width:1, len:1, tech:'corner-wall', diff:4, fromL:-1, aim:[0.2,-0.25], assist:'uncertain',
  pre:() => box(1,1,0, 1,1,3, 'minecraft:deepslate_bricks'), what:'a lone wall post',
  note:'as the corner fence but onto a wall post. One air shorter than the corner-wall-3gap lane (that one is frame-tight for a player, and Co-pilot finds no script in one of four tick phases)'});
jump({gap:2, side:-1, width:1, len:4, tech:'post-to-strip', diff:3});
jump({gap:1, width:1, len:3, tech:'neo', diff:4, manual:true, via:[1,0.9], assist:'uncertain',
  pre:() => box(1,0,0, 1,0,3, 'minecraft:deepslate_bricks'), what:'a 1x3 strip behind the pillar',
  note:'one-block neo: a 4-high pillar fills the single gap cell; jump out round either side and curve back in. The NEO template exists but has not been run in game'});
checkpoint('corners', E_FROM_N, {gap:2, tech:'strip-to-pad', diff:2});

// ---- Section 5 ----
section(5, 'Ceilings and head-hitters', 'Orange glass ceilings: 3 blocks of headroom shorten the jump, 2 blocks of headroom cut it to a hop.');
cur.theme = 'minecraft:cut_copper';
jump({gap:2, tech:'low-ceiling', diff:2, cap:1.2, ceil:{dy:4, from:1, to:2}, note:'3 blocks of headroom over the gap; jump from the edge, an early jump hits the ceiling face'});
jump(T({gap:3, tech:'low-ceiling', diff:4, cap:1.2, ceil:{dy:4, from:-1, to:3}, note:'3 blocks of headroom: the longest jump that ceiling allows'}));
turn(S_FROM_E);
box(0,-1,3, 11,1,3, 'minecraft:orange_stained_glass');
jump({gap:1, len:3, tech:'head-hitter-hop', diff:3, cap:0.2, note:'tunnel with 2 blocks of headroom: the jump is a 0.2-high hop, take off at the very edge'});
jump({gap:1, len:3, tech:'head-hitter-hop', diff:3, cap:0.2});
jump({gap:1, len:4, tech:'head-hitter-hop', diff:3, cap:0.2, note:'the tunnel ends 2 blocks into this pad'});
jump({gap:2, dy:1, len:5, tech:'slot-jump-up', diff:4, cap:1.2, ceil:{dy:4, from:-1, to:5}, note:'up one block into a slot with 2 blocks of headroom; the ceiling covers the takeoff, the gap and the first 3 landing blocks'});
checkpoint('ceilings', E_FROM_S, {gap:2, dy:-1, tech:'sprint-jump-down', diff:1});

// ---- Section 6 ----
section(6, 'Ladders and vines', 'Climbing: a ladder wall, a jump onto ladders, a jump onto vines. Movement Assist does not model climbables.');
cur.theme = 'minecraft:spruce_planks';
jump({gap:2, tech:'sprint-jump', diff:1});
jump(T({gap:2, dy:1, tech:'sprint-jump-up', diff:2}));
turn(N_FROM_E);
jump({gap:2, tech:'sprint-jump', diff:1});
ladderClimb(4);
climbCatch(2, 3, {diff:3, block:'minecraft:spruce_planks', note:'KNOWN LIMIT: ladders are not landing candidates for the assist'});
climbCatch(2, 3, {diff:3, vine:true, block:'minecraft:mossy_cobblestone', note:'KNOWN LIMIT: as above. Needs randomTickSpeed 0 so the vines do not grow'});
jump({gap:3, dy:-1, tech:'sprint-jump-down', diff:2});
checkpoint('climb', E_FROM_N, {gap:2, tech:'sprint-jump', diff:1});

// ---- Section 7 ----
section(7, 'Staircase and plunge', 'Up five surface levels in steps (one onto slabs), then long jumps down.');
cur.theme = 'minecraft:cut_sandstone';
jump({gap:1, dy:1, len:3, tech:'sprint-jump-up', diff:1});
jump(T({gap:2, dy:1, tech:'sprint-jump-up', diff:2}));
turn(S_FROM_E);
jump({gap:2, dy:1, side:1, len:3, tech:'sprint-jump-up', diff:3});
jump({gap:2, dy:1, kind:'slab', len:3, tech:'slab-up', diff:2, what:'a 3x3 pad of bottom slabs'});
jump({gap:2, dy:0, len:4, tech:'slab-to-block-up', diff:2});
jump({gap:4, dy:-1, len:5, tech:'long-jump-down', diff:4, note:'4 air: the longest gap on the main line'});
jump({gap:4, dy:-2, len:5, tech:'long-jump-down', diff:3});
jump({gap:3, dy:-2, side:2, len:4, tech:'diagonal-down', diff:2});
jump({gap:3, dy:-1, width:1, len:4, tech:'strip-down', diff:3});
checkpoint('stairs', E_FROM_S, {gap:2, dy:-1, tech:'sprint-jump-down', diff:1});

// ---- Section 8 ----
section(8, 'Momentum: ice and slime', 'Packed ice keeps speed and makes sprint jumps longer; then a slime bounce, which the assist does not model.');
cur.theme = 'minecraft:quartz_bricks';
jump({gap:2, tech:'sprint-jump', diff:1});
jump(T({gap:3, tech:'sprint-jump', diff:2}));
turn(N_FROM_E);
jump({gap:2, len:6, tech:'ice-landing', diff:2, block:'minecraft:packed_ice', surf:'ice', what:'a 3x6 packed ice runway', note:'keep sprinting, do not stop on the ice'});
jump({gap:4, len:5, tech:'ice-long-jump', diff:3, note:'4 air from ice at running speed (reach is about 5 air); fails if you stopped on the ice'});
jump({gap:3, len:5, tech:'ice-landing', diff:2, block:'minecraft:packed_ice', surf:'ice', what:'a 3x5 packed ice pad'});
jump({gap:3, dy:1, len:5, tech:'ice-jump-up', diff:4, note:'3 air and one up: only with the speed kept on the ice'});
jump({gap:2, dy:-3, len:3, tech:'slime-drop', diff:2, assist:'blind', block:'minecraft:slime_block', what:'a 3x3 slime pad', note:'jump off the edge (do not walk off: the bounce is then too low), drop 3 onto slime, keep forward held and let go of jump. KNOWN LIMIT: the replica does not model the bounce'});
(function slimeBounce() {
  const [xs, xd, apex] = slimeSim(2);
  // xd is measured from the takeoff position of the drop; takeoff p is edge-relative in [-1.2, 0.5];
  // the slime pad spans edge+2..edge+5 and the cursor is its last block (centre at edge + 4.5)
  const near = -1.2 + xd, far = 0.5 + xd;
  const fNear = Math.ceil(near - 4.5 - 0.5 - 0.3), fFar = Math.floor(far - 4.5 + 0.5 + 0.3) + 1;
  const A = centre(W(-1,0,0), cur.y+1);
  box(fNear,-1,2, fFar,1,2, 'minecraft:quartz_bricks');
  meta('slime-bounce', 3, 'blind', `bounce (apex about ${apex.toFixed(1)} above the slime) with forward held onto a 3x${fFar-fNear+1} pad, +2 surface, ${fNear-1} air beyond the slime; touchdown ${near.toFixed(1)} to ${far.toFixed(1)} blocks past the drop edge depending on the takeoff. KNOWN LIMIT: bounce not modelled`, null);
  route(A, 'bounce'); route(centre(W(fNear+1,0,2), cur.y+3), 'land');
  const p = W(fFar,0,2), a0 = W(fNear,-1,2), a1 = W(fFar,1,2), rect = rectOf(fNear,fFar,-1,1,2,'full');
  console.error('slime: touchdown on slime', xs.toFixed(2), 'from takeoff; on target', xd.toFixed(2), 'apex', apex.toFixed(2), 'pad f', fNear, fFar, 'slime covers takeoff p in', (2-0.3-xs).toFixed(2), (5+0.3-xs).toFixed(2));
  cur = {x:p[0],y:p[1],z:p[2],h:cur.h,kind:'full',theme:cur.theme,surf:'normal',runBack:3, rect, bounds: boundsOf(a0,a1)};
})();
checkpoint('momentum', E_FROM_N, {gap:3, tech:'sprint-jump', diff:2});

// ---- Section 9 ----
section(9, 'Blocks assist', 'Two obstacles with a slow way round and a shortcut that one placed block opens: a 2-high wall and a 4-air gap. The runner carries cobblestone.');
out.push('kit 9 minecraft:cobblestone 32');
cur.theme = 'minecraft:bricks';
jump({gap:2, tech:'sprint-jump', diff:1});
jump(T({gap:2, tech:'sprint-jump', diff:1}));
turn(S_FROM_E);
jump({gap:2, len:12, tech:'sprint-jump', diff:1, what:'the 3x12 wall platform'});
(function wallAndGap() {
  const f0 = -11, y = cur.y; // cursor = last block (index 11) of the platform
  box(f0+7,-1,1, f0+7,1,2, 'minecraft:stone_bricks');            // the wall: 2 high across the lane
  box(f0+6,4,0, f0+8,5,0, 'minecraft:bricks');                    // side pad (way round)
  const save = Object.assign({}, cur);
  const P = (f,l) => centre(W(f,l,0), y+1);
  const rectA = rectOf(f0, f0+6, -1, 1, 0, 'full'), rectD = rectOf(f0+6, f0+8, 4, 5, 0, 'full'), rectB = rectOf(f0+8, 0, -1, 1, 0, 'full');
  route(P(f0+3,0), 'run');
  cur.rect = rectA; cur.runBack = 4;
  let w = window_(P(f0+5,1), P(f0+6,4), rectD, 0, null);
  meta('side-hop', 2, 'ok', 'way round the wall, 1 of 2: from the left lane, 2 air sideways and one block forward onto the 2x3 side pad', w.ticks);
  route(P(f0+5,1), 'takeoff'); route(P(f0+6,4), 'land');
  cur.rect = rectD; cur.runBack = 2;
  w = window_(P(f0+8,4), P(f0+9,1), rectB, 0, null);
  meta('side-hop', 2, 'ok', 'way round the wall, 2 of 2: back onto the platform behind the wall', w.ticks);
  route(P(f0+8,4), 'takeoff'); route(P(f0+9,1), 'land');
  comment('Shortcut W (optional): over the 2-high wall with one placed block (jump, place a block under the feet near the top, land on it, jump again).');
  comment('A player can do it by hand; Movement Assist "parkour with blocks" does it for them. It replaces the two side hops.');
  out.push(`alt W ${fmt(P(f0+5,0)[0])} ${y+1} ${fmt(P(f0+5,0)[2])} takeoff`);
  out.push(`alt W ${fmt(P(f0+9,0)[0])} ${y+1} ${fmt(P(f0+9,0)[2])} land`);
  cur = Object.assign({}, save); cur.rect = rectB; cur.runBack = 3;
  box(2,4,0, 3,5,0, 'minecraft:bricks');                          // island: f +2..+3, l 4..5
  box(5,-1,0, 9,1,0, 'minecraft:bricks');                         // far platform: f +5..+9
  const rectI = rectOf(2,3,4,5,0,'full'), rectB2 = rectOf(5,9,-1,1,0,'full');
  route(P(-1,0), 'run');
  w = window_(P(0,1), P(2,4), rectI, 0, null);
  meta('island-hop', 2, 'ok', 'way round the 4-air gap, 1 of 2: diagonally left onto the 2x2 island', w.ticks);
  route(P(0,1), 'takeoff'); route(P(2,4), 'land');
  cur.rect = rectI; cur.runBack = 1;
  w = window_(P(3,4), P(5,1), rectB2, 0, null);
  meta('island-hop', 2, 'ok', 'way round the 4-air gap, 2 of 2: diagonally back onto the far platform', w.ticks);
  route(P(3,4), 'takeoff'); route(P(5,1), 'land');
  cur = Object.assign({}, save); cur.rect = rectB; cur.runBack = 3;
  w = window_(P(0,0), P(5,0), rectB2, 0, null);
  comment(`Shortcut G (optional): straight over the 4-air gap. The one frame-tight jump of the course: takeoff window ${w.ticks.toFixed(1)} tick, on the tick after the body leaves the edge.`);
  comment('Movement Assist can re-time the press or place one block against the far side. There is no 5-air gap on the course: no technique or single block saves one.');
  out.push(`alt G ${fmt(P(0,0)[0])} ${y+1} ${fmt(P(0,0)[2])} takeoff`);
  out.push(`alt G ${fmt(P(5,0)[0])} ${y+1} ${fmt(P(5,0)[2])} land`);
  console.error('4-air shortcut window', w.ticks.toFixed(2), 'X', w.X.toFixed(3));
  const p = W(9,0,0), a0 = W(5,-1,0), a1 = W(9,1,0);
  cur = {x:p[0],y:p[1],z:p[2],h:save.h,kind:'full',theme:save.theme,surf:'normal',runBack:4.5, rect:rectB2, bounds: boundsOf(a0,a1)};
})();
checkpoint('blocks', E_FROM_S, {gap:2, tech:'sprint-jump', diff:1});

// ---- Section 10 ----
section(10, 'Finale: mixed', 'No rest pads: strip, single block, fence row, wall post, ladder catch, ice, corner, finish.');
cur.theme = 'minecraft:purpur_block';
jump({gap:3, width:1, len:4, tech:'strip', diff:3});
jump(T({gap:2, dy:1, tech:'strip-up', diff:2}));
turn(N_FROM_E);
jump({gap:2, width:1, len:1, tech:'single-block', diff:3});
jump({gap:3, dy:-1, kind:'fence', width:1, len:3, tech:'block-to-fence-row', diff:4, what:'a fence row (3 long)', note:'from a single block: keep the speed of the jump before'});
jump({gap:2, kind:'wall', width:1, len:1, tech:'fence-to-wall-post', diff:4, what:'a lone wall post'});
climbCatch(2, 3, {diff:3, block:'minecraft:purpur_pillar', note:'from the wall post. KNOWN LIMIT: ladders are not landing candidates for the assist'});
jump({gap:3, dy:-2, len:5, tech:'ice-landing-down', diff:3, block:'minecraft:packed_ice', surf:'ice', what:'a 3x5 packed ice pad', note:'3-block run-up on the tower top'});
jump({gap:4, len:6, tech:'ice-long-jump', diff:3, note:'4 air from ice; the pad is the runway for the last corner'});
jump({gap:2, side:-1, width:1, len:1, tech:'corner', diff:3, fromL:1, aim:[0.2,0.25], assist:'uncertain',
  pre:() => box(1,-1,1, 1,-1,2, 'minecraft:purpur_pillar'), note:'mirror of the section 4 corner: the column is in the right lane'});
(function finish() {
  jump({gap:2, len:5, width:5, block:'minecraft:emerald_block', tech:'block-to-pad', diff:2, what:'the 5x5 finish pad'});
  const b = cur.bounds; out.push(`finish ${b.x0} ${cur.y+1} ${b.z0} ${b.x1+1} ${cur.y+4} ${b.z1+1}`);
  route([(b.x0+b.x1)/2+0.5, cur.y+1, (b.z0+b.z1)/2+0.5], 'finish');
})();

// ================================ CHECKS + OUTPUT ================================
let mn=[1e9,1e9,1e9], mx=[-1e9,-1e9,-1e9]; const cells=[];
for (const [k,v] of vox) { const p=k.split(',').map(Number); cells.push([p,v]); for(let i=0;i<3;i++){mn[i]=Math.min(mn[i],p[i]);mx[i]=Math.max(mx[i],p[i]);} }
let worst = 1e9, worstInfo='';
const bySec = {}; for (const [p,v] of cells) (bySec[v.sec] = bySec[v.sec]||[]).push(p);
for (const a in bySec) for (const b in bySec) if (+b > +a+1) for (const p of bySec[a]) for (const q of bySec[b]) {
  if (Math.abs(p[1]-q[1]) > 8) continue; const d = Math.max(Math.abs(p[0]-q[0]), Math.abs(p[2]-q[2]));
  if (d < worst) { worst = d; worstInfo = `S${a} ${p} / S${b} ${q}`; } }
for (const [p,v] of cells) if (/fence|wall|pane/.test(v.id)) for (const [dx,dz] of [[1,0],[-1,0],[0,1],[0,-1]]) {
  const n = vox.get((p[0]+dx)+','+p[1]+','+(p[2]+dz)); if (n && !/fence|wall|pane/.test(n.id)) warn.push('narrow block touches '+n.id+' at '+p); }
const floorY = mn[1] - 8, M = 6;
const head = ['', '# The builder clears this volume first (in slices: /fill is capped at 32768 blocks), then lays the fail floor.',
  `clear ${mn[0]-M} ${floorY} ${mn[2]-M} ${mx[0]+M} ${mx[1]+6} ${mx[2]+M}`,
  `fill ${mn[0]-M} ${floorY} ${mn[2]-M} ${mx[0]+M} ${floorY} ${mx[2]+M} minecraft:red_stained_glass`,
  `fail ${floorY} minecraft:red_stained_glass`];
out.splice(HEAD, 0, ...head);
fs.writeFileSync(process.argv[2], out.join('\n') + '\n');
const md = [];
md.push('| # | Section | Jumps | Difficulty (avg / max) | Techniques |'); md.push('|---|---|---|---|---|');
for (const s of sections) { const d = s.jumps.map(j=>j.diff);
  md.push(`| ${s.n} | ${s.name} | ${s.jumps.length} (${s.jumps[0].id}-${s.jumps[s.jumps.length-1].id}) | ${(d.reduce((a,b)=>a+b,0)/d.length).toFixed(1)} / ${Math.max(...d)} | ${[...new Set(s.jumps.map(j=>j.tech))].join(', ')} |`); }
md.push(''); md.push('| Jump | Sec | Technique | Diff | Assist | Window (ticks) | Geometry and note |'); md.push('|---|---|---|---|---|---|---|');
for (const j of jumps) md.push(`| ${j.id} | ${j.sec} | ${j.tech} | ${j.diff} | ${j.assist} | ${j.win==null?'-':j.win.toFixed(1)} | ${j.note} |`);
fs.writeFileSync(process.argv[3], md.join('\n') + '\n');
console.error('jumps', jumps.length, 'lines', out.length, 'bbox', mn, mx, 'floorY', floorY, 'blocks', vox.size);
console.error('min spacing non-adjacent sections', worst, worstInfo);
{ let L=0, prev=null, climb=0; for (const l of out) if (l.startsWith('route ')) { const p=l.split(' ').slice(1,4).map(Number); if (prev) { L+=Math.hypot(p[0]-prev[0],p[2]-prev[2]); if (l.endsWith('land') && p[1]-prev[1]>2) climb+=p[1]-prev[1]; } prev=p; } console.error('route length', L.toFixed(1), 'climb', climb); }
 console.error('checkpoints', cpn); console.error(warn.join('\n') || 'no warnings');
console.error('tables: flat', sim(0).toFixed(3), '+1', sim(1).toFixed(3), '-1', sim(-1).toFixed(3), '-2', sim(-2).toFixed(3), '+0.5', sim(0.5).toFixed(3), 'ice flat', sim(0,null,'ice').toFixed(3), 'ice +1', sim(1,null,'ice').toFixed(3), 'cap1.2 flat', sim(0,1.2).toFixed(3), 'cap1.2 +1', sim(1,1.2).toFixed(3), 'hh', sim(0,0.2).toFixed(3), 'standing flat', sim(0,null,'n',true).toFixed(3));
