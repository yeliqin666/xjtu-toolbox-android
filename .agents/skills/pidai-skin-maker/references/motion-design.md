# 屁岱动作设计

`motion-format.md` says what the renderer can draw. This file is about deciding what to draw. Read it before the storyboard, not after.

The failure mode this exists to prevent is not ugliness. It is **filler**: motion that animates correctly, passes every check, and would work just as well for a completely different character. An orbiting dot, a pulsing glow, a gentle bob. They are what you reach for when you have not decided what the character is.

## Start from material, desire, constraint

Three questions, answered before any keyframe:

- **Material** — what is it made of, and how does that stuff move? A shell is rigid and fails suddenly. A stem is flexible and returns. A blob has no skeleton and slumps. Paper creases. Liquid finds a level.
- **Desire** — what does it want? Out. Toward light. To be left alone. To show you something.
- **Constraint** — what stops it? A shell it has not broken yet. Roots. Its own weight. Being very small.

Motion is what happens when desire meets constraint through a material. That sentence is the whole method. An egg that wants out through a rigid shell gives you: pressure from inside, the shell resisting, a sudden local failure, then release. Every beat of that skin comes out of those three answers, and none of it is a spinning ring.

Write the three answers down. They are the thing you check new ideas against.

## The signature move

One action that only this character could perform. It usually comes straight from the constraint: the moment the constraint gives, or the moment it does not.

Build `tap` around it — that is the beat the user triggers deliberately and watches. The other beats can be quieter, but they should belong to the same body.

## Vocabulary beyond translate and scale

Most first drafts move things and resize them, because those are the two fields you notice first. The renderer affords considerably more. Reach for these before settling:

| Device | How | Reads as |
| --- | --- | --- |
| A piece leaves | a layer with its own transform and fade | breaking, shedding, launching |
| Hinge, not slide | `pivot_x`/`pivot_y` at the joint | a lid, a jaw, a wing, a door |
| Travelling bulge | keyframes of one path with the same command sequence, numbers moved locally | something moving *inside*, pressure, a swallow |
| A hole opens or closes | `fill_rule: evenodd` plus a second subpath that grows | an eye, a mouth, a wound, a window |
| A line grows | an open `polyline` revealed by extending it, or `stroke_width` changing | a crack spreading, ink being drawn, a scar |
| Colour shifts | per-layer `fill` on a keyframe | heating, ripening, waking, souring |
| Silhouette inverts | crossfade between two deliberately different structures | transformation, a cut, a reveal |
| Z-order changes | reorder layers between keyframes | turning around, something surfacing |
| Counter-motion | one layer moves against the others | weight, recoil, something loose inside |
| Overshoot and settle | a keyframe past the target, then back | softness, momentum, life |
| Anticipation | a small move the *wrong* way first | intent — the character decided to move |

At 46 px, devices that change the **silhouette** beat devices that change interior detail. A bulge travelling across the outline reads; a dot moving around inside an unchanged outline barely does.

## Make the five beats differ in kind

`rest`, `idle`, `thinking`, `alert`, `tap` should not be five amplitudes of one motion. Give them different jobs:

- **rest** — the character existing. Breathing, settling, the slow thing it does all day. Small but never still.
- **idle** — a flicker of interiority: a blink, a twitch, a glance, a shiver. Brief, occasional, not a second loop of `rest`.
- **thinking** — work happening. Something is being processed and the outside can tell. This is where spinners get reached for; instead ask what *this* body does while busy. The egg's answer was pressure moving around inside the shell.
- **alert** — it wants your attention now. Sharp, asymmetric, unmistakable at a glance.
- **tap** — you touched it and it responded. The signature move belongs here. Must begin within `0.15s`.

If two beats would be interchangeable with a relabel, one of them is not designed yet.

## Hold a rule across the whole skin

Pick two or three rules and keep them everywhere. Consistency is most of what reads as character:

- "It never leaves the ground."
- "Every move is two beats: hesitate, then commit."
- "The shell always resists before it yields."
- "Nothing ever fully straightens."

Rules also generate: once you have "hesitate then commit", the `alert` keyframes almost write themselves.

## Before delivering: the substitution test

For each action, write one sentence: *this action shows that the character is ___.*

Then substitute a different character into the sentence. If it still holds — "shows that the character is thinking", "shows that the character is alive" — the action is filler and carries no identity. Rewrite it until the sentence only works for this character: *this action shows the chick shoving at a shell that has not given yet.*

Do this before packaging. It costs a minute and it is the only check that catches a competent, well-measured, completely generic skin.

## A worked derivation

> **Premise.** 一朵水仙花.
>
> **Material** — thin stem, heavy head; petals are separate and flexible; the whole thing returns to upright because the stem is springy, not because it decides to.
> **Desire** — light.
> **Constraint** — rooted; it can lean, never walk.
>
> **Rule** — the head always arrives late. Whatever the stem does, the head does a beat behind.
>
> - **rest**: stem leans a few degrees one way and back, head trailing. Counter-motion, ~8% travel.
> - **idle**: one petal flicks, alone. A separate layer, hinged at its base. Nothing else moves.
> - **thinking**: the head describes a slow arc as if searching; petals lag at the turns. Not a spin — an arc with a hesitation at each end.
> - **alert**: stem snaps upright, head overshoots and settles. Sharp, one direction.
> - **tap**: petals open outward from the centre, hinged at their bases, then relax most of the way back — never all the way. (Signature: only a flower opens.)
>
> Every one of those came out of "thin springy stem, heavy head, wants light, cannot move". None of them needed a new renderer feature, and none would transplant onto a different character.
