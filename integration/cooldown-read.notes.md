# Cooldown reading (volley 2026-11-22)

Verified against the NeoForge 21.1.265 sources (`build/moddev/artifacts/neoforge-21.1.265-sources.jar`), not memory.

## What the client can see of another entity

- **Swings.** The server sends `ClientboundAnimatePacket` (action 0 main hand, 3 off hand) to trackers when the
  entity calls `LivingEntity.swing`. `ClientPacketListener.handleAnimate` calls `swing(hand)` on the client entity, which
  sets `swinging = true` and `swingTime = -1`; the entity's own tick (`updateSwingTime`) then moves `swingTime` to 0
  and up. A new swing can only start when not swinging or when `swingTime >= duration/2`, so a restart always shows as
  `swinging` turning on or `swingTime` going down. Mobs use the same path (zombie/skeleton arm swings arrive as the same
  packet). Caveat: any swing counts, not only attacks (block placing, interacting, missing a hit): the signal is
  "cooldown restarted", which is true for attack swings and over-eager for the rest.
- **Attack speed.** `Attributes.ATTACK_SPEED` is `setSyncable(true)`, and `ServerEntity` sends
  `ClientboundUpdateAttributesPacket` for syncable attributes (on tracking start and when dirty). Remote players have
  the attribute, and the held item's modifiers are applied server-side, so `getAttribute(ATTACK_SPEED).getValue()` on
  the client is the real speed (sword 1.6, axe 0.8-1.0, mace 0.6, bare hand 4.0). Mobs have no such attribute.
- **Hurt time.** `LivingEntity.hurtTime` is set to `hurtDuration` (10) on the client by the hurt entity event, then
  counts down.

## Fallbacks chosen (`MinecraftCombatWorld`)

1. Attribute present and its value differs from its base: use it.
2. Attribute present but equal to base (a server that does not sync item modifiers), or no attribute (mobs): base (4.0)
   plus the `ADD_VALUE` modifiers on `ATTACK_SPEED` from `getMainHandItem().getAttributeModifiers()` for the MAINHAND
   slot. The result is cached keyed by the `ItemAttributeModifiers` instance, so the modifier list is walked only when
   the held stack changes (one reusable `BiConsumer` field, no per-tick lambda). Item components that override
   modifiers are honoured because the component instance is the key.
3. Nothing known: 4.0 (the snapshot default).

Swing edge: previous `swinging`/`swingTime` are kept per target id; `targetSwingStarted` is true when
`swinging && (!prevSwinging || swingTime < prevSwingTime)`. A target switch, a gap of more than 2 game ticks since the
last sample, or a second sample inside the same game tick never produces a swing (a second sample in one tick returns
false even if the first returned true: the consumer should call `sample` once per tick).

`targetYaw` is `yBodyRot`. Own velocity is read from `getDeltaMovement()` (no `Vec3` allocated).

## Swing gate

`SwingGate.HURT_HOLD = 2`: the SWING action is withheld while `targetHurtTime > 2`, in both the plain and the crit
path (including the max-crit-wait fallback). Reasoning: vanilla immunity lasts 10 ticks, and a swing chosen now is
processed by the server one to two ticks later, so a swing chosen at hurtTime 2 arrives as the immunity ends. The hold
is therefore at most 8 ticks and ends by itself as hurtTime counts down; it can never outlast the immunity. JUMP
pre-hop is unchanged. With `targetHurtTime == 0` behaviour is identical to before. Note: damage greater than the first
hit goes through the immunity in vanilla (only the difference), so holding trades that for a full-charge hit; it is a
tuning choice for the playtest.

## OpponentModel

`noteSwing/attackReadyIn/attackCharge/cooldownTicks` per slot (`swingTick`, `swingPeriod`), reset on `clear()` and on
slot eviction. Speed clamped to 0.25..16 (NaN = sword default 12.5 ticks). Allocation test over 200k calls.

## Playtest list

- Swing detection against a real player and against mobs (zombie arm swings, skeletons): does `targetSwingStarted`
  fire exactly once per swing, never on a target switch?
- Attack speed reading for sword/axe/trident/mace holders on a server (attribute synced vs fallback path).
- Whether holding swings for the hurt window raises real damage per second (compare HURT_HOLD 0/2/4).
- That `sample()` stays allocation free in a Spark profile (new reads: delta movement, attribute lookup, cached item
  speed, swing edge).
- Nothing in `CombatAssistant` or the solver reads the new fields yet; wiring (calling `noteSwing` when
  `targetSwingStarted`) belongs to the solver task.
