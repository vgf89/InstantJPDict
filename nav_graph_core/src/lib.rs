uniffi::setup_scaffolding!();

/// Bounding box for a detected character.
#[derive(Clone, Debug, uniffi::Record)]
pub struct BoundingBox {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

/// Navigation graph with one outgoing edge per cardinal direction per node.
#[derive(Clone, Debug, uniffi::Record)]
pub struct NavGraph {
    pub edges: Vec<i32>,
    pub n: i32,
}

/// Build a nav graph from detected character bounding boxes.
#[uniffi::export]
pub fn build_nav_graph(boxes: Vec<BoundingBox>) -> NavGraph {
    let n = boxes.len();
    if n < 5 {
        return fallback(n);
    }

    // Normalise positions to [0, 1)
    let max_x = boxes.iter().map(|b| (b.x + b.w) as f32).fold(0.0f32, f32::max).max(1.0);
    let max_y = boxes.iter().map(|b| (b.y + b.h) as f32).fold(0.0f32, f32::max).max(1.0);
    let positions: Vec<(f32, f32)> = boxes.iter().map(|b| {
        let cx = (b.x as f32 + b.w as f32 / 2.0) / max_x;
        let cy = (b.y as f32 + b.h as f32 / 2.0) / max_y;
        (cx, cy)
    }).collect();

    const W: f32 = 10.0; // off-axis penalty Phase 1
    const W2: f32 = 3.0; // off-axis penalty Phase 2
    const LOCAL_DIST: f32 = 0.05;
    const DIR_MIN: f32 = 0.008;
    const CONE45: f32 = 1.0;
    const WRAP: f32 = 0.5;

    // ── Phase 1: local candidates ──
    let (n1, s1, e1, w1) = build_phase1(&positions, n, LOCAL_DIST, DIR_MIN, CONE45, W);

    // Greedy assignment Phase 1
    let mut edges: Vec<[usize; 4]> = (0..n).map(|i| {
        greedy_assignment(i, n, &n1[i], &s1[i], &e1[i], &w1[i])
    }).collect();
    let initial_edges = edges.clone();

    // ── Phase 2: global (unlimited distance, no wrap) ──
    let (n2, s2, e2, w2) = build_phase2(&positions, n, DIR_MIN, CONE45, W2);
    fill_empty(&mut edges, n, &n2, &s2, &e2, &w2);

    // ── Phase 3: torus wrap for remaining ──
    let (n3, s3, e3, w3) = build_phase3(&positions, n, DIR_MIN, CONE45, W2, WRAP);
    fill_empty(&mut edges, n, &n3, &s3, &e3, &w3);

    let flat: Vec<i32> = edges.iter().flat_map(|e| e.iter().map(|&v| v as i32)).collect();
    NavGraph { edges: flat, n: n as i32 }
}

/// Navigate from `idx` in `dir` (0=N,1=S,2=E,3=W). Returns `None` if slot is empty.
#[uniffi::export]
pub fn navigate(graph: &NavGraph, idx: i32, dir: i32) -> Option<i32> {
    if idx < 0 || idx >= graph.n || dir < 0 || dir >= 4 { return None; }
    let flat_idx = (idx * 4 + dir) as usize;
    let target = graph.edges[flat_idx];
    if target >= graph.n { None } else { Some(target) }
}

/// Get the raw flat edge array (4 entries per node: N,S,E,W).
pub fn get_edges(graph: &NavGraph) -> Vec<i32> {
    graph.edges.clone()
}

// ── helper functions ──

fn torus_dx(x1: f32, x2: f32) -> f32 { let r = (x1 - x2).abs(); r.min(1.0 - r) }
fn torus_dy(y1: f32, y2: f32) -> f32 { let r = (y1 - y2).abs(); r.min(1.0 - r) }

fn direction_check(dir: usize, xi: f32, yi: f32, xj: f32, yj: f32) -> Option<(f32, f32)> {
    match dir {
        0 => if yj < yi { Some((yi - yj, (xi - xj).abs())) } else { None },
        1 => if yj > yi { Some((yj - yi, (xi - xj).abs())) } else { None },
        2 => if xj > xi { Some((xj - xi, (yi - yj).abs())) } else { None },
        _ => if xj < xi { Some((xi - xj, (yi - yj).abs())) } else { None },
    }
}

fn build_phase1(
    pos: &[(f32, f32)], n: usize, max_dist: f32, dir_min: f32, cone45: f32, w: f32,
) -> (Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>) {
    let mut nl: Vec<Vec<(usize, f32)>> = Vec::with_capacity(n);
    let mut sl = Vec::with_capacity(n);
    let mut el = Vec::with_capacity(n);
    let mut wl = Vec::with_capacity(n);

    for i in 0..n {
        let (xi, yi) = pos[i];
        let mut nv = Vec::new();
        let mut sv = Vec::new();
        let mut ev = Vec::new();
        let mut wv = Vec::new();
        for j in 0..n {
            if i == j { continue; }
            let (xj, yj) = pos[j];
            let dx = (xi - xj).abs();
            let dy = (yi - yj).abs();
            let dist = (dx * dx + dy * dy).sqrt();
            if dist > max_dist { continue; }

            for (d, list) in [(0, &mut nv), (1, &mut sv), (2, &mut ev), (3, &mut wv)] {
                if let Some((prim, off)) = direction_check(d, xi, yi, xj, yj) {
                    if prim < dir_min { continue; }
                    if off > cone45 * prim { continue; }
                    list.push((j, prim + w * off));
                }
            }
        }
        nv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        sv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        ev.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        wv.sort_by(|a: &(usize, f32), b: &(usize, f32)| a.1.partial_cmp(&b.1).unwrap());
        nl.push(nv); sl.push(sv); el.push(ev); wl.push(wv);
    }
    (nl, sl, el, wl)
}

fn build_phase2(
    pos: &[(f32, f32)], n: usize, dir_min: f32, cone45: f32, w2: f32,
) -> (Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>) {
    let mut nl = Vec::with_capacity(n); let mut sl = Vec::with_capacity(n);
    let mut el = Vec::with_capacity(n); let mut wl = Vec::with_capacity(n);
    for i in 0..n {
        let (xi, yi) = pos[i];
        let mut nv = Vec::new(); let mut sv = Vec::new(); let mut ev = Vec::new(); let mut wv = Vec::new();
        for j in 0..n {
            if i == j { continue; }
            let (xj, yj) = pos[j];
            for (d, list) in [(0, &mut nv), (1, &mut sv), (2, &mut ev), (3, &mut wv)] {
                if let Some((prim, off)) = direction_check(d, xi, yi, xj, yj) {
                    if prim < dir_min { continue; }
                    if off > cone45 * prim { continue; }
                    list.push((j, prim + w2 * off));
                }
            }
        }
        nv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        sv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        ev.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        wv.sort_by(|a: &(usize, f32), b: &(usize, f32)| a.1.partial_cmp(&b.1).unwrap());
        nl.push(nv); sl.push(sv); el.push(ev); wl.push(wv);
    }
    (nl, sl, el, wl)
}

fn build_phase3(
    pos: &[(f32, f32)], n: usize, _dir_min: f32, _cone45: f32, w2: f32, wrap: f32,
) -> (Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>, Vec<Vec<(usize, f32)>>) {
    let mut nl = Vec::with_capacity(n); let mut sl = Vec::with_capacity(n);
    let mut el = Vec::with_capacity(n); let mut wl = Vec::with_capacity(n);
    for i in 0..n {
        let (xi, yi) = pos[i];
        let mut nv = Vec::new(); let mut sv = Vec::new(); let mut ev = Vec::new(); let mut wv = Vec::new();
        for j in 0..n {
            if i == j { continue; }
            let (xj, yj) = pos[j];
            let tx = torus_dx(xi, xj);
            let ty = torus_dy(yi, yj);
            // North wrapped: yj > yi (going up past y=0 wraps to y≈1)
            if yj > yi {
                let dy_n = (yi - yj + 1.0) % 1.0;
                nv.push((j, w2 * tx + dy_n + wrap));
            }
            // South wrapped: yj < yi
            if yj < yi {
                let dy_s = (yj - yi + 1.0) % 1.0;
                sv.push((j, w2 * tx + dy_s + wrap));
            }
            // East wrapped: xj < xi
            if xj < xi {
                let dx_e = (xj - xi + 1.0) % 1.0;
                ev.push((j, dx_e + w2 * ty + wrap));
            }
            // West wrapped: xj > xi
            if xj > xi {
                let dx_w = (xi - xj + 1.0) % 1.0;
                wv.push((j, dx_w + w2 * ty + wrap));
            }
        }
        nv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        sv.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        ev.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        wv.sort_by(|a: &(usize, f32), b: &(usize, f32)| a.1.partial_cmp(&b.1).unwrap());
        nl.push(nv); sl.push(sv); el.push(ev); wl.push(wv);
    }
    (nl, sl, el, wl)
}

fn fill_empty(
    edges: &mut Vec<[usize; 4]>, n: usize,
    north: &[Vec<(usize, f32)>], south: &[Vec<(usize, f32)>],
    east: &[Vec<(usize, f32)>], west: &[Vec<(usize, f32)>],
) {
    let lists = [north, south, east, west];
    for i in 0..n {
        for d in 0..4 {
            if edges[i][d] >= n {
                if let Some(&(v, _)) = lists[d][i].iter().find(|&&(v, _)| v != i && {
                    !(0..4).any(|od| edges[i][od] == v)
                }) {
                    edges[i][d] = v;
                }
            }
        }
    }
}

fn greedy_assignment(
    _i: usize, n: usize,
    north: &[(usize, f32)], south: &[(usize, f32)],
    east: &[(usize, f32)], west: &[(usize, f32)],
) -> [usize; 4] {
    let lists = [north, south, east, west];
    let mut result = [n; 4];
    let mut used = std::collections::HashSet::new();

    // Pick top choice per direction, distinct
    for d in 0..4 {
        for &(v, _) in lists[d] {
            if used.insert(v) {
                result[d] = v;
                break;
            }
        }
    }

    // Resolve conflicts via most-constrained-first retry
    let mut has_conflict = true;
    while has_conflict {
        has_conflict = false;
        let mut conflict_found = false;

        // Check for directional conflicts (same node from two directions)
        for d in 0..4 {
            if result[d] >= n { continue; }
            // Check if this node is used by another direction where it should be different
            for d2 in (d+1)..4 {
                if result[d2] >= n { continue; }
                if result[d] == result[d2] {
                    conflict_found = true;
                    // Keep the one with the better (smaller) list, replace the other
                    let (keep, replace) = if lists[d].len() <= lists[d2].len() { (d, d2) } else { (d2, d) };
                    let kept = result[keep];
                    for &(alt, _) in lists[replace] {
                        if alt != kept && !used.contains(&alt) {
                            used.remove(&result[replace]);
                            result[replace] = alt;
                            used.insert(alt);
                            break;
                        }
                    }
                    has_conflict = true;
                    break;
                }
            }
            if has_conflict { break; }
        }

        if !conflict_found { break; }
    }

    result
}

fn fallback(n: usize) -> NavGraph {
    let mut flat = Vec::with_capacity(n * 4);
    for i in 0..n {
        flat.push(((i + 1) % n) as i32);
        flat.push(((i + 2) % n) as i32);
        flat.push(((i + 3) % n) as i32);
        flat.push(((i + 4) % n) as i32);
    }
    NavGraph { edges: flat, n: n as i32 }
}
