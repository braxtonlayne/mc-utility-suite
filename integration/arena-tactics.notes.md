# arena-tactics integration notes

## TacticUtility (a = fight advantage 0..1, pressure = clamp(oppHopRate/1.5))
- HIGH_GROUND = (0.6a + 0.45*min(1,highGround)) * reach; reach = 1 at distance >= 2, 0.8 + 0.1*distance below.
  Capped at a - 0.01 when a >= 0.65 and distance <= 3 (never beats FIGHT when clearly winning in reach). 0 when highGround = 0.
- CEILING_DENY = 0.5a + 0.6*min(1,ceilingDeny)*pressure; 0 without a spot or without hops.
- Hysteresis (0.12), MIN_DWELL (10) and FLEE untouched. Both inputs 0 = exactly the old behaviour.

## ArenaTactics
- assess (call a few times a second): high = min(1, gain/2) * (1 - dist/10), 0 if py >= ty + 1; ceiling = min(1, columns/6) * (1 - dist/10). Spots cached (x,z).
- plan: keys relative to facing the target; forward = v.f, left = v.(fz,-fx); dead zone 0.3; sprint when FORWARD and spot > 3 away;
  jump when the next column (1 block toward the spot) has floorY = here + 1 and onGround; hazard flags veto the step;
  holding within 0.6, stays holding until 1.0 (so knockback jitter does not flap); false after 100 ticks without 0.05 progress
  (latched until the spot moves >= 1 block or the tactic changes / reset). The give-up latch is shared by both tactics.
- Wiring needs: call assess to feed TacticUtility.highGround/ceilingDeny; when plan() is true and holding() under CEILING_DENY, suppress hops.

## TacticsPlanner
- KNOCK_VECTOR when edgeKnock is on, no HOLD_GAP, knockDepth < 3 and knockValue >= 0.35 (KNOCK_VECTOR_MIN_VALUE).
  Same circling as EDGE_KNOCK (shared circleAndCloseIn) using knockFieldX/Z; sprint-in unless knockKind == KNOCK_WALL
  (wall: walk to 2.8 blocks and hold, no sprint). EDGE_KNOCK keeps priority. 3-argument plan unchanged.

## Left for playtest
Whether repositioning to high ground gives up too many free hits, the ceiling trap against a real crit-hopper, knock vectoring on real terrain.
